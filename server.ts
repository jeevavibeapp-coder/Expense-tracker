import express from "express";
import { createServer as createViteServer } from "vite";
import Database from "better-sqlite3";
import path from "path";
import fs from "fs";

const db = new Database("expenses.db");

// Initialize database
db.exec(`
  CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    description TEXT NOT NULL,
    amount REAL NOT NULL,
    category TEXT NOT NULL,
    type TEXT CHECK(type IN ('income', 'expense')) NOT NULL,
    date TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  )
`);

db.exec(`
  CREATE TABLE IF NOT EXISTS budgets (
    category TEXT PRIMARY KEY,
    amount REAL NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
  )
`);

db.exec(`
  CREATE TABLE IF NOT EXISTS categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL,
    type TEXT CHECK(type IN ('income', 'expense')) NOT NULL,
    icon TEXT,
    color TEXT
  )
`);

db.exec(`
  CREATE TABLE IF NOT EXISTS recurring_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    description TEXT NOT NULL,
    amount REAL NOT NULL,
    category TEXT NOT NULL,
    type TEXT CHECK(type IN ('income', 'expense')) NOT NULL,
    frequency TEXT CHECK(frequency IN ('daily', 'weekly', 'monthly', 'yearly')) NOT NULL,
    next_date TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  )
`);

// Process recurring transactions
function processRecurring() {
  const due = db.prepare("SELECT * FROM recurring_transactions WHERE next_date <= date('now')").all() as any[];
  if (due.length === 0) return;

  const insertTx = db.prepare("INSERT INTO transactions (description, amount, category, type, date) VALUES (?, ?, ?, ?, ?)");
  const updateNext = db.prepare("UPDATE recurring_transactions SET next_date = ? WHERE id = ?");

  db.transaction(() => {
    for (const r of due) {
      // Insert the actual transaction
      insertTx.run(r.description, r.amount, r.category, r.type, r.next_date);

      // Calculate next date
      let nextDate = new Date(r.next_date);
      if (r.frequency === 'daily') nextDate.setDate(nextDate.getDate() + 1);
      else if (r.frequency === 'weekly') nextDate.setDate(nextDate.getDate() + 7);
      else if (r.frequency === 'monthly') nextDate.setMonth(nextDate.getMonth() + 1);
      else if (r.frequency === 'yearly') nextDate.setFullYear(nextDate.getFullYear() + 1);

      const nextDateString = nextDate.toISOString().split('T')[0];
      updateNext.run(nextDateString, r.id);
    }
  })();
}

// Seed default categories if empty
const categoryCount = db.prepare("SELECT COUNT(*) as count FROM categories").get() as { count: number };
if (categoryCount.count === 0) {
  const defaultCategories = [
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
    { name: 'Other Expense', type: 'expense', icon: 'PlusCircle', color: '#64748b' }
  ];

  const insertCategory = db.prepare("INSERT INTO categories (name, type, icon, color) VALUES (?, ?, ?, ?)");
  for (const cat of defaultCategories) {
    insertCategory.run(cat.name, cat.type, cat.icon, cat.color);
  }
}

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(express.json());

  // API Routes
  app.get("/api/categories", (req, res) => {
    const categories = db.prepare("SELECT * FROM categories").all();
    res.json(categories);
  });

  app.post("/api/categories", (req, res) => {
    const { name, type, icon, color } = req.body;
    try {
      const info = db.prepare(
        "INSERT INTO categories (name, type, icon, color) VALUES (?, ?, ?, ?)"
      ).run(name, type, icon, color);
      const newCategory = db.prepare("SELECT * FROM categories WHERE id = ?").get(info.lastInsertRowid);
      res.status(201).json(newCategory);
    } catch (error: any) {
      if (error.code === 'SQLITE_CONSTRAINT') {
        res.status(400).json({ error: "Category already exists" });
      } else {
        res.status(500).json({ error: "Internal server error" });
      }
    }
  });

  app.delete("/api/categories/:id", (req, res) => {
    db.prepare("DELETE FROM categories WHERE id = ?").run(req.params.id);
    res.status(204).end();
  });

  app.get("/api/transactions", (req, res) => {
    processRecurring();
    const transactions = db.prepare("SELECT * FROM transactions ORDER BY date DESC").all();
    res.json(transactions);
  });

  app.post("/api/transactions", (req, res) => {
    const { description, amount, category, type, date } = req.body;
    const info = db.prepare(
      "INSERT INTO transactions (description, amount, category, type, date) VALUES (?, ?, ?, ?, ?)"
    ).run(description, amount, category, type, date);

    const newTransaction = db.prepare("SELECT * FROM transactions WHERE id = ?").get(info.lastInsertRowid);
    res.status(201).json(newTransaction);
  });

  app.put("/api/transactions/:id", (req, res) => {
    const { description, amount, category, type, date } = req.body;
    db.prepare(
      "UPDATE transactions SET description = ?, amount = ?, category = ?, type = ?, date = ? WHERE id = ?"
    ).run(description, amount, category, type, date, req.params.id);
    const updated = db.prepare("SELECT * FROM transactions WHERE id = ?").get(req.params.id);
    res.json(updated);
  });

  app.delete("/api/transactions/:id", (req, res) => {
    db.prepare("DELETE FROM transactions WHERE id = ?").run(req.params.id);
    res.status(204).end();
  });

  app.get("/api/recurring", (req, res) => {
    const r = db.prepare("SELECT * FROM recurring_transactions").all();
    res.json(r);
  });

  app.post("/api/recurring", (req, res) => {
    const { description, amount, category, type, frequency, start_date } = req.body;
    const info = db.prepare(
      "INSERT INTO recurring_transactions (description, amount, category, type, frequency, next_date) VALUES (?, ?, ?, ?, ?, ?)"
    ).run(description, amount, category, type, frequency, start_date);
    const newR = db.prepare("SELECT * FROM recurring_transactions WHERE id = ?").get(info.lastInsertRowid);
    res.status(201).json(newR);
  });

  app.delete("/api/recurring/:id", (req, res) => {
    db.prepare("DELETE FROM recurring_transactions WHERE id = ?").run(req.params.id);
    res.status(204).end();
  });

  app.get("/api/stats", (req, res) => {
    const stats = db.prepare(`
      SELECT 
        SUM(CASE WHEN type = 'income' THEN amount ELSE 0 END) as totalIncome,
        SUM(CASE WHEN type = 'expense' THEN amount ELSE 0 END) as totalExpenses,
        category,
        type
      FROM transactions
      GROUP BY category, type
    `).all();
    res.json(stats);
  });

  app.get("/api/budgets", (req, res) => {
    const budgets = db.prepare("SELECT * FROM budgets").all();
    res.json(budgets);
  });

  app.post("/api/budgets", (req, res) => {
    const { category, amount } = req.body;
    db.prepare(`
      INSERT INTO budgets (category, amount) 
      VALUES (?, ?) 
      ON CONFLICT(category) DO UPDATE SET amount = excluded.amount, updated_at = CURRENT_TIMESTAMP
    `).run(category, amount);
    res.status(200).json({ category, amount });
  });

  // Vite middleware for development
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    // Production static serving
    const distPath = path.resolve(__dirname, "dist");
    if (fs.existsSync(distPath)) {
      app.use(express.static(distPath));
      app.get("*", (req, res) => {
        res.sendFile(path.resolve(distPath, "index.html"));
      });
    }
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
