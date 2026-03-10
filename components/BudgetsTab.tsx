import React from 'react';
import { motion } from 'motion/react';
import { Target, TrendingUp, TrendingDown, LayoutDashboard } from 'lucide-react';
import { cn, formatCurrency } from '../utils';
import { Budget, Category, Transaction } from '../types';

interface BudgetsTabProps {
    budgets: Budget[];
    categories: Category[];
    transactions: Transaction[];
    handleSetBudget: (category: string, amount: number) => void;
}

export function BudgetsTab({
    budgets,
    categories,
    transactions,
    handleSetBudget
}: BudgetsTabProps) {
    const currentMonthTransactions = transactions.filter(t => {
        const d = new Date(t.date);
        const now = new Date();
        return d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
    });

    return (
        <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="grid grid-cols-1 md:grid-cols-2 gap-6 md:gap-8"
        >
            <section className="p-6 md:p-8 premium-card">
                <h3 className="text-xl font-bold mb-6 flex items-center gap-2 dark:text-zinc-100">
                    <Target className="text-indigo-600" size={24} />
                    Set Monthly Budgets
                </h3>
                <div className="space-y-6">
                    {categories.filter(c => c.type === 'expense').map((cat) => (
                        <div key={cat.id} className="space-y-2">
                            <div className="flex justify-between items-center px-1">
                                <span className="text-sm font-bold text-zinc-700 dark:text-zinc-300">{cat.name}</span>
                                <span className="text-xs font-bold text-indigo-600 dark:text-indigo-400">
                                    Current: {formatCurrency(budgets.find(b => b.category === cat.name)?.amount || 0)}
                                </span>
                            </div>
                            <div className="relative">
                                <input
                                    type="number"
                                    placeholder="Set limit..."
                                    defaultValue={budgets.find(b => b.category === cat.name)?.amount}
                                    onBlur={(e) => {
                                        const val = parseFloat(e.target.value);
                                        if (!isNaN(val)) handleSetBudget(cat.name, val);
                                    }}
                                    className="w-full p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all text-sm dark:text-zinc-100"
                                />
                            </div>
                        </div>
                    ))}
                </div>
            </section>

            <section className="p-6 md:p-8 premium-card">
                <h3 className="text-xl font-bold mb-6 flex items-center gap-2 dark:text-zinc-100">
                    <TrendingUp className="text-emerald-600" size={24} />
                    Budget Tracker
                </h3>
                <div className="space-y-8">
                    {budgets.length > 0 ? (
                        budgets.map((budget) => {
                            const spent = currentMonthTransactions
                                .filter(t => t.category === budget.category && t.type === 'expense')
                                .reduce((acc, t) => acc + t.amount, 0);
                            const percent = Math.min(100, (spent / budget.amount) * 100);
                            const isOver = spent > budget.amount;

                            return (
                                <div key={budget.category} className="space-y-3">
                                    <div className="flex justify-between items-end">
                                        <div>
                                            <h4 className="font-bold text-zinc-900 dark:text-zinc-100">{budget.category}</h4>
                                            <p className="text-xs text-zinc-400 font-medium">
                                                {formatCurrency(spent)} of {formatCurrency(budget.amount)} spent
                                            </p>
                                        </div>
                                        <span className={cn(
                                            "text-xs font-black uppercase tracking-widest",
                                            isOver ? "text-rose-600" : percent > 80 ? "text-amber-600" : "text-emerald-600"
                                        )}>
                                            {isOver ? 'Exceeded' : `${Math.round(percent)}%`}
                                        </span>
                                    </div>
                                    <div className="h-3 w-full bg-zinc-100 dark:bg-zinc-800 rounded-full overflow-hidden p-0.5">
                                        <motion.div
                                            initial={{ width: 0 }}
                                            animate={{ width: `${percent}%` }}
                                            transition={{ duration: 1, ease: 'easeOut' }}
                                            className={cn(
                                                "h-full rounded-full",
                                                isOver ? "bg-rose-500" : percent > 80 ? "bg-amber-500" : "bg-emerald-500"
                                            )}
                                        />
                                    </div>
                                </div>
                            );
                        })
                    ) : (
                        <div className="py-12 flex flex-col items-center justify-center text-zinc-400 gap-3">
                            <LayoutDashboard size={32} />
                            <p className="text-sm">No budgets set yet.</p>
                        </div>
                    )}
                </div>
            </section>
        </motion.div>
    );
}
