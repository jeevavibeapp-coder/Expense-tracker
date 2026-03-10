import React from 'react';
import { motion } from 'motion/react';
import { BarChart3, PieChart as PieChartIcon } from 'lucide-react';
import { cn, formatCurrency } from '../utils';
import { ResponsiveContainer, PieChart, Pie, Cell, Tooltip, BarChart, Bar, XAxis, YAxis, CartesianGrid } from 'recharts';

interface ReportsTabProps {
    reportPeriod: 'weekly' | 'monthly' | 'yearly';
    setReportPeriod: (period: 'weekly' | 'monthly' | 'yearly') => void;
    periodLabel: string;
    reportTotalSpending: number;
    reportCategoryData: any[];
    reportTrendData: any[];
    reportPieIndex: number;
    setReportPieIndex: (index: number) => void;
    renderActiveShape: any;
    CustomReportPieTooltip: any;
    CustomReportBarTooltip: any;
}

export function ReportsTab({
    reportPeriod,
    setReportPeriod,
    periodLabel,
    reportTotalSpending,
    reportCategoryData,
    reportTrendData,
    reportPieIndex,
    setReportPieIndex,
    renderActiveShape,
    CustomReportPieTooltip,
    CustomReportBarTooltip
}: ReportsTabProps) {
    return (
        <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="space-y-8"
        >
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                    <h3 className="text-2xl font-black dark:text-zinc-100">{periodLabel} Report</h3>
                    <p className="text-zinc-500 dark:text-zinc-500 font-medium">Detailed breakdown of your spending habits.</p>
                </div>
                <div className="flex bg-zinc-100 dark:bg-zinc-800/50 p-1 rounded-xl w-fit">
                    {['weekly', 'monthly', 'yearly'].map((period) => (
                        <button
                            key={period}
                            onClick={() => setReportPeriod(period as any)}
                            className={cn(
                                "px-4 py-2 text-xs font-bold rounded-lg transition-all capitalize",
                                reportPeriod === period
                                    ? "bg-white dark:bg-zinc-900 shadow-sm text-indigo-600"
                                    : "text-zinc-500 dark:text-zinc-400 hover:text-zinc-700"
                            )}
                        >
                            {period}
                        </button>
                    ))}
                </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
                <section className="lg:col-span-1 p-6 md:p-8 premium-card flex flex-col items-center">
                    <div className="w-full mb-8">
                        <h4 className="text-xs font-black text-zinc-400 uppercase tracking-[0.2em] mb-2">Total Spending</h4>
                        <h2 className="text-4xl font-black text-rose-600">{formatCurrency(reportTotalSpending)}</h2>
                    </div>

                    <div className="h-[280px] w-full">
                        {reportCategoryData.length > 0 ? (
                            <ResponsiveContainer width="100%" height="100%">
                                <PieChart>
                                    <Pie
                                        activeIndex={reportPieIndex}
                                        activeShape={renderActiveShape}
                                        data={reportCategoryData}
                                        cx="50%"
                                        cy="50%"
                                        innerRadius={70}
                                        outerRadius={95}
                                        paddingAngle={4}
                                        dataKey="value"
                                        stroke="none"
                                        onMouseEnter={(_, index) => setReportPieIndex(index)}
                                    >
                                        {reportCategoryData.map((entry, index) => (
                                            <Cell key={`cell-${index}`} fill={entry.color} />
                                        ))}
                                    </Pie>
                                    <Tooltip content={<CustomReportPieTooltip />} />
                                </PieChart>
                            </ResponsiveContainer>
                        ) : (
                            <div className="h-full flex flex-col items-center justify-center text-zinc-400 gap-3">
                                <PieChartIcon size={32} />
                                <p className="text-sm">No expenses in this period</p>
                            </div>
                        )}
                    </div>

                    <div className="w-full space-y-3 mt-8">
                        {reportCategoryData.map((item, index) => (
                            <div key={index} className="flex items-center justify-between">
                                <div className="flex items-center gap-2">
                                    <div className="w-2 h-2 rounded-full" style={{ backgroundColor: item.color }} />
                                    <span className="text-xs font-bold text-zinc-700 dark:text-zinc-300">{item.name}</span>
                                </div>
                                <span className="text-xs font-black text-zinc-900 dark:text-zinc-100">{formatCurrency(item.value)}</span>
                            </div>
                        ))}
                    </div>
                </section>

                <section className="lg:col-span-2 p-6 md:p-8 premium-card">
                    <h4 className="text-lg font-bold mb-8 dark:text-zinc-100 flex items-center gap-2">
                        <BarChart3 className="text-indigo-600" size={24} />
                        Spending Trend
                    </h4>
                    <div className="h-[400px] w-full">
                        {reportTrendData.length > 0 ? (
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={reportTrendData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" opacity={0.3} />
                                    <XAxis
                                        dataKey="name"
                                        axisLine={false}
                                        tickLine={false}
                                        tick={{ fill: '#94a3b8', fontSize: 12, fontWeight: 600 }}
                                        dy={10}
                                    />
                                    <YAxis axisLine={false} tickLine={false} tick={{ fill: '#94a3b8', fontSize: 10 }} />
                                    <Tooltip content={<CustomReportBarTooltip />} cursor={{ fill: '#f8fafc', opacity: 0.5 }} />
                                    <Bar dataKey="value" fill="#6366f1" radius={[8, 8, 0, 0]} barSize={reportPeriod === 'weekly' ? 40 : 25}>
                                        {reportTrendData.map((entry, index) => (
                                            <Cell key={`cell-${index}`} fill={entry.value > reportTotalSpending / reportTrendData.length ? '#ef4444' : '#6366f1'} />
                                        ))}
                                    </Bar>
                                </BarChart>
                            </ResponsiveContainer>
                        ) : (
                            <div className="h-full flex items-center justify-center text-zinc-400">
                                <p>No activity recorded for this period.</p>
                            </div>
                        )}
                    </div>
                </section>
            </div>
        </motion.div>
    );
}
