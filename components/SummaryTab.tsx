import React from 'react';
import { motion } from 'motion/react';
import { Activity, TrendingUp, Target, Wallet } from 'lucide-react';
import { cn, formatCurrency } from '../utils';
import { Category, Transaction } from '../types';
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Cell, PieChart, Pie } from 'recharts';

interface SummaryTabProps {
    monthlyIncome: number;
    monthlyExpenses: number;
    monthlySavings: number;
    savingsRate: number;
    budgetAdherence: number;
    averageMonthlySpending: number;
    topCategory: { category: string; average: number } | undefined;
    categoryAverages: { category: string; average: number }[];
    categoryColorMap: Record<string, string>;
    barChartData: any[];
    CustomBarTooltip: any;
    CustomAvgTooltip: any;
}

export function SummaryTab({
    monthlyIncome,
    monthlyExpenses,
    monthlySavings,
    savingsRate,
    budgetAdherence,
    averageMonthlySpending,
    topCategory,
    categoryAverages,
    categoryColorMap,
    barChartData,
    CustomBarTooltip,
    CustomAvgTooltip
}: SummaryTabProps) {
    return (
        <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="space-y-8"
        >
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                {[
                    { label: 'Monthly Income', value: monthlyIncome, icon: <TrendingUp size={20} />, color: 'emerald' },
                    { label: 'Monthly Spent', value: monthlyExpenses, icon: <TrendingUp size={20} className="rotate-180" />, color: 'rose' },
                    { label: 'Monthly Savings', value: monthlySavings, icon: <Wallet size={20} />, color: 'indigo' },
                    { label: 'Savings Rate', value: `${savingsRate.toFixed(1)}%`, icon: <Activity size={20} />, color: 'amber' }
                ].map((stat, i) => (
                    <div key={i} className="p-5 premium-card">
                        <div className={`p-2 w-fit rounded-xl bg-${stat.color}-50 dark:bg-${stat.color}-900/20 text-${stat.color}-600 mb-3`}>
                            {stat.icon}
                        </div>
                        <p className="text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">{stat.label}</p>
                        <h4 className="text-xl font-black text-zinc-900 dark:text-zinc-100 mt-1">
                            {typeof stat.value === 'number' ? formatCurrency(stat.value) : stat.value}
                        </h4>
                    </div>
                ))}
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
                <section className="p-6 md:p-8 premium-card">
                    <div className="flex items-center justify-between mb-8">
                        <h3 className="text-lg font-bold dark:text-zinc-100 flex items-center gap-2">
                            <Activity className="text-indigo-600" size={24} />
                            Income vs Expenses
                        </h3>
                    </div>
                    <div className="h-[300px] w-full">
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={barChartData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" opacity={0.5} />
                                <XAxis
                                    dataKey="name"
                                    axisLine={false}
                                    tickLine={false}
                                    tick={{ fill: '#94a3b8', fontSize: 12, fontWeight: 600 }}
                                    dy={10}
                                />
                                <YAxis axisLine={false} tickLine={false} tick={{ fill: '#94a3b8', fontSize: 10 }} />
                                <Tooltip content={<CustomBarTooltip />} cursor={{ fill: '#f8fafc', opacity: 0.5 }} />
                                <Bar dataKey="income" name="Income" fill="#10b981" radius={[6, 6, 0, 0]} barSize={24} />
                                <Bar dataKey="expense" name="Expense" fill="#f43f5e" radius={[6, 6, 0, 0]} barSize={24} />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>
                </section>

                <section className="p-6 md:p-8 premium-card">
                    <h3 className="text-lg font-bold mb-8 dark:text-zinc-100 flex items-center gap-2">
                        <Target className="text-indigo-600" size={24} />
                        Efficiency & Insights
                    </h3>
                    <div className="space-y-8">
                        <div className="space-y-3">
                            <div className="flex justify-between items-end">
                                <span className="text-sm font-bold text-zinc-600 dark:text-zinc-400">Budget Adherence</span>
                                <span className="text-lg font-black text-emerald-600">{Math.round(budgetAdherence)}%</span>
                            </div>
                            <div className="h-4 w-full bg-zinc-100 dark:bg-zinc-800/50 rounded-full overflow-hidden p-1">
                                <motion.div
                                    initial={{ width: 0 }}
                                    animate={{ width: `${budgetAdherence}%` }}
                                    className="h-full bg-emerald-500 rounded-full"
                                />
                            </div>
                            <p className="text-xs text-zinc-400 font-medium">You've kept {Math.round(budgetAdherence)}% of your allowed budget this month.</p>
                        </div>

                        <div className="grid grid-cols-2 gap-6 pt-4 border-t border-zinc-100 dark:border-zinc-800">
                            <div>
                                <p className="text-[10px] font-bold text-zinc-400 uppercase tracking-widest mb-1">Avg. Monthly Spend</p>
                                <h5 className="text-2xl font-black text-zinc-900 dark:text-zinc-100">{formatCurrency(averageMonthlySpending)}</h5>
                            </div>
                            <div>
                                <p className="text-[10px] font-bold text-zinc-400 uppercase tracking-widest mb-1">Top Category (Avg)</p>
                                <h5 className="text-2xl font-black text-indigo-600">{topCategory?.category || 'None'}</h5>
                                <p className="text-xs font-bold text-zinc-400 mt-1">{formatCurrency(topCategory?.average || 0)} / mo</p>
                            </div>
                        </div>

                        <div className="pt-6">
                            <h4 className="text-xs font-bold text-zinc-400 uppercase tracking-widest mb-4">Historical Category Averages</h4>
                            <div className="h-[120px] w-full">
                                <ResponsiveContainer width="100%" height="100%">
                                    <BarChart data={categoryAverages}>
                                        <Tooltip content={<CustomAvgTooltip />} cursor={{ fill: '#f8fafc', opacity: 0.5 }} />
                                        <Bar dataKey="average" radius={[4, 4, 0, 0]}>
                                            {categoryAverages.map((entry, index) => (
                                                <Cell key={index} fill={categoryColorMap[entry.category] || '#6366f1'} />
                                            ))}
                                        </Bar>
                                    </BarChart>
                                </ResponsiveContainer>
                            </div>
                        </div>
                    </div>
                </section>
            </div>
        </motion.div>
    );
}
