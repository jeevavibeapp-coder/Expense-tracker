import React from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { X, Sparkles } from 'lucide-react';

interface SMSModalProps {
    isOpen: boolean;
    onClose: () => void;
    smsText: string;
    setSmsText: (text: string) => void;
    isParsing: boolean;
    onParse: () => void;
}

export function SMSModal({
    isOpen,
    onClose,
    smsText,
    setSmsText,
    isParsing,
    onParse
}: SMSModalProps) {
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
                                <Sparkles className="text-indigo-600" size={24} />
                                Import from SMS
                            </h3>
                            <button
                                onClick={onClose}
                                className="p-2 hover:bg-zinc-100 dark:hover:bg-zinc-800 rounded-xl transition-colors dark:text-zinc-400"
                            >
                                <X size={20} />
                            </button>
                        </div>

                        <div className="p-6 space-y-4">
                            <p className="text-sm text-zinc-500 dark:text-zinc-400">
                                Paste your bank transaction SMS here. Our AI will automatically extract the details for you.
                            </p>
                            <textarea
                                value={smsText}
                                onChange={(e) => setSmsText(e.target.value)}
                                placeholder="Example: Rs.350 spent at SWIGGY using HDFC card..."
                                className="w-full h-32 p-4 bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all dark:text-zinc-100 resize-none"
                            />
                            <button
                                onClick={onParse}
                                disabled={isParsing || !smsText.trim()}
                                className="w-full py-4 bg-indigo-600 disabled:bg-zinc-300 dark:disabled:bg-zinc-800 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 dark:shadow-none hover:bg-indigo-700 transition-all active:scale-[0.98] flex items-center justify-center gap-2"
                            >
                                {isParsing ? (
                                    <>
                                        <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                                        Parsing...
                                    </>
                                ) : (
                                    <>
                                        <Sparkles size={20} />
                                        Extract Details
                                    </>
                                )}
                            </button>
                        </div>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
