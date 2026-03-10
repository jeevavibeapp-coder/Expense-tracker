import React from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { X } from 'lucide-react';
import { cn } from '../utils';
import { Category, Transaction } from '../types';

interface TransactionModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSubmit: (e: React.FormEvent) => void;
    editingTransaction: Transaction | null;
    formData: {
        description: string;
        amount: string;
        category: string;
        type: 'income' | 'expense';
        date: string;
        isRecurring: boolean;
        frequency: 'daily' | 'weekly' | 'monthly' | 'yearly';
    };
    setFormData: (data: any) => void;
    categories: Category[];
}

export function TransactionModal({
    isOpen,
    onClose,
    onSubmit,
    editingTransaction,
    formData,
    setFormData,
    categories
}: TransactionModalProps) {
    return (
        <AnimatePresence>
            {isOpen && (
                <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
                    <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        onClick={onClose}
                        className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm"
                    />
                    <motion.div
                        initial={{ opacity: 0, scale: 0.95, y: 20 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.95, y: 20 }}
                        className="relative w-full max-w-md bg-white dark:bg-zinc-900 rounded-3xl shadow-2xl overflow-hidden border border-zinc-200 dark:border-zinc-800"
                    >
                        <div className="p-6 border-b border-zinc-100 dark:border-zinc-800 flex items-center justify-between">
                            <h3 className="text-xl font-bold dark:text-zinc-100">
                                {editingTransaction ? 'Edit Transaction' : 'Add Transaction'}
                            </h3>
                            <button
                                onClick={onClose}
                                className="p-2 hover:bg-zinc-100 dark:hover:bg-zinc-800 rounded-xl transition-colors dark:text-zinc-400"
                            >
                                <X size={20} />
                            </button>
                        </div>

                        <form onSubmit={onSubmit} className="p-6 space-y-4">
                            <div className="flex p-1 bg-zinc-100 dark:bg-zinc-800/50 rounded-xl">
                                <button
                                    type="button"
                                    onClick={() => {
                                        const firstExpenseCat = categories.find(c => c.type === 'expense')?.name || '';
                                        setFormData({ ...formData, type: 'expense', category: firstExpenseCat });
                                    }}
                                    className={cn(
                                        "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                                        formData.type === 'expense' ? "bg-white dark:bg-zinc-900 shadow-sm text-rose-600" : "text-zinc-500 dark:text-zinc-400"
                                    )}
                                >
                                    Expense
                                </button>
                                <button
                                    type="button"
                                    onClick={() => {
                                        const firstIncomeCat = categories.find(c => c.type === 'income')?.name || '';
                                        setFormData({ ...formData, type: 'income', category: firstIncomeCat });
                                    }}
                                    className={cn(
                                        "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                                        formData.type === 'income' ? "bg-white dark:bg-zinc-900 shadow-sm text-emerald-600" : "text-zinc-500 dark:text-zinc-400"
                                    )}
                                >
                                    Income
                                </button>
                            </div>

                            <div className="space-y-1">
                                <label className="text-xs font-semibold text-zinc-400 dark:text-zinc-500 uppercase ml-1">Description</label>
                                <input
                                    type="text"
                                    placeholder="e.g. Weekly Grocery"
                                    value={formData.description}
                                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                                    className="w-full p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all dark:text-zinc-100"
                                />
                            </div>

                            <div className="grid grid-cols-2 gap-4">
                                <div className="space-y-1">
                                    <label className="text-xs font-semibold text-zinc-400 dark:text-zinc-500 uppercase ml-1">Amount</label>
                                    <input
                                        type="number"
                                        step="0.01"
                                        placeholder="0.00"
                                        value={formData.amount}
                                        onChange={(e) => setFormData({ ...formData, amount: e.target.value })}
                                        className="w-full p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all dark:text-zinc-100"
                                    />
                                </div>
                                <div className="space-y-1">
                                    <label className="text-xs font-semibold text-zinc-400 dark:text-zinc-500 uppercase ml-1">Date</label>
                                    <input
                                        type="date"
                                        value={formData.date}
                                        onChange={(e) => setFormData({ ...formData, date: e.target.value })}
                                        className="w-full p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all dark:text-zinc-100"
                                    />
                                </div>
                            </div>

                            <div className="space-y-1">
                                <label className="text-xs font-semibold text-zinc-400 dark:text-zinc-500 uppercase ml-1">Category</label>
                                <select
                                    value={formData.category}
                                    onChange={(e) => setFormData({ ...formData, category: e.target.value })}
                                    className="w-full p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all appearance-none dark:text-zinc-100"
                                >
                                    {categories.filter(c => c.type === formData.type).map((cat) => (
                                        <option key={cat.id} value={cat.name}>{cat.name}</option>
                                    ))}
                                </select>
                            </div>

                            {!editingTransaction && (
                                <div className="flex flex-col sm:flex-row gap-4 pt-2">
                                    <label className="flex items-center gap-2 cursor-pointer">
                                        <input
                                            type="checkbox"
                                            checked={formData.isRecurring}
                                            onChange={(e) => setFormData({ ...formData, isRecurring: e.target.checked })}
                                            className="w-4 h-4 text-indigo-600 rounded focus:ring-indigo-500 dark:bg-zinc-950 dark:border-zinc-800"
                                        />
                                        <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">Make Recurring</span>
                                    </label>
                                    {formData.isRecurring && (
                                        <select
                                            value={formData.frequency}
                                            onChange={(e) => setFormData({ ...formData, frequency: e.target.value as any })}
                                            className="flex-1 p-2 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all appearance-none text-sm dark:text-zinc-100"
                                        >
                                            <option value="daily">Daily</option>
                                            <option value="weekly">Weekly</option>
                                            <option value="monthly">Monthly</option>
                                            <option value="yearly">Yearly</option>
                                        </select>
                                    )}
                                </div>
                            )}

                            <button
                                type="submit"
                                className="w-full py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 dark:shadow-none hover:bg-indigo-700 transition-all active:scale-[0.98] mt-4"
                            >
                                {editingTransaction ? 'Update Transaction' : 'Save Transaction'}
                            </button>
                        </form>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
