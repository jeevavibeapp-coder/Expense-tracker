import React from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { TrendingUp, X, PieChart as PieChartIcon, Search, Sparkles, Plus } from 'lucide-react';
import { format } from 'date-fns/format';
import { cn, formatCurrency } from '../utils';
import { StatsGrid } from './StatsGrid';
import { Transaction, Category } from '../types';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';

interface DashboardTabProps {
    balance: number;
    totalIncome: number;
    totalExpenses: number;
    isSpendingUp: boolean;
    showSpendingAlert: boolean;
    setShowSpendingAlert: (show: boolean) => void;
    chartType: 'income' | 'expense';
    setChartType: (type: 'income' | 'expense') => void;
    chartData: any[];
    categoryColorMap: Record<string, string>;
    recentTransactions: Transaction[];
    setActiveTab: (tab: any) => void;
    setShowAddModal: (show: boolean) => void;
    setShowSMSModal: (show: boolean) => void;
    CustomPieTooltip: any;
    renderActiveShape: any;
    dashboardPieIndex: number;
    setDashboardPieIndex: (index: number) => void;
}

export function DashboardTab({
    balance,
    totalIncome,
    totalExpenses,
    isSpendingUp,
    showSpendingAlert,
    setShowSpendingAlert,
    chartType,
    setChartType,
    chartData,
    categoryColorMap,
    recentTransactions,
    setActiveTab,
    setShowAddModal,
    setShowSMSModal,
    CustomPieTooltip,
    renderActiveShape,
    dashboardPieIndex,
    setDashboardPieIndex
}: DashboardTabProps) {
    return (
        <div className="space-y-8">
            {/* Spending Alert */}
            <AnimatePresence>
                {isSpendingUp && showSpendingAlert && (
                    <motion.div
                        initial={{ opacity: 0, y: -10, height: 0 }}
                        animate={{ opacity: 1, y: 0, height: 'auto' }}
                        exit={{ opacity: 0, y: -10, height: 0 }}
                        className="overflow-hidden"
                    >
                        <div className="p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-2xl flex items-start sm:items-center justify-between gap-4">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-amber-100 dark:bg-amber-800 rounded-lg text-amber-600 dark:text-amber-400">
                                    <TrendingUp size={20} />
                                </div>
                                <div>
                                    <h4 className="text-sm font-bold text-amber-900 dark:text-amber-100">Spending Alert</h4>
                                    <p className="text-sm text-amber-700 dark:text-amber-300">Your spending this month is up by more than 10% compared to last month. Consider reviewing your average monthly spending on the Summary tab.</p>
                                </div>
                            </div>
                            <button
                                onClick={() => setShowSpendingAlert(false)}
                                className="p-2 text-amber-500 hover:bg-amber-100 dark:hover:bg-amber-800 rounded-lg transition-colors flex-shrink-0"
                                aria-label="Dismiss alert"
                            >
                                <X size={16} />
                            </button>
                        </div>
                    </motion.div>
                )}
            </AnimatePresence>

            <StatsGrid
                balance={balance}
                totalIncome={totalIncome}
                totalExpenses={totalExpenses}
            />

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 md:gap-8">
                {/* Chart Section */}
                <section className="p-6 md:p-8 premium-card">
                    <div className="flex items-center justify-between mb-6 md:mb-8">
                        <h3 className="text-base md:text-lg font-semibold flex items-center gap-2 dark:text-zinc-100">
                            <PieChartIcon size={20} className="text-indigo-600" />
                            {chartType === 'expense' ? 'Spending by Category' : 'Income by Source'}
                        </h3>
                        <div className="flex bg-zinc-100 dark:bg-zinc-800/50 p-1 rounded-lg">
                            <button
                                onClick={() => setChartType('expense')}
                                className={cn(
                                    "px-3 py-1 text-xs font-medium rounded-md transition-all",
                                    chartType === 'expense' ? "bg-white dark:bg-zinc-900 shadow-sm text-zinc-900 dark:text-zinc-100" : "text-zinc-500 dark:text-zinc-400 hover:text-zinc-700 dark:text-zinc-300"
                                )}
                            >
                                Expenses
                            </button>
                            <button
                                onClick={() => setChartType('income')}
                                className={cn(
                                    "px-3 py-1 text-xs font-medium rounded-md transition-all",
                                    chartType === 'income' ? "bg-white dark:bg-zinc-900 shadow-sm text-zinc-900 dark:text-zinc-100" : "text-zinc-500 dark:text-zinc-400 hover:text-zinc-700 dark:text-zinc-300"
                                )}
                            >
                                Income
                            </button>
                        </div>
                    </div>
                    <div className="h-[250px] md:h-[300px] w-full">
                        {chartData.length > 0 ? (
                            <ResponsiveContainer width="100%" height="100%">
                                <PieChart>
                                    <Pie
                                        activeIndex={dashboardPieIndex}
                                        activeShape={renderActiveShape}
                                        data={chartData}
                                        cx="50%"
                                        cy="50%"
                                        innerRadius={65}
                                        outerRadius={90}
                                        paddingAngle={6}
                                        dataKey="value"
                                        stroke="none"
                                        onMouseEnter={(_, index) => setDashboardPieIndex(index)}
                                    >
                                        {chartData.map((entry, index) => (
                                            <Cell
                                                key={`cell-${index}`}
                                                fill={categoryColorMap[entry.name] || '#6366f1'}
                                                className="transition-all duration-300 hover:opacity-80"
                                            />
                                        ))}
                                    </Pie>
                                    <Tooltip content={<CustomPieTooltip />} />
                                </PieChart>
                            </ResponsiveContainer>
                        ) : (
                            <div className="h-full flex flex-col items-center justify-center text-zinc-400 gap-3">
                                <div className="p-4 bg-zinc-50 dark:bg-zinc-800/50 rounded-full">
                                    <PieChartIcon size={32} />
                                </div>
                                <p className="text-sm">No {chartType} data yet</p>
                            </div>
                        )}
                    </div>
                    {chartData.length > 0 && (
                        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 md:gap-4 mt-4 md:mt-6">
                            {chartData.slice(0, 6).map((item, index) => (
                                <div key={index} className="flex items-center gap-2">
                                    <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: categoryColorMap[item.name] || '#6366f1' }} />
                                    <span className="text-xs text-zinc-600 dark:text-zinc-400 truncate">{item.name}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </section>

                {/* Recent Transactions */}
                <section className="p-6 md:p-8 premium-card">
                    <div className="flex items-center justify-between mb-6 md:mb-8">
                        <h3 className="text-base md:text-lg font-semibold dark:text-zinc-100">Recent Activity</h3>
                        <button
                            onClick={() => setActiveTab('history')}
                            className="text-xs font-bold text-indigo-600 dark:text-indigo-400 hover:text-indigo-700 uppercase tracking-wider"
                        >
                            See All
                        </button>
                    </div>
                    <div className="space-y-4 md:space-y-5">
                        {recentTransactions.length > 0 ? (
                            recentTransactions.map((tx) => (
                                <div key={tx.id} className="flex items-center justify-between group">
                                    <div className="flex items-center gap-3 md:gap-4">
                                        <div className={cn(
                                            "p-2 md:p-3 rounded-2xl transition-all group-hover:scale-110",
                                            tx.type === 'income' ? "bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600" : "bg-rose-50 dark:bg-rose-900/20 text-rose-600"
                                        )}>
                                            {tx.type === 'income' ? <TrendingUp size={20} /> : <TrendingUp size={20} className="rotate-180" />}
                                        </div>
                                        <div>
                                            <h4 className="font-bold text-zinc-900 dark:text-zinc-100 text-sm md:text-base leading-tight">{tx.description}</h4>
                                            <p className="text-xs text-zinc-400 dark:text-zinc-500 font-medium">{tx.category} • {format(new Date(tx.date), 'MMM d, yyyy')}</p>
                                        </div>
                                    </div>
                                    <div className="text-right">
                                        <p className={cn(
                                            "font-extrabold text-sm md:text-base",
                                            tx.type === 'income' ? "text-emerald-600 dark:text-emerald-400" : "text-zinc-900 dark:text-zinc-100"
                                        )}>
                                            {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                                        </p>
                                    </div>
                                </div>
                            ))
                        ) : (
                            <div className="py-12 flex flex-col items-center justify-center text-zinc-400 gap-3">
                                <Search size={32} />
                                <p className="text-sm">Start by adding your first transaction!</p>
                            </div>
                        )}
                    </div>
                    <div className="flex flex-col sm:flex-row gap-3 mt-8 md:mt-10 pt-6 md:pt-8 border-t border-zinc-100 dark:border-zinc-800">
                        <button
                            onClick={() => setShowAddModal(true)}
                            className="flex-1 flex items-center justify-center gap-2 py-3 md:py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-100 dark:shadow-none hover:bg-indigo-700 transition-all active:scale-[0.98]"
                        >
                            <Plus size={20} />
                            Manual Add
                        </button>
                        <button
                            onClick={() => setShowSMSModal(true)}
                            className="flex-1 flex items-center justify-center gap-2 py-3 md:py-4 bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 rounded-2xl font-bold hover:bg-zinc-800 dark:hover:bg-zinc-200 transition-all active:scale-[0.98]"
                        >
                            <Sparkles size={18} />
                            AI import
                        </button>
                    </div>
                </section>
            </div>
        </div>
    );
}
