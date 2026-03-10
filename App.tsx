import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Plus,
  LayoutDashboard,
  History,
  X,
  BarChart3,
  Target,
  Activity,
  Tag,
  ArrowDownLeft,
  ArrowUpRight
} from 'lucide-react';

// Common Components
import { ThemeToggle } from './components/ThemeToggle';
import { StatsGrid } from './components/StatsGrid';
import { TransactionModal } from './components/TransactionModal';
import { SMSModal } from './components/SMSModal';
import { CategoryModal } from './components/CategoryModal';

// Tabs
import { DashboardTab } from './components/DashboardTab';
import { HistoryTab } from './components/HistoryTab';
import { BudgetsTab } from './components/BudgetsTab';
import { SummaryTab } from './components/SummaryTab';
import { ReportsTab } from './components/ReportsTab';
import { CategoriesTab } from './components/CategoriesTab';

// Utils & Types
import { format } from 'date-fns/format';
import { subMonths } from 'date-fns/subMonths';
import { startOfWeek } from 'date-fns/startOfWeek';
import { endOfWeek } from 'date-fns/endOfWeek';
import { startOfMonth } from 'date-fns/startOfMonth';
import { endOfMonth } from 'date-fns/endOfMonth';
import { startOfYear } from 'date-fns/startOfYear';
import { endOfYear } from 'date-fns/endOfYear';
import { isWithinInterval } from 'date-fns/isWithinInterval';
import { eachDayOfInterval } from 'date-fns/eachDayOfInterval';
import { eachMonthOfInterval } from 'date-fns/eachMonthOfInterval';
import { eachWeekOfInterval } from 'date-fns/eachWeekOfInterval';
import { isSameDay } from 'date-fns/isSameDay';
import { isSameMonth } from 'date-fns/isSameMonth';
import { isSameWeek } from 'date-fns/isSameWeek';
import { GoogleGenAI, Type } from "@google/genai";
import { cn, formatCurrency } from './utils';
import { Transaction, Budget, Category } from './types';
import { Sector } from 'recharts';

