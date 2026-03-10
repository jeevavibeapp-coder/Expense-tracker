import React from 'react';
import { motion } from 'motion/react';
import { Tag, Plus, Trash2 } from 'lucide-react';
import { Category } from '../types';

interface CategoriesTabProps {
    categories: Category[];
    setShowAddCategoryModal: (show: boolean) => void;
    handleDeleteCategory: (id: number) => void;
}

export function CategoriesTab({
    categories,
    setShowAddCategoryModal,
    handleDeleteCategory
}: CategoriesTabProps) {
    return (
        <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="space-y-8"
        >
            <div className="flex items-center justify-between">
                <div>
                    <h3 className="text-2xl font-black dark:text-zinc-100">Categories</h3>
                    <p className="text-zinc-500 font-medium">Customize your transaction labels.</p>
                </div>
                <button
                    onClick={() => setShowAddCategoryModal(true)}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl font-bold shadow-lg shadow-indigo-100 dark:shadow-none hover:bg-indigo-700 transition-all"
                >
                    <Plus size={18} />
                    New Category
                </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
                {['expense', 'income'].map((type) => (
                    <section key={type} className="p-6 md:p-8 premium-card">
                        <h4 className="text-lg font-bold mb-6 flex items-center gap-2 capitalize dark:text-zinc-100">
                            <Tag className={type === 'income' ? 'text-emerald-600' : 'text-rose-600'} size={24} />
                            {type} Categories
                        </h4>
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                            {categories.filter(c => c.type === type).map((cat) => (
                                <div
                                    key={cat.id}
                                    className="flex items-center justify-between p-3 rounded-2xl bg-zinc-50 dark:bg-zinc-800/50 border border-zinc-100 dark:border-zinc-800 group"
                                >
                                    <div className="flex items-center gap-3">
                                        <div className="w-4 h-4 rounded-full" style={{ backgroundColor: cat.color }} />
                                        <span className="font-bold text-zinc-900 dark:text-zinc-100 text-sm">{cat.name}</span>
                                    </div>
                                    <button
                                        onClick={() => handleDeleteCategory(cat.id)}
                                        className="p-2 text-zinc-300 hover:text-rose-600 opacity-0 group-hover:opacity-100 transition-all"
                                    >
                                        <Trash2 size={16} />
                                    </button>
                                </div>
                            ))}
                        </div>
                    </section>
                ))}
            </div>
        </motion.div>
    );
}
