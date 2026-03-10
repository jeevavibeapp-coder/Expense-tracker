import { motion } from 'motion/react';
import { Wallet, TrendingUp, TrendingDown } from 'lucide-react';
import { formatCurrency } from '../utils';

interface StatsGridProps {
    balance: number;
    totalIncome: number;
    totalExpenses: number;
}

export function StatsGrid({ balance, totalIncome, totalExpenses }: StatsGridProps) {
    return (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                className="p-5 md:p-6 rounded-3xl bg-indigo-600 text-white shadow-xl shadow-indigo-200"
            >
                <div className="flex items-center justify-between mb-3 md:mb-4">
                    <div className="p-2 bg-white dark:bg-zinc-900/20 rounded-xl">
                        <Wallet size={20} className="md:w-6 md:h-6" />
                    </div>
                    <span className="text-[10px] md:text-xs font-medium uppercase tracking-wider opacity-80">Total Balance</span>
                </div>
                <h2 className="text-2xl md:text-3xl font-bold">{formatCurrency(balance)}</h2>
            </motion.div>

            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.1 }}
                className="p-5 md:p-6 rounded-3xl bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 shadow-sm"
            >
                <div className="flex items-center justify-between mb-3 md:mb-4">
                    <div className="p-2 bg-emerald-50 text-emerald-600 rounded-xl">
                        <TrendingUp size={20} className="md:w-6 md:h-6" />
                    </div>
                    <span className="text-[10px] md:text-xs font-medium uppercase tracking-wider text-zinc-400">Total Income</span>
                </div>
                <h2 className="text-2xl md:text-3xl font-bold text-zinc-900 dark:text-zinc-100">{formatCurrency(totalIncome)}</h2>
            </motion.div>

            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.2 }}
                className="p-5 md:p-6 rounded-3xl bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 shadow-sm"
            >
                <div className="flex items-center justify-between mb-3 md:mb-4">
                    <div className="p-2 bg-rose-50 text-rose-600 rounded-xl">
                        <TrendingDown size={20} className="md:w-6 md:h-6" />
                    </div>
                    <span className="text-[10px] md:text-xs font-medium uppercase tracking-wider text-zinc-400">Total Expenses</span>
                </div>
                <h2 className="text-2xl md:text-3xl font-bold text-zinc-900 dark:text-zinc-100">{formatCurrency(totalExpenses)}</h2>
            </motion.div>
        </div>
    );
}
