export interface Transaction {
    id: number;
    description: string;
    amount: number;
    category: string;
    type: 'income' | 'expense';
    date: string;
}

export interface Budget {
    category: string;
    amount: number;
}

export interface Category {
    id: number;
    name: string;
    type: 'income' | 'expense';
    icon: string;
    color: string;
}