export default function App() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [editingTransaction, setEditingTransaction] = useState<Transaction | null>(null);
  const [showAddCategoryModal, setShowAddCategoryModal] = useState(false);
  const [activeTab, setActiveTab] = useState<'dashboard' | 'history' | 'budgets' | 'summary' | 'reports' | 'categories'>('dashboard');
  const [chartType, setChartType] = useState<'expense' | 'income'>('expense');
  const [searchQuery, setSearchQuery] = useState('');

  // SMS Import State
  const [showSMSModal, setShowSMSModal] = useState(false);
  const [smsText, setSmsText] = useState('');
  const [isParsing, setIsParsing] = useState(false);

  // Sorting and Filtering State
  const [sortField, setSortField] = useState<'date' | 'amount' | 'category'>('date');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');
  const [filterCategory, setFilterCategory] = useState<string>('all');
  const [dateFilter, setDateFilter] = useState<{ start: string, end: string }>({ start: '', end: '' });

  // Reports State
  const [reportPeriod, setReportPeriod] = useState<'weekly' | 'monthly' | 'yearly'>('monthly');

  // Chart Active Index State
  const [dashboardPieIndex, setDashboardPieIndex] = useState(0);
  const [reportPieIndex, setReportPieIndex] = useState(0);

  // Alert State
  const [showSpendingAlert, setShowSpendingAlert] = useState(true);

  // Form State
  const [formData, setFormData] = useState({
    description: '',
    amount: '',
    category: '',
    type: 'expense' as 'income' | 'expense',
    date: format(new Date(), 'yyyy-MM-dd'),
    isRecurring: false,
    frequency: 'monthly' as 'daily' | 'weekly' | 'monthly' | 'yearly',
  });

  const [categoryFormData, setCategoryFormData] = useState({
    name: '',
    type: 'expense' as 'income' | 'expense',
    color: '#6366f1',
  });

  const categoryColorMap = categories.reduce((acc, cat) => {
    acc[cat.name] = cat.color || '#94a3b8';
    return acc;
  }, {} as Record<string, string>);

  useEffect(() => {
    fetchTransactions();
    fetchBudgets();
    fetchCategories();

    // Android SMS Listener
    if (typeof window !== 'undefined' && (window as any).Capacitor) {
      const { registerPlugin } = (window as any).Capacitor;
      const SpendWise = (window as any).SpendWise || registerPlugin('SpendWise');

      SpendWise.addListener('onSMSReceived', (data: { sender: string; body: string }) => {
        setSmsText(data.body);
        setShowSMSModal(true);
      });
    }
  }, []);

  const fetchTransactions = async () => {
    try {
      const res = await fetch('/api/transactions');
      const data = await res.json();
      setTransactions(data);
    } catch (error) {
      console.error('Failed to fetch transactions:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchCategories = async () => {
    try {
      const res = await fetch('/api/categories');
      const data = await res.json();
      setCategories(data);
      if (data.length > 0) {
        setFormData(prev => ({
          ...prev,
          category: (data as Category[]).find((c: Category) => c.type === prev.type)?.name || (data as Category[])[0].name
        }));
      }
    } catch (error) {
      console.error('Failed to fetch categories:', error);
    }
  };

  const fetchBudgets = async () => {
    try {
      const res = await fetch('/api/budgets');
      const data = await res.json();
      setBudgets(data);
    } catch (error) {
      console.error('Failed to fetch budgets:', error);
    }
  };

  const handleSetBudget = async (category: string, amount: number) => {
    try {
      const res = await fetch('/api/budgets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ category, amount }),
      });
      if (res.ok) {
        const updatedBudget = await res.json();
        setBudgets(prev => {
          const index = prev.findIndex(b => b.category === category);
          if (index > -1) {
            const newBudgets = [...prev];
            newBudgets[index] = updatedBudget;
            return newBudgets;
          }
          return [...prev, updatedBudget];
        });
      }
    } catch (error) {
      console.error('Failed to set budget:', error);
    }
  };

  const handleDeleteCategory = async (id: number) => {
    if (!confirm('Are you sure you want to delete this category?')) return;
    try {
      const res = await fetch(`/api/categories/${id}`, { method: 'DELETE' });
      if (res.ok) {
        setCategories(prev => prev.filter(c => c.id !== id));
      }
    } catch (error) {
      console.error('Failed to delete category:', error);
    }
  };

  const handleAddCategory = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const res = await fetch('/api/categories', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(categoryFormData),
      });
      if (res.ok) {
        const newCategory = await res.json();
        setCategories(prev => [...prev, newCategory]);
        setShowAddCategoryModal(false);
        setCategoryFormData({ name: '', type: 'expense', color: '#6366f1' });
      }
    } catch (error) {
      console.error('Failed to add category:', error);
    }
  };

  const startEditTransaction = (tx: Transaction) => {
    setEditingTransaction(tx);
    setFormData({
      description: tx.description,
      amount: tx.amount.toString(),
      category: tx.category,
      type: tx.type,
      date: tx.date,
      isRecurring: false,
      frequency: 'monthly'
    });
    setShowAddModal(true);
  };

  const closeTransactionModal = () => {
    setShowAddModal(false);
    setEditingTransaction(null);
    setFormData({
      description: '',
      amount: '',
      category: categories.find(c => c.type === 'expense')?.name || '',
      type: 'expense',
      date: format(new Date(), 'yyyy-MM-dd'),
      isRecurring: false,
      frequency: 'monthly',
    });
  };

  const handleAddTransaction = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.description || !formData.amount) return;
    try {
      const method = editingTransaction ? 'PUT' : 'POST';
      const url = editingTransaction ? `/api/transactions/${editingTransaction.id}` :
        (formData.isRecurring ? '/api/recurring' : '/api/transactions');

      const body = { ...formData, amount: parseFloat(formData.amount) };
      if (formData.isRecurring && !editingTransaction) {
        (body as any).start_date = formData.date;
      }

      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (res.ok) {
        fetchTransactions();
        closeTransactionModal();
      }
    } catch (error) {
      console.error('Failed to save transaction:', error);
    }
  };

  const parseSMS = async () => {
    if (!smsText.trim()) return;
    setIsParsing(true);
    try {
      const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY || '' });
      const response = await ai.models.generateContent({
        model: "gemini-3-flash-preview",
        contents: `Extract transaction details from this SMS: "${smsText}". 
        Current date is ${format(new Date(), 'yyyy-MM-dd')}.`,
        config: {
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              description: { type: Type.STRING },
              amount: { type: Type.NUMBER },
              category: { type: Type.STRING },
              type: { type: Type.STRING },
              date: { type: Type.STRING },
            },
            required: ["description", "amount", "category", "type", "date"],
          },
        },
      });

      const result = JSON.parse(response.text || '{}');
      setFormData({
        ...formData,
        description: result.description || '',
        amount: result.amount?.toString() || '',
        category: result.category || '',
        type: (result.type as any) || 'expense',
        date: result.date || format(new Date(), 'yyyy-MM-dd'),
      });
      setShowSMSModal(false);
      setShowAddModal(true);
    } catch (error) {
      console.error('Failed to parse SMS:', error);
    } finally {
      setIsParsing(false);
    }
  };

  const deleteTransaction = async (id: number) => {
    if (!confirm('Are you sure you want to delete this transaction?')) return;
    try {
      await fetch(`/api/transactions/${id}`, { method: 'DELETE' });
      setTransactions(transactions.filter(t => t.id !== id));
    } catch (error) {
      console.error('Failed to delete transaction:', error);
    }
  };

  // Calculations
  const totalIncome = transactions.filter(t => t.type === 'income').reduce((acc, t) => acc + t.amount, 0);
  const totalExpenses = transactions.filter(t => t.type === 'expense').reduce((acc, t) => acc + t.amount, 0);
  const balance = totalIncome - totalExpenses;

  const chartData = Object.entries(
    transactions.filter(t => t.type === chartType).reduce((acc, t) => {
      acc[t.category] = (acc[t.category] || 0) + t.amount;
      return acc;
    }, {} as Record<string, number>)
  ).map(([name, value]) => ({ name, value }));

  const barChartData = Object.values(transactions.reduce((acc, t) => {
    const month = format(new Date(t.date), 'MMM yyyy');
    if (!acc[month]) acc[month] = { name: month, income: 0, expense: 0 };
    acc[month][t.type] += t.amount;
    return acc;
  }, {} as Record<string, any>)).sort((a: any, b: any) => new Date(a.name).getTime() - new Date(b.name).getTime());

  const filteredTransactions = transactions
    .filter(tx => tx.description.toLowerCase().includes(searchQuery.toLowerCase()))
    .filter(tx => filterCategory === 'all' || tx.category === filterCategory)
    .filter(tx => (!dateFilter.start || tx.date >= dateFilter.start) && (!dateFilter.end || tx.date <= dateFilter.end))
    .sort((a, b) => {
      let comp = 0;
      if (sortField === 'date') comp = new Date(a.date).getTime() - new Date(b.date).getTime();
      else if (sortField === 'amount') comp = a.amount - b.amount;
      else comp = a.category.localeCompare(b.category);
      return sortOrder === 'asc' ? comp : -comp;
    });

  const exportCSV = () => {
    const csv = ['Date,Description,Category,Type,Amount', ...filteredTransactions.map(tx =>
      `${tx.date},"${tx.description}",${tx.category},${tx.type},${tx.amount}`)].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `spendwise-export-${format(new Date(), 'yyyy-MM-dd')}.csv`;
    a.click();
  };

  // Summary Logic
  const currentMonth = format(new Date(), 'MMM yyyy');
  const prevMonth = format(subMonths(new Date(), 1), 'MMM yyyy');
  const cmTransactions = transactions.filter(t => format(new Date(t.date), 'MMM yyyy') === currentMonth);
  const pmExpenses = transactions.filter(t => format(new Date(t.date), 'MMM yyyy') === prevMonth && t.type === 'expense').reduce((a, b) => a + b.amount, 0);
  const cmIncome = cmTransactions.filter(t => t.type === 'income').reduce((a, b) => a + b.amount, 0);
  const cmExpenses = cmTransactions.filter(t => t.type === 'expense').reduce((a, b) => a + b.amount, 0);
  const isSpendingUp = pmExpenses > 0 && cmExpenses > pmExpenses * 1.1;
  const budgetAdherence = cmExpenses > 0 ? Math.max(0, 100 - (cmExpenses / (budgets.reduce((a, b) => a + b.amount, 0) || 1)) * 100) : 100;

  const categoryAverages = categories.filter(c => c.type === 'expense').map(cat => ({
    category: cat.name,
    average: transactions.filter(t => t.category === cat.name && t.type === 'expense').reduce((a, b) => a + b.amount, 0) / (new Set(transactions.map(t => format(new Date(t.date), 'MMM yyyy'))).size || 1)
  }));

  const getReportData = () => {
    const now = new Date();
    let start: Date, end: Date;
    if (reportPeriod === 'weekly') { start = startOfWeek(now); end = endOfWeek(now); }
    else if (reportPeriod === 'monthly') { start = startOfMonth(now); end = endOfMonth(now); }
    else { start = startOfYear(now); end = endOfYear(now); }

    const pTransactions = transactions.filter(t => isWithinInterval(new Date(t.date), { start, end }));
    const eTransactions = pTransactions.filter(t => t.type === 'expense');

    return {
      reportCategoryData: categories.filter(c => c.type === 'expense').map(cat => ({
        name: cat.name,
        value: eTransactions.filter(t => t.category === cat.name).reduce((a, b) => a + b.amount, 0),
        color: cat.color || '#64748b'
      })).filter(d => d.value > 0),
      reportTrendData: eachMonthOfInterval({ start, end }).map(d => ({
        name: format(d, 'MMM'),
        value: eTransactions.filter(t => isSameMonth(new Date(t.date), d)).reduce((a, b) => a + b.amount, 0)
      })),
      reportTotalSpending: eTransactions.reduce((a, b) => a + b.amount, 0),
      periodLabel: format(now, reportPeriod === 'yearly' ? 'yyyy' : 'MMMM yyyy')
    };
  };

  const { reportCategoryData, reportTrendData, reportTotalSpending, periodLabel } = getReportData();

  // Tooltips & Active Shapes
  const CustomPieTooltip = ({ active, payload }: any) => active && payload?.[0] && (
    <div className="bg-white dark:bg-zinc-900 p-2 rounded-xl shadow-lg border dark:border-zinc-800 text-xs font-bold">
      {payload[0].name}: {formatCurrency(payload[0].value)}
    </div>
  );

  const renderActiveShape = (props: any) => (
    <Sector cx={props.cx} cy={props.cy} innerRadius={props.innerRadius} outerRadius={props.outerRadius + 6} startAngle={props.startAngle} endAngle={props.endAngle} fill={props.fill} />
  );

  if (loading) return (
    <div className="flex items-center justify-center min-h-screen bg-zinc-50 dark:bg-zinc-950">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-600"></div>
    </div>
  );

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 md:py-12 pb-24 md:pb-12 dark:text-zinc-100 min-h-screen">
      <header className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div className="flex items-center justify-between w-full md:w-auto">
          <div>
            <h1 className="text-3xl gradient-text tracking-tight">SpendWise</h1>
            <p className="text-zinc-500 text-sm font-medium">Precision financial management.</p>
          </div>
          <div className="md:hidden"><ThemeToggle /></div>
        </div>

        <nav className="hidden md:flex items-center gap-1 p-1 bg-zinc-100 dark:bg-zinc-800/50 rounded-xl">
          <ThemeToggle />
          {[
            { id: 'dashboard', icon: <LayoutDashboard size={18} />, label: 'Dashboard' },
            { id: 'history', icon: <History size={18} />, label: 'History' },
            { id: 'budgets', icon: <Target size={18} />, label: 'Budgets' },
            { id: 'categories', icon: <Tag size={18} />, label: 'Categories' },
            { id: 'summary', icon: <Activity size={18} />, label: 'Summary' },
            { id: 'reports', icon: <BarChart3 size={18} />, label: 'Reports' }
          ].map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={cn("flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-bold transition-all",
                activeTab === tab.id ? "bg-white dark:bg-zinc-900 shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700 dark:text-zinc-400")}
            >
              {tab.icon} {tab.label}
            </button>
          ))}
        </nav>
      </header>

      <main>
        {activeTab === 'dashboard' && (
          <DashboardTab
            balance={balance} totalIncome={totalIncome} totalExpenses={totalExpenses}
            isSpendingUp={isSpendingUp} showSpendingAlert={showSpendingAlert} setShowSpendingAlert={setShowSpendingAlert}
            chartType={chartType} setChartType={setChartType} chartData={chartData} categoryColorMap={categoryColorMap}
            recentTransactions={transactions.slice(0, 5)} setActiveTab={setActiveTab} setShowAddModal={setShowAddModal} setShowSMSModal={setShowSMSModal}
            CustomPieTooltip={CustomPieTooltip} renderActiveShape={renderActiveShape}
            dashboardPieIndex={dashboardPieIndex} setDashboardPieIndex={setDashboardPieIndex}
          />
        )}
        {activeTab === 'history' && (
          <HistoryTab
            transactions={transactions} filteredTransactions={filteredTransactions} searchQuery={searchQuery} setSearchQuery={setSearchQuery}
            filterCategory={filterCategory} setFilterCategory={setFilterCategory} dateFilter={dateFilter} setDateFilter={setDateFilter}
            sortField={sortField} setSortField={setSortField} sortOrder={sortOrder} setSortOrder={setSortOrder}
            categories={categories} exportCSV={exportCSV} startEditTransaction={startEditTransaction} deleteTransaction={deleteTransaction}
          />
        )}
        {activeTab === 'budgets' && (
          <BudgetsTab budgets={budgets} categories={categories} transactions={transactions} handleSetBudget={handleSetBudget} />
        )}
        {activeTab === 'categories' && (
          <CategoriesTab categories={categories} setShowAddCategoryModal={setShowAddCategoryModal} handleDeleteCategory={handleDeleteCategory} />
        )}
        {activeTab === 'summary' && (
          <SummaryTab
            monthlyIncome={cmIncome} monthlyExpenses={cmExpenses} monthlySavings={cmIncome - cmExpenses}
            savingsRate={cmIncome > 0 ? ((cmIncome - cmExpenses) / cmIncome) * 100 : 0} budgetAdherence={budgetAdherence}
            averageMonthlySpending={totalExpenses / (new Set(transactions.map(t => format(new Date(t.date), 'MMM yyyy'))).size || 1)}
            topCategory={categoryAverages.sort((a, b) => b.average - a.average)[0]} categoryAverages={categoryAverages}
            categoryColorMap={categoryColorMap} barChartData={barChartData}
            CustomBarTooltip={({ active, payload }: any) => active && payload?.[0] && <div className="bg-white p-2 rounded shadow text-xs font-bold">{payload[0].value}</div>}
            CustomAvgTooltip={({ active, payload }: any) => active && payload?.[0] && <div className="bg-white p-2 rounded shadow text-xs font-bold">{payload[0].value}</div>}
          />
        )}
        {activeTab === 'reports' && (
          <ReportsTab
            reportPeriod={reportPeriod} setReportPeriod={setReportPeriod} periodLabel={periodLabel} reportTotalSpending={reportTotalSpending}
            reportCategoryData={reportCategoryData} reportTrendData={reportTrendData} reportPieIndex={reportPieIndex} setReportPieIndex={setReportPieIndex}
            renderActiveShape={renderActiveShape} CustomReportPieTooltip={CustomPieTooltip}
            CustomReportBarTooltip={({ active, payload }: any) => active && payload?.[0] && <div className="bg-white p-2 rounded shadow text-xs font-bold">{payload[0].value}</div>}
          />
        )}
      </main>

      {/* Modals */}
      <TransactionModal
        isOpen={showAddModal} onClose={closeTransactionModal} onSubmit={handleAddTransaction}
        editingTransaction={editingTransaction} formData={formData} setFormData={setFormData} categories={categories}
      />
      <SMSModal isOpen={showSMSModal} onClose={() => setShowSMSModal(false)} smsText={smsText} setSmsText={setSmsText} isParsing={isParsing} onParse={parseSMS} />
      <CategoryModal isOpen={showAddCategoryModal} onClose={() => setShowAddCategoryModal(false)} onSubmit={handleAddCategory} categoryFormData={categoryFormData} setCategoryFormData={setCategoryFormData} />

      {/* Mobile Nav */}
      <footer className="md:hidden fixed bottom-0 left-0 right-0 bg-white dark:bg-zinc-900 border-t border-zinc-200 dark:border-zinc-800 px-4 py-2 flex justify-between z-40 pb-safe shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)]">
        {[
          { id: 'dashboard', icon: <LayoutDashboard size={20} />, label: 'Home' },
          { id: 'history', icon: <History size={20} />, label: 'History' },
          { id: 'budgets', icon: <Target size={20} />, label: 'Budgets' },
          { id: 'summary', icon: <Activity size={20} />, label: 'Summary' },
          { id: 'reports', icon: <BarChart3 size={20} />, label: 'Reports' }
        ].map(n => (
          <button key={n.id} onClick={() => setActiveTab(n.id as any)} className={cn("flex flex-col items-center gap-1 p-2 transition-colors", activeTab === n.id ? "text-indigo-600" : "text-zinc-400 hover:text-zinc-600")}>
            {n.icon} <span className="text-[10px] font-bold uppercase tracking-tighter">{n.label}</span>
          </button>
        ))}
      </footer>
    </div>
  );
}
