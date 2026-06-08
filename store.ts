import { Transaction, Budget, Category } from './types';

/**
 * On-device persistence layer for the standalone Android/PWA build.
 *
 * The original app talked to an Express + SQLite backend over `/api/*`.
 * A Capacitor APK ships only static web assets inside a WebView with no
 * server, so all data is persisted locally with `localStorage`. This keeps
 * the app fully offline and compatible with every Android WebView.
 */

interface RecurringTransaction {
  id: number;
  description: string;
  amount: number;
  category: string;
  type: 'income' | 'expense';
  frequency: 'daily' | 'weekly' | 'monthly' | 'yearly';
  next_date: string;
}

const KEYS = {
  transactions: 'spendwise_transactions',
  budgets: 'spendwise_budgets',
  categories: 'spendwise_categories',
  recurring: 'spendwise_recurring',
  seeded: 'spendwise_seeded',
} as const;

const DEFAULT_CATEGORIES: Omit<Category, 'id'>[] = [
  { name: 'Salary', type: 'income', icon: 'Wallet', color: '#10b981' },
  { name: 'Freelance', type: 'income', icon: 'Briefcase', color: '#3b82f6' },
  { name: 'Investments', type: 'income', icon: 'TrendingUp', color: '#8b5cf6' },
  { name: 'Other Income', type: 'income', icon: 'PlusCircle', color: '#64748b' },
  { name: 'Food & Dining', type: 'expense', icon: 'Utensils', color: '#f43f5e' },
  { name: 'Shopping', type: 'expense', icon: 'ShoppingBag', color: '#ec4899' },
  { name: 'Transport', type: 'expense', icon: 'Car', color: '#f59e0b' },
  { name: 'Entertainment', type: 'expense', icon: 'Tv', color: '#8b5cf6' },
  { name: 'Health', type: 'expense', icon: 'HeartPulse', color: '#ef4444' },
  { name: 'Bills & Utilities', type: 'expense', icon: 'Zap', color: '#3b82f6' },
  { name: 'Other Expense', type: 'expense', icon: 'PlusCircle', color: '#64748b' },
];

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

function write<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (error) {
    console.error('Failed to persist data:', error);
  }
}

function nextId(items: { id: number }[]): number {
  return items.reduce((max, item) => Math.max(max, item.id), 0) + 1;
}

function ensureSeed(): void {
  if (!localStorage.getItem(KEYS.seeded)) {
    const seeded = DEFAULT_CATEGORIES.map((cat, index) => ({ ...cat, id: index + 1 }));
    write(KEYS.categories, seeded);
    localStorage.setItem(KEYS.seeded, '1');
  }
}

function addFrequency(date: Date, frequency: RecurringTransaction['frequency']): Date {
  const next = new Date(date);
  if (frequency === 'daily') next.setDate(next.getDate() + 1);
  else if (frequency === 'weekly') next.setDate(next.getDate() + 7);
  else if (frequency === 'monthly') next.setMonth(next.getMonth() + 1);
  else if (frequency === 'yearly') next.setFullYear(next.getFullYear() + 1);
  return next;
}

function todayISO(): string {
  return new Date().toISOString().split('T')[0];
}

/** Materialise any recurring transactions that are due into real transactions. */
function processRecurring(): void {
  const recurring = read<RecurringTransaction[]>(KEYS.recurring, []);
  if (recurring.length === 0) return;

  const transactions = read<Transaction[]>(KEYS.transactions, []);
  const today = todayISO();
  let changed = false;

  for (const r of recurring) {
    let guard = 0;
    while (r.next_date <= today && guard < 1000) {
      transactions.push({
        id: nextId(transactions),
        description: r.description,
        amount: r.amount,
        category: r.category,
        type: r.type,
        date: r.next_date,
      });
      r.next_date = addFrequency(new Date(r.next_date), r.frequency).toISOString().split('T')[0];
      changed = true;
      guard++;
    }
  }

  if (changed) {
    write(KEYS.transactions, transactions);
    write(KEYS.recurring, recurring);
  }
}

// ---------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------

export function getCategories(): Category[] {
  ensureSeed();
  return read<Category[]>(KEYS.categories, []);
}

export function addCategory(input: { name: string; type: 'income' | 'expense'; color: string; icon?: string }): Category {
  const categories = getCategories();
  const exists = categories.some(c => c.name.toLowerCase() === input.name.trim().toLowerCase());
  if (exists) {
    throw new Error('Category already exists');
  }
  const category: Category = {
    id: nextId(categories),
    name: input.name.trim(),
    type: input.type,
    icon: input.icon || 'Tag',
    color: input.color,
  };
  categories.push(category);
  write(KEYS.categories, categories);
  return category;
}

export function deleteCategory(id: number): void {
  const categories = getCategories().filter(c => c.id !== id);
  write(KEYS.categories, categories);
}

// ---------------------------------------------------------------------------
// Transactions
// ---------------------------------------------------------------------------

export function getTransactions(): Transaction[] {
  ensureSeed();
  processRecurring();
  const transactions = read<Transaction[]>(KEYS.transactions, []);
  return [...transactions].sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : b.id - a.id));
}

export function addTransaction(input: Omit<Transaction, 'id'>): Transaction {
  const transactions = read<Transaction[]>(KEYS.transactions, []);
  const transaction: Transaction = { ...input, id: nextId(transactions) };
  transactions.push(transaction);
  write(KEYS.transactions, transactions);
  return transaction;
}

export function updateTransaction(id: number, input: Omit<Transaction, 'id'>): Transaction {
  const transactions = read<Transaction[]>(KEYS.transactions, []);
  const index = transactions.findIndex(t => t.id === id);
  const updated: Transaction = { ...input, id };
  if (index > -1) {
    transactions[index] = updated;
    write(KEYS.transactions, transactions);
  }
  return updated;
}

export function deleteTransaction(id: number): void {
  const transactions = read<Transaction[]>(KEYS.transactions, []).filter(t => t.id !== id);
  write(KEYS.transactions, transactions);
}

export function addRecurring(input: {
  description: string;
  amount: number;
  category: string;
  type: 'income' | 'expense';
  frequency: 'daily' | 'weekly' | 'monthly' | 'yearly';
  start_date: string;
}): RecurringTransaction {
  const recurring = read<RecurringTransaction[]>(KEYS.recurring, []);
  const entry: RecurringTransaction = {
    id: nextId(recurring),
    description: input.description,
    amount: input.amount,
    category: input.category,
    type: input.type,
    frequency: input.frequency,
    next_date: input.start_date,
  };
  recurring.push(entry);
  write(KEYS.recurring, recurring);
  // Immediately materialise any occurrences that are already due.
  processRecurring();
  return entry;
}

// ---------------------------------------------------------------------------
// Budgets
// ---------------------------------------------------------------------------

export function getBudgets(): Budget[] {
  return read<Budget[]>(KEYS.budgets, []);
}

export function setBudget(category: string, amount: number): Budget {
  const budgets = getBudgets();
  const index = budgets.findIndex(b => b.category === category);
  const budget: Budget = { category, amount };
  if (index > -1) budgets[index] = budget;
  else budgets.push(budget);
  write(KEYS.budgets, budgets);
  return budget;
}
