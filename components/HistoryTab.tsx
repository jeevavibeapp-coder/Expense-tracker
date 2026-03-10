import React from 'react';
import { motion } from 'motion/react';
import { Search, Filter, Calendar, ArrowUpDown, Pencil, Trash2, Download } from 'lucide-react';
import { format } from 'date-fns/format';
import { cn, formatCurrency } from '../utils';
import { Transaction, Category } from '../types';

interface HistoryTabProps {
    transactions: Transaction[];
    filteredTransactions: Transaction[];
    searchQuery: string;
    setSearchQuery: (query: string) => void;
    filterCategory: string;
    setFilterCategory: (category: string) => void;
    dateFilter: { start: string; end: string };
    setDateFilter: React.Dispatch<React.SetStateAction<{ start: string; end: string }>>;
    sortField: 'date' | 'amount' | 'category';
    setSortField: (field: 'date' | 'amount' | 'category') => void;
    sortOrder: 'asc' | 'desc';
    setSortOrder: (order: 'asc' | 'desc') => void;
    categories: Category[];
    exportCSV: () => void;
    startEditTransaction: (tx: Transaction) => void;
    deleteTransaction: (id: number) => void;
}

export function HistoryTab({
    transactions,
    filteredTransactions,
    searchQuery,
    setSearchQuery,
    filterCategory,
    setFilterCategory,
    dateFilter,
    setDateFilter,
    sortField,
    setSortField,
    sortOrder,
    setSortOrder,
    categories,
    exportCSV,
    startEditTransaction,
    deleteTransaction
}: HistoryTabProps) {
    return (
        <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="space-y-6 md:space-y-8"
        >
            <div className="p-6 md:p-8 premium-card">
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-6 md:mb-8">
                    <h3 className="text-xl font-bold dark:text-zinc-100">Transaction History</h3>
                    <div className="flex flex-wrap items-center gap-2 md:gap-3">
                        <div className="relative flex-1 md:flex-initial">
                            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-400" size={18} />
                            <input
                                type="text"
                                placeholder="Search transactions..."
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                className="w-full md:w-64 pl-10 pr-4 py-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/20 dark:text-zinc-100"
                            />
                        </div>

                        <div className="flex gap-2">
                            <button
                                onClick={exportCSV}
                                className="flex items-center gap-2 px-3 py-2 bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400 border border-emerald-200 dark:border-emerald-800 rounded-xl text-sm font-medium hover:bg-emerald-100 dark:hover:bg-emerald-900/30 transition-colors"
                                title="Export to CSV"
                            >
                                <Download size={16} />
                                <span className="hidden sm:inline">Export</span>
                            </button>

                            <div className="relative group/filter">
                                <button className="flex items-center gap-2 px-3 py-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl text-sm font-medium text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors">
                                    <Filter size={16} />
                                    <span className="hidden sm:inline">Category</span>
                                </button>
                                <div className="absolute right-0 mt-2 w-48 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-lg opacity-0 invisible group-hover/filter:opacity-100 group-hover/filter:visible transition-all z-10">
                                    <div className="p-2 space-y-1">
                                        <button
                                            onClick={() => setFilterCategory('all')}
                                            className={cn(
                                                "w-full text-left px-3 py-2 rounded-lg text-sm transition-colors",
                                                filterCategory === 'all' ? "bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400" : "text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-800"
                                            )}
                                        >
                                            All Categories
                                        </button>
                                        {categories.map(cat => (
                                            <button
                                                key={cat.id}
                                                onClick={() => setFilterCategory(cat.name)}
                                                className={cn(
                                                    "w-full text-left px-3 py-2 rounded-lg text-sm transition-colors",
                                                    filterCategory === cat.name ? "bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400" : "text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-800"
                                                )}
                                            >
                                                {cat.name}
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            </div>

                            <div className="relative group/calendar">
                                <button className="flex items-center gap-2 px-3 py-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl text-sm font-medium text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors">
                                    <Calendar size={16} />
                                    <span className="hidden sm:inline">Date Range</span>
                                </button>
                                <div className="absolute right-0 mt-2 w-64 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-lg opacity-0 invisible group-hover/calendar:opacity-100 group-hover/calendar:visible transition-all z-10">
                                    <div className="p-3 space-y-3">
                                        <div>
                                            <label className="text-xs font-semibold text-zinc-400 dark:text-zinc-500 uppercase">Start Date</label>
                                            <input
                                                type="date"
                                                value={dateFilter.start}
                                                onChange={(e) => setDateFilter(prev => ({ ...prev, start: e.target.value }))}
                                                className="w-full mt-1 px-3 py-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/20 dark:text-zinc-100"
                                            />
                                        </div>
                                        <div>
                                            <label className="text-xs font-semibold text-zinc-400 dark:text-zinc-500 uppercase">End Date</label>
                                            <input
                                                type="date"
                                                value={dateFilter.end}
                                                onChange={(e) => setDateFilter(prev => ({ ...prev, end: e.target.value }))}
                                                className="w-full mt-1 px-3 py-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/20 dark:text-zinc-100"
                                            />
                                        </div>
                                        <div className="pt-2 border-t border-zinc-100 dark:border-zinc-800">
                                            <button
                                                onClick={() => setDateFilter({ start: '', end: '' })}
                                                className="w-full py-1.5 text-xs font-medium text-rose-600 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-900/20 rounded-lg transition-colors"
                                            >
                                                Clear Range
                                            </button>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <div className="relative group/sort">
                                <button className="flex items-center gap-2 px-3 py-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl text-sm font-medium text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors">
                                    <ArrowUpDown size={16} />
                                    <span className="hidden sm:inline">Sort</span>
                                </button>
                                <div className="absolute right-0 mt-2 w-48 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-lg opacity-0 invisible group-hover/sort:opacity-100 group-hover/sort:visible transition-all z-10">
                                    <div className="p-2 space-y-1">
                                        {[
                                            { label: 'Date', field: 'date' },
                                            { label: 'Amount', field: 'amount' },
                                            { label: 'Category', field: 'category' }
                                        ].map(({ label, field }) => (
                                            <div key={field} className="flex flex-col">
                                                <span className="px-3 py-1 text-[10px] font-bold text-zinc-400 uppercase tracking-wider">{label}</span>
                                                <div className="flex gap-1 p-1">
                                                    <button
                                                        onClick={() => { setSortField(field as any); setSortOrder('asc'); }}
                                                        className={cn(
                                                            "flex-1 py-1 px-2 rounded-md text-xs font-medium transition-colors",
                                                            sortField === field && sortOrder === 'asc' ? "bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400" : "text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-800"
                                                        )}
                                                    >
                                                        Asc
                                                    </button>
                                                    <button
                                                        onClick={() => { setSortField(field as any); setSortOrder('desc'); }}
                                                        className={cn(
                                                            "flex-1 py-1 px-2 rounded-md text-xs font-medium transition-colors",
                                                            sortField === field && sortOrder === 'desc' ? "bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400" : "text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-800"
                                                        )}
                                                    >
                                                        Desc
                                                    </button>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                {/* Desktop Table View */}
                <div className="hidden md:block overflow-x-auto">
                    <table className="w-full text-left">
                        <thead>
                            <tr className="border-b border-zinc-100 dark:border-zinc-800">
                                <th className="py-4 px-4 text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">Date</th>
                                <th className="py-4 px-4 text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">Description</th>
                                <th className="py-4 px-4 text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">Category</th>
                                <th className="py-4 px-4 text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">Type</th>
                                <th className="py-4 px-4 text-right text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">Amount</th>
                                <th className="py-4 px-4 text-right text-xs font-bold text-zinc-400 dark:text-zinc-500 uppercase tracking-wider">Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredTransactions.length > 0 ? (
                                filteredTransactions.map((tx) => (
                                    <tr key={tx.id} className="border-b border-zinc-50 dark:border-zinc-800/50 hover:bg-zinc-50/50 dark:hover:bg-zinc-800/20 transition-colors group">
                                        <td className="py-4 px-4 text-sm text-zinc-600 dark:text-zinc-400">
                                            {format(new Date(tx.date), 'MMM d, yyyy')}
                                        </td>
                                        <td className="py-4 px-4">
                                            <span className="font-semibold text-zinc-900 dark:text-zinc-100">{tx.description}</span>
                                        </td>
                                        <td className="py-4 px-4">
                                            <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-zinc-100 dark:bg-zinc-800 text-zinc-800 dark:text-zinc-200">
                                                {tx.category}
                                            </span>
                                        </td>
                                        <td className="py-4 px-4">
                                            <span className={cn(
                                                "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium",
                                                tx.type === 'income' ? "bg-emerald-100 dark:bg-emerald-900/40 text-emerald-800 dark:text-emerald-400" : "bg-rose-100 dark:bg-rose-900/40 text-rose-800 dark:text-rose-400"
                                            )}>
                                                {tx.type}
                                            </span>
                                        </td>
                                        <td className={cn(
                                            "py-4 px-4 text-right font-bold",
                                            tx.type === 'income' ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"
                                        )}>
                                            {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                                        </td>
                                        <td className="py-4 px-4 text-right">
                                            <div className="flex items-center justify-end gap-1">
                                                <button
                                                    onClick={() => startEditTransaction(tx)}
                                                    className="p-2 text-zinc-400 hover:text-indigo-600 transition-colors"
                                                    title="Edit transaction"
                                                >
                                                    <Pencil size={16} />
                                                </button>
                                                <button
                                                    onClick={() => deleteTransaction(tx.id)}
                                                    className="p-2 text-zinc-400 hover:text-rose-600 transition-colors"
                                                    title="Delete transaction"
                                                >
                                                    <Trash2 size={18} />
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                ))
                            ) : (
                                <tr>
                                    <td colSpan={6} className="py-8 text-center text-zinc-500 dark:text-zinc-500">
                                        No transactions found{searchQuery || filterCategory !== 'all' ? ' matching your filters' : ' yet. Add one to get started!'}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>

                {/* Mobile List View */}
                <div className="md:hidden space-y-4">
                    {filteredTransactions.length > 0 ? (
                        filteredTransactions.map((tx) => (
                            <div key={tx.id} className="p-4 rounded-2xl bg-zinc-50 dark:bg-zinc-800/50 border border-zinc-100 dark:border-zinc-800 flex flex-col gap-3">
                                <div className="flex justify-between items-start">
                                    <div>
                                        <h4 className="font-bold text-zinc-900 dark:text-zinc-100">{tx.description}</h4>
                                        <p className="text-xs text-zinc-500 dark:text-zinc-500">{format(new Date(tx.date), 'MMM d, yyyy')}</p>
                                    </div>
                                    <div className="flex flex-col items-end gap-1">
                                        <span className={cn(
                                            "font-bold text-lg",
                                            tx.type === 'income' ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"
                                        )}>
                                            {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                                        </span>
                                        <div className="flex items-center gap-2">
                                            <button
                                                onClick={() => startEditTransaction(tx)}
                                                className="text-zinc-400 hover:text-indigo-600 transition-colors"
                                            >
                                                <Pencil size={14} />
                                            </button>
                                            <button
                                                onClick={() => deleteTransaction(tx.id)}
                                                className="text-zinc-400 hover:text-rose-600 transition-colors"
                                            >
                                                <Trash2 size={16} />
                                            </button>
                                        </div>
                                    </div>
                                </div>
                                <div className="flex gap-2">
                                    <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-medium bg-zinc-200 dark:bg-zinc-800 text-zinc-800 dark:text-zinc-300">
                                        {tx.category}
                                    </span>
                                    <span className={cn(
                                        "inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-medium",
                                        tx.type === 'income' ? "bg-emerald-100 dark:bg-emerald-900/40 text-emerald-800 dark:text-emerald-400" : "bg-rose-100 dark:bg-rose-900/40 text-rose-800 dark:text-rose-400"
                                    )}>
                                        {tx.type}
                                    </span>
                                </div>
                            </div>
                        ))
                    ) : (
                        <div className="py-8 text-center text-zinc-500 dark:text-zinc-500">
                            No transactions found{searchQuery || filterCategory !== 'all' ? ' matching your filters' : ' yet. Add one to get started!'}
                        </div>
                    )}
                </div>
            </div>
        </motion.div>
    );
}
