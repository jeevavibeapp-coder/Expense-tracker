import React from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Tag, X } from 'lucide-react';
import { cn } from '../utils';

interface CategoryModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSubmit: (e: React.FormEvent) => void;
    categoryFormData: {
        name: string;
        type: 'income' | 'expense';
        color: string;
    };
    setCategoryFormData: (data: any) => void;
}

export function CategoryModal({
    isOpen,
    onClose,
    onSubmit,
    categoryFormData,
    setCategoryFormData
}: CategoryModalProps) {
    const colors = [
        '#6366f1', '#f43f5e', '#10b981', '#f59e0b', '#8b5cf6',
        '#06b6d4', '#ec4899', '#64748b', '#22c55e', '#ef4444'
    ];

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
                            <h3 className="text-xl font-bold flex items-center gap-2 dark:text-zinc-100">
                                <Tag className="text-indigo-600" size={24} />
                                Add Category
                            </h3>
                            <button
                                onClick={onClose}
                                className="p-2 hover:bg-zinc-100 dark:hover:bg-zinc-800 rounded-xl transition-colors dark:text-zinc-400"
                            >
                                <X size={20} />
                            </button>
                        </div>

                        <form onSubmit={onSubmit} className="p-6 space-y-6">
                            <div className="flex p-1 bg-zinc-100 dark:bg-zinc-800/50 rounded-xl">
                                <button
                                    type="button"
                                    onClick={() => setCategoryFormData({ ...categoryFormData, type: 'expense' })}
                                    className={cn(
                                        "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                                        categoryFormData.type === 'expense' ? "bg-white dark:bg-zinc-900 shadow-sm text-rose-600" : "text-zinc-500 dark:text-zinc-400"
                                    )}
                                >
                                    Expense
                                </button>
                                <button
                                    type="button"
                                    onClick={() => setCategoryFormData({ ...categoryFormData, type: 'income' })}
                                    className={cn(
                                        "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                                        categoryFormData.type === 'income' ? "bg-white dark:bg-zinc-900 shadow-sm text-emerald-600" : "text-zinc-500 dark:text-zinc-400"
                                    )}
                                >
                                    Income
                                </button>
                            </div>

                            <div className="space-y-1">
                                <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Category Name</label>
                                <input
                                    type="text"
                                    placeholder="e.g. Entertainment"
                                    value={categoryFormData.name}
                                    onChange={(e) => setCategoryFormData({ ...categoryFormData, name: e.target.value })}
                                    className="w-full p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all dark:text-zinc-100"
                                    autoFocus
                                />
                            </div>

                            <div className="space-y-3">
                                <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Theme Color</label>
                                <div className="grid grid-cols-5 gap-3">
                                    {colors.map((color) => (
                                        <button
                                            key={color}
                                            type="button"
                                            onClick={() => setCategoryFormData({ ...categoryFormData, color })}
                                            className={cn(
                                                "w-full aspect-square rounded-xl transition-all border-4",
                                                categoryFormData.color === color ? "border-zinc-200 dark:border-zinc-700 scale-110" : "border-transparent"
                                            )}
                                            style={{ backgroundColor: color }}
                                        />
                                    ))}
                                </div>
                            </div>

                            <button
                                type="submit"
                                className="w-full py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 dark:shadow-none hover:bg-indigo-700 transition-all active:scale-[0.98] mt-4"
                            >
                                Create Category
                            </button>
                        </form>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
