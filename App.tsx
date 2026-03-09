import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { 
  Plus, 
  Minus, 
  TrendingUp, 
  TrendingDown, 
  Wallet, 
  Trash2, 
  Calendar, 
  Tag, 
  ArrowUpRight, 
  ArrowDownLeft,
  PieChart as PieChartIcon,
  LayoutDashboard,
  History,
  X,
  Search,
  BarChart3,
  Target,
  Activity,
  Filter,
  ArrowUpDown
} from 'lucide-react';
import { 
  PieChart, 
  Pie, 
  Cell, 
  ResponsiveContainer, 
  Tooltip, 
  BarChart, 
  Bar, 
  XAxis, 
  YAxis, 
  CartesianGrid,
  Sector,
  AreaChart,
  Area
} from 'recharts';
import { format, subMonths, startOfWeek, endOfWeek, startOfMonth, endOfMonth, startOfYear, endOfYear, isWithinInterval, eachDayOfInterval, eachMonthOfInterval, eachWeekOfInterval, isSameDay, isSameMonth, isSameWeek } from 'date-fns';
import { GoogleGenAI, Type } from "@google/genai";
import { cn, formatCurrency } from './utils';

interface Transaction {
  id: number;
  description: string;
  amount: number;
  category: string;
  type: 'income' | 'expense';
  date: string;
}

interface Budget {
  category: string;
  amount: number;
}

interface Category {
  id: number;
  name: string;
  type: 'income' | 'expense';
  icon?: string;
  color?: string;
}

export default function App() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [showAddCategoryModal, setShowAddCategoryModal] = useState(false);
  const [activeTab, setActiveTab] = useState<'dashboard' | 'history' | 'budgets' | 'summary' | 'reports' | 'categories'>('dashboard');
  const [chartType, setChartType] = useState<'expense' | 'income'>('expense');
  const [searchQuery, setSearchQuery] = useState('');
  
  // SMS Import State
  const [showSMSModal, setShowSMSModal] = useState(false);
  const [smsText, setSmsText] = useState('');
  const [isParsing, setIsParsing] = useState(false);

  // Chart Interaction State
  const [selectedChartItem, setSelectedChartItem] = useState<{ type: 'category' | 'month', value: string } | null>(null);

  // Sorting and Filtering State
  const [sortField, setSortField] = useState<'date' | 'amount' | 'category'>('date');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');
  const [filterCategory, setFilterCategory] = useState<string>('all');

  // Reports State
  const [reportPeriod, setReportPeriod] = useState<'weekly' | 'monthly' | 'yearly'>('monthly');

  // Chart Active Index State
  const [dashboardPieIndex, setDashboardPieIndex] = useState(0);
  const [reportPieIndex, setReportPieIndex] = useState(0);

  // Alert State
  const [showSpendingAlert, setShowSpendingAlert] = useState(true);

  // Form State
  const [formData, setFormData] = useState({
    description: '',
    amount: '',
    category: '',
    type: 'expense' as 'income' | 'expense',
    date: format(new Date(), 'yyyy-MM-dd'),
  });

  const [categoryFormData, setCategoryFormData] = useState({
    name: '',
    type: 'expense' as 'income' | 'expense',
    color: '#6366f1',
  });

  const categoryColorMap = categories.reduce((acc, cat) => {
    acc[cat.name] = cat.color || '#94a3b8';
    return acc;
  }, {} as Record<string, string>);

  useEffect(() => {
    fetchTransactions();
    fetchBudgets();
    fetchCategories();
  }, []);

  const fetchTransactions = async () => {
    try {
      const res = await fetch('/api/transactions');
      const data = await res.json();
      setTransactions(data);
    } catch (error) {
      console.error('Failed to fetch transactions:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchCategories = async () => {
    try {
      const res = await fetch('/api/categories');
      const data = await res.json();
      setCategories(data);
      
      // Update form data with first category if not set
      if (data.length > 0) {
        setFormData(prev => ({
          ...prev,
          category: data.find((c: Category) => c.type === prev.type)?.name || data[0].name
        }));
      }
    } catch (error) {
      console.error('Failed to fetch categories:', error);
    }
  };

  const fetchBudgets = async () => {
    try {
      const res = await fetch('/api/budgets');
      const data = await res.json();
      setBudgets(data);
    } catch (error) {
      console.error('Failed to fetch budgets:', error);
    }
  };

  const handleSetBudget = async (category: string, amount: number) => {
    try {
      const res = await fetch('/api/budgets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ category, amount }),
      });
      if (res.ok) {
        const updatedBudget = await res.json();
        setBudgets(prev => {
          const index = prev.findIndex(b => b.category === category);
          if (index > -1) {
            const newBudgets = [...prev];
            newBudgets[index] = updatedBudget;
            return newBudgets;
          }
          return [...prev, updatedBudget];
        });
      }
    } catch (error) {
      console.error('Failed to set budget:', error);
    }
  };

  const handleDeleteCategory = async (id: number) => {
    if (!confirm('Are you sure you want to delete this category? Transactions using this category will still show the name but won\'t be linked to a dynamic category.')) return;
    try {
      const res = await fetch(`/api/categories/${id}`, { method: 'DELETE' });
      if (res.ok) {
        setCategories(prev => prev.filter(c => c.id !== id));
      }
    } catch (error) {
      console.error('Failed to delete category:', error);
    }
  };

  const handleAddCategory = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const res = await fetch('/api/categories', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(categoryFormData),
      });
      if (res.ok) {
        const newCategory = await res.json();
        setCategories(prev => [...prev, newCategory]);
        setShowAddCategoryModal(false);
        setCategoryFormData({ name: '', type: 'expense', color: '#6366f1' });
      } else {
        const err = await res.json();
        alert(err.error || 'Failed to add category');
      }
    } catch (error) {
      console.error('Failed to add category:', error);
    }
  };

  const handleAddTransaction = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.description || !formData.amount) return;

    try {
      const res = await fetch('/api/transactions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...formData,
          amount: parseFloat(formData.amount),
        }),
      });
      if (res.ok) {
        const newTx = await res.json();
        setTransactions([newTx, ...transactions]);
        setShowAddModal(false);
        setFormData({
          description: '',
          amount: '',
          category: categories.find(c => c.type === 'expense')?.name || '',
          type: 'expense',
          date: format(new Date(), 'yyyy-MM-dd'),
        });
      }
    } catch (error) {
      console.error('Failed to add transaction:', error);
    }
  };

  const parseSMS = async () => {
    if (!smsText.trim()) return;
    setIsParsing(true);
    try {
      const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY || '' });
      const response = await ai.models.generateContent({
        model: "gemini-3-flash-preview",
        contents: `Extract transaction details from this SMS: "${smsText}". 
        Current date is ${format(new Date(), 'yyyy-MM-dd')}.
        The currency is Indian Rupee (INR).
        Rules:
        - Amount must be a number.
        - Type must be 'income' or 'expense'.
        - Category must be one of: ${categories.filter(c => c.type === 'expense').map(c => c.name).join(', ')} (for expenses) or ${categories.filter(c => c.type === 'income').map(c => c.name).join(', ')} (for income).
        - Description should be concise.
        - Date should be in yyyy-MM-dd format.`,
        config: {
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              description: { type: Type.STRING },
              amount: { type: Type.NUMBER },
              category: { type: Type.STRING },
              type: { type: Type.STRING },
              date: { type: Type.STRING },
            },
            required: ["description", "amount", "category", "type", "date"],
          },
        },
      });

      const result = JSON.parse(response.text || '{}');
      setFormData({
        description: result.description || '',
        amount: result.amount?.toString() || '',
        category: result.category || (result.type === 'income' ? categories.find(c => c.type === 'income')?.name : categories.find(c => c.type === 'expense')?.name) || '',
        type: (result.type as 'income' | 'expense') || 'expense',
        date: result.date || format(new Date(), 'yyyy-MM-dd'),
      });
      setShowSMSModal(false);
      setShowAddModal(true);
      setSmsText('');
    } catch (error) {
      console.error('Failed to parse SMS:', error);
      alert('Failed to parse SMS. Please try again or enter manually.');
    } finally {
      setIsParsing(false);
    }
  };

  const deleteTransaction = async (id: number) => {
    try {
      await fetch(`/api/transactions/${id}`, { method: 'DELETE' });
      setTransactions(transactions.filter(t => t.id !== id));
    } catch (error) {
      console.error('Failed to delete transaction:', error);
    }
  };

  const totalIncome = transactions
    .filter(t => t.type === 'income')
    .reduce((acc, t) => acc + t.amount, 0);

  const totalExpenses = transactions
    .filter(t => t.type === 'expense')
    .reduce((acc, t) => acc + t.amount, 0);

  const balance = totalIncome - totalExpenses;

  const chartData = Object.entries(
    transactions
      .filter(t => t.type === chartType)
      .reduce((acc, t) => {
        acc[t.category] = (acc[t.category] || 0) + t.amount;
        return acc;
      }, {} as Record<string, number>)
  ).map(([name, value]) => ({ name, value }));

  const monthlyData = transactions.reduce((acc, t) => {
    const month = format(new Date(t.date), 'MMM yyyy');
    if (!acc[month]) {
      acc[month] = { name: month, income: 0, expense: 0 };
    }
    acc[month][t.type] += t.amount;
    return acc;
  }, {} as Record<string, { name: string, income: number, expense: number }>);

  const barChartData = (Object.values(monthlyData) as Array<{ name: string, income: number, expense: number }>).sort((a, b) => new Date(a.name).getTime() - new Date(b.name).getTime());

  const recentTransactions = transactions.slice(0, 5);

  const filteredTransactions = transactions
    .filter(tx => tx.description.toLowerCase().includes(searchQuery.toLowerCase()))
    .filter(tx => filterCategory === 'all' || tx.category === filterCategory)
    .sort((a, b) => {
      let comparison = 0;
      if (sortField === 'date') {
        comparison = new Date(a.date).getTime() - new Date(b.date).getTime();
      } else if (sortField === 'amount') {
        comparison = a.amount - b.amount;
      } else if (sortField === 'category') {
        comparison = a.category.localeCompare(b.category);
      }
      return sortOrder === 'asc' ? comparison : -comparison;
    });

  // Summary Calculations
  const currentMonth = format(new Date(), 'MMM yyyy');
  const previousMonth = format(subMonths(new Date(), 1), 'MMM yyyy');
  
  const currentMonthTransactions = transactions.filter(t => format(new Date(t.date), 'MMM yyyy') === currentMonth);
  const previousMonthTransactions = transactions.filter(t => format(new Date(t.date), 'MMM yyyy') === previousMonth);
  
  const monthlyIncome = currentMonthTransactions
    .filter(t => t.type === 'income')
    .reduce((acc, t) => acc + t.amount, 0);
    
  const monthlyExpenses = currentMonthTransactions
    .filter(t => t.type === 'expense')
    .reduce((acc, t) => acc + t.amount, 0);

  const previousMonthExpenses = previousMonthTransactions
    .filter(t => t.type === 'expense')
    .reduce((acc, t) => acc + t.amount, 0);

  const isSpendingUp = previousMonthExpenses > 0 && monthlyExpenses > previousMonthExpenses * 1.10;
    
  const monthlySavings = monthlyIncome - monthlyExpenses;
  
  const totalBudget = budgets.reduce((acc, b) => acc + b.amount, 0);
  const budgetAdherence = totalBudget > 0 ? Math.max(0, 100 - (monthlyExpenses / totalBudget) * 100) : 100;
  
  const savingsRate = monthlyIncome > 0 ? (monthlySavings / monthlyIncome) * 100 : 0;

  // Average spending per category
  const categoryAverages = categories
    .filter(c => c.type === 'expense')
    .map(cat => {
      const category = cat.name;
      const categoryTransactions = transactions.filter(t => t.type === 'expense' && t.category === category);
      const monthsWithTransactions = new Set(categoryTransactions.map(t => format(new Date(t.date), 'MMM yyyy'))).size;
      const totalSpent = categoryTransactions.reduce((acc, t) => acc + t.amount, 0);
      return {
        category,
        average: monthsWithTransactions > 0 ? totalSpent / monthsWithTransactions : 0
      };
    });

  const topCategory = [...categoryAverages].sort((a, b) => b.average - a.average)[0];

  const allExpenseTransactions = transactions.filter(t => t.type === 'expense');
  const allMonthsWithExpenses = new Set(allExpenseTransactions.map(t => format(new Date(t.date), 'MMM yyyy'))).size;
  const totalHistoricalExpenses = allExpenseTransactions.reduce((acc, t) => acc + t.amount, 0);
  const averageMonthlySpending = allMonthsWithExpenses > 0 ? totalHistoricalExpenses / allMonthsWithExpenses : 0;

  // Reports Data Calculation
  const getReportData = () => {
    const now = new Date();
    let interval: { start: Date; end: Date };

    if (reportPeriod === 'weekly') {
      interval = { start: startOfWeek(now), end: endOfWeek(now) };
    } else if (reportPeriod === 'monthly') {
      interval = { start: startOfMonth(now), end: endOfMonth(now) };
    } else {
      interval = { start: startOfYear(now), end: endOfYear(now) };
    }

    const periodTransactions = transactions.filter(t => 
      isWithinInterval(new Date(t.date), interval)
    );

    const expenseTransactions = periodTransactions.filter(t => t.type === 'expense');
    
    // Category Data for Pie Chart
    const reportCategoryData = categories
      .filter(c => c.type === 'expense')
      .map(cat => {
        const category = cat.name;
        const amount = expenseTransactions
          .filter(t => t.category === category)
          .reduce((acc, t) => acc + t.amount, 0);
        return { name: category, value: amount, color: cat.color || '#64748b' };
      }).filter(d => d.value > 0);

    // Trend Data for Bar Chart
    let reportTrendData: any[] = [];
    if (reportPeriod === 'weekly') {
      reportTrendData = eachDayOfInterval(interval).map(date => {
        const amount = expenseTransactions
          .filter(t => isSameDay(new Date(t.date), date))
          .reduce((acc, t) => acc + t.amount, 0);
        return { name: format(date, 'EEE'), value: amount };
      });
    } else if (reportPeriod === 'monthly') {
      reportTrendData = eachWeekOfInterval(interval).map((date, index) => {
        const amount = expenseTransactions
          .filter(t => isSameWeek(new Date(t.date), date))
          .reduce((acc, t) => acc + t.amount, 0);
        return { name: `W${index + 1}`, value: amount };
      });
    } else {
      reportTrendData = eachMonthOfInterval(interval).map(date => {
        const amount = expenseTransactions
          .filter(t => isSameMonth(new Date(t.date), date))
          .reduce((acc, t) => acc + t.amount, 0);
        return { name: format(date, 'MMM'), value: amount };
      });
    }

    return { 
      reportCategoryData, 
      reportTrendData, 
      reportTotalSpending: expenseTransactions.reduce((acc, t) => acc + t.amount, 0),
      periodLabel: reportPeriod === 'weekly' ? `${format(interval.start, 'MMM d')} - ${format(interval.end, 'MMM d, yyyy')}` :
                   reportPeriod === 'monthly' ? format(now, 'MMMM yyyy') :
                   format(now, 'yyyy')
    };
  };

  const { reportCategoryData, reportTrendData, reportTotalSpending, periodLabel } = getReportData();

  // Custom Tooltips
  const totalChartValue = chartData.reduce((acc, curr) => acc + (curr.value as number), 0);
  
  const CustomPieTooltip = ({ active, payload }: any) => {
    if (active && payload && payload.length) {
      const data = payload[0];
      const percent = totalChartValue > 0 ? ((data.value / totalChartValue) * 100).toFixed(1) : 0;
      return (
        <div className="bg-white/90 backdrop-blur-sm p-3 rounded-2xl shadow-xl border border-white/20 ring-1 ring-black/5">
          <div className="flex items-center gap-2 mb-1.5">
            <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: data.payload.fill || categoryColorMap[data.name] }} />
            <p className="font-bold text-zinc-900 text-sm">{data.name}</p>
          </div>
          <div className="space-y-0.5">
            <p className="text-indigo-600 font-extrabold text-base">{formatCurrency(data.value)}</p>
            <p className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">{percent}% of total {chartType}</p>
          </div>
        </div>
      );
    }
    return null;
  };

  const CustomReportPieTooltip = ({ active, payload }: any) => {
    if (active && payload && payload.length) {
      const data = payload[0];
      const percent = reportTotalSpending > 0 ? ((data.value / reportTotalSpending) * 100).toFixed(1) : 0;
      return (
        <div className="bg-white/90 backdrop-blur-sm p-3 rounded-2xl shadow-xl border border-white/20 ring-1 ring-black/5">
          <div className="flex items-center gap-2 mb-1.5">
            <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: data.payload.fill || categoryColorMap[data.name] }} />
            <p className="font-bold text-zinc-900 text-sm">{data.name}</p>
          </div>
          <div className="space-y-0.5">
            <p className="text-indigo-600 font-extrabold text-base">{formatCurrency(data.value)}</p>
            <p className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">{percent}% of period total</p>
          </div>
        </div>
      );
    }
    return null;
  };

  const CustomReportBarTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-white/90 backdrop-blur-sm p-3 rounded-2xl shadow-xl border border-white/20 ring-1 ring-black/5">
          <p className="font-bold text-zinc-900 text-xs mb-1.5 uppercase tracking-wider opacity-60">{label}</p>
          <div className="space-y-0.5">
            <p className="text-rose-600 font-extrabold text-base">{formatCurrency(payload[0].value)}</p>
            <p className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">Total Spending</p>
          </div>
        </div>
      );
    }
    return null;
  };

  const totalBarIncome = barChartData.reduce((acc, curr) => acc + curr.income, 0);
  const totalBarExpense = barChartData.reduce((acc, curr) => acc + curr.expense, 0);

  const CustomBarTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-white/90 backdrop-blur-sm p-4 rounded-2xl shadow-xl border border-white/20 ring-1 ring-black/5 min-w-[180px]">
          <p className="font-bold text-zinc-900 text-xs mb-3 uppercase tracking-wider opacity-60">{label}</p>
          <div className="space-y-3">
            {payload.map((entry: any, index: number) => {
              const total = entry.dataKey === 'income' ? totalBarIncome : totalBarExpense;
              const percent = total > 0 ? ((entry.value / total) * 100).toFixed(1) : 0;
              return (
                <div key={index} className="flex flex-col">
                  <div className="flex justify-between items-center mb-0.5">
                    <span style={{ color: entry.color }} className="text-xs font-bold uppercase tracking-wide">{entry.name}</span>
                    <span className="text-xs font-bold text-zinc-900">{formatCurrency(entry.value)}</span>
                  </div>
                  <div className="w-full h-1 bg-zinc-100 rounded-full overflow-hidden">
                    <div 
                      className="h-full rounded-full transition-all duration-500" 
                      style={{ backgroundColor: entry.color, width: `${percent}%` }} 
                    />
                  </div>
                  <p className="text-[10px] text-zinc-400 mt-1 font-medium">{percent}% of all {entry.name.toLowerCase()}</p>
                </div>
              );
            })}
          </div>
        </div>
      );
    }
    return null;
  };

  const totalAverage = categoryAverages.reduce((acc, curr) => acc + curr.average, 0);

  const CustomAvgTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      const data = payload[0];
      const percent = totalAverage > 0 ? ((data.value / totalAverage) * 100).toFixed(1) : 0;
      return (
        <div className="bg-white/90 backdrop-blur-sm p-3 rounded-2xl shadow-xl border border-white/20 ring-1 ring-black/5">
          <div className="flex items-center gap-2 mb-1.5">
            <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: data.payload.fill }} />
            <p className="font-bold text-zinc-900 text-sm">{label}</p>
          </div>
          <div className="space-y-0.5">
            <p className="text-amber-600 font-extrabold text-base">{formatCurrency(data.value)}</p>
            <p className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">{percent}% of total averages</p>
          </div>
        </div>
      );
    }
    return null;
  };

  // SVG Gradients for charts
  const ChartGradients = () => (
    <svg width="0" height="0" style={{ position: 'absolute' }}>
      <defs>
        <linearGradient id="incomeGradient" x1="0" y1="0" x2="0" y2="1">
          <stop offset="5%" stopColor="#10b981" stopOpacity={0.8}/>
          <stop offset="95%" stopColor="#10b981" stopOpacity={0}/>
        </linearGradient>
        <linearGradient id="expenseGradient" x1="0" y1="0" x2="0" y2="1">
          <stop offset="5%" stopColor="#f43f5e" stopOpacity={0.8}/>
          <stop offset="95%" stopColor="#f43f5e" stopOpacity={0}/>
        </linearGradient>
        <linearGradient id="trendGradient" x1="0" y1="0" x2="0" y2="1">
          <stop offset="5%" stopColor="#6366f1" stopOpacity={0.3}/>
          <stop offset="95%" stopColor="#6366f1" stopOpacity={0}/>
        </linearGradient>
      </defs>
    </svg>
  );

  const renderActiveShape = (props: any) => {
    const RADIAN = Math.PI / 180;
    const { cx, cy, midAngle, innerRadius, outerRadius, startAngle, endAngle, fill, payload, percent, value } = props;
    const sin = Math.sin(-RADIAN * midAngle);
    const cos = Math.cos(-RADIAN * midAngle);
    const sx = cx + (outerRadius + 10) * cos;
    const sy = cy + (outerRadius + 10) * sin;
    const mx = cx + (outerRadius + 30) * cos;
    const my = cy + (outerRadius + 30) * sin;
    const ex = mx + (cos >= 0 ? 1 : -1) * 22;
    const ey = my;
    const textAnchor = cos >= 0 ? 'start' : 'end';

    return (
      <g>
        <Sector
          cx={cx}
          cy={cy}
          innerRadius={innerRadius}
          outerRadius={outerRadius + 6}
          startAngle={startAngle}
          endAngle={endAngle}
          fill={fill}
        />
        <Sector
          cx={cx}
          cy={cy}
          startAngle={startAngle}
          endAngle={endAngle}
          innerRadius={outerRadius + 8}
          outerRadius={outerRadius + 12}
          fill={fill}
        />
      </g>
    );
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-600"></div>
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 md:py-12 pb-24 md:pb-12">
      <ChartGradients />
      {/* Header */}
      <header className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-zinc-900">SpendWise</h1>
          <p className="text-zinc-500 mt-1">Manage your finances with precision.</p>
        </div>
        
        {/* Desktop Navigation */}
        <div className="hidden md:flex items-center gap-2 p-1 bg-zinc-100 rounded-xl w-fit">
          <button
            onClick={() => setActiveTab('dashboard')}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              activeTab === 'dashboard' ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
            )}
          >
            <LayoutDashboard size={18} />
            Dashboard
          </button>
          <button
            onClick={() => setActiveTab('history')}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              activeTab === 'history' ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
            )}
          >
            <History size={18} />
            History
          </button>
          <button
            onClick={() => setActiveTab('budgets')}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              activeTab === 'budgets' ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
            )}
          >
            <Target size={18} />
            Budgets
          </button>
          <button
            onClick={() => setActiveTab('categories')}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              activeTab === 'categories' ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
            )}
          >
            <Tag size={18} />
            Categories
          </button>
          <button
            onClick={() => setActiveTab('summary')}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              activeTab === 'summary' ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
            )}
          >
            <Activity size={18} />
            Summary
          </button>
          <button
            onClick={() => setActiveTab('reports')}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all",
              activeTab === 'reports' ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
            )}
          >
            <BarChart3 size={18} />
            Reports
          </button>
        </div>
      </header>

      {activeTab === 'dashboard' ? (
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
                <div className="p-4 bg-amber-50 border border-amber-200 rounded-2xl flex items-start sm:items-center justify-between gap-4">
                  <div className="flex items-center gap-3">
                    <div className="p-2 bg-amber-100 rounded-lg text-amber-600">
                      <TrendingUp size={20} />
                    </div>
                    <div>
                      <h4 className="text-sm font-bold text-amber-900">Spending Alert</h4>
                      <p className="text-sm text-amber-700">Your spending this month is up by more than 10% compared to last month. Consider reviewing your average monthly spending on the Summary tab.</p>
                    </div>
                  </div>
                  <button 
                    onClick={() => setShowSpendingAlert(false)}
                    className="p-2 text-amber-500 hover:bg-amber-100 rounded-lg transition-colors flex-shrink-0"
                    aria-label="Dismiss alert"
                  >
                    <X size={16} />
                  </button>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          {/* Stats Grid */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
            <motion.div 
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              className="p-5 md:p-6 rounded-3xl bg-indigo-600 text-white shadow-xl shadow-indigo-200"
            >
              <div className="flex items-center justify-between mb-3 md:mb-4">
                <div className="p-2 bg-white/20 rounded-xl">
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
              className="p-5 md:p-6 rounded-3xl bg-white border border-zinc-200 shadow-sm"
            >
              <div className="flex items-center justify-between mb-3 md:mb-4">
                <div className="p-2 bg-emerald-50 text-emerald-600 rounded-xl">
                  <TrendingUp size={20} className="md:w-6 md:h-6" />
                </div>
                <span className="text-[10px] md:text-xs font-medium uppercase tracking-wider text-zinc-400">Total Income</span>
              </div>
              <h2 className="text-2xl md:text-3xl font-bold text-zinc-900">{formatCurrency(totalIncome)}</h2>
            </motion.div>

            <motion.div 
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.2 }}
              className="p-5 md:p-6 rounded-3xl bg-white border border-zinc-200 shadow-sm"
            >
              <div className="flex items-center justify-between mb-3 md:mb-4">
                <div className="p-2 bg-rose-50 text-rose-600 rounded-xl">
                  <TrendingDown size={20} className="md:w-6 md:h-6" />
                </div>
                <span className="text-[10px] md:text-xs font-medium uppercase tracking-wider text-zinc-400">Total Expenses</span>
              </div>
              <h2 className="text-2xl md:text-3xl font-bold text-zinc-900">{formatCurrency(totalExpenses)}</h2>
            </motion.div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 md:gap-8">
            {/* Chart Section */}
            <section className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <div className="flex items-center justify-between mb-6 md:mb-8">
                <h3 className="text-base md:text-lg font-semibold flex items-center gap-2">
                  <PieChartIcon size={20} className="text-indigo-600" />
                  {chartType === 'expense' ? 'Spending by Category' : 'Income by Source'}
                </h3>
                <div className="flex bg-zinc-100 p-1 rounded-lg">
                  <button
                    onClick={() => setChartType('expense')}
                    className={cn(
                      "px-3 py-1 text-xs font-medium rounded-md transition-all",
                      chartType === 'expense' ? "bg-white shadow-sm text-zinc-900" : "text-zinc-500 hover:text-zinc-700"
                    )}
                  >
                    Expenses
                  </button>
                  <button
                    onClick={() => setChartType('income')}
                    className={cn(
                      "px-3 py-1 text-xs font-medium rounded-md transition-all",
                      chartType === 'income' ? "bg-white shadow-sm text-zinc-900" : "text-zinc-500 hover:text-zinc-700"
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
                        innerRadius={60}
                        outerRadius={90}
                        paddingAngle={5}
                        dataKey="value"
                        onMouseEnter={(_, index) => setDashboardPieIndex(index)}
                        onClick={(data) => setSelectedChartItem({ type: 'category', value: data.name })}
                        className="cursor-pointer"
                      >
                        {chartData.map((entry, index) => (
                          <Cell key={`cell-${index}`} fill={categoryColorMap[entry.name] || '#94a3b8'} stroke="none" />
                        ))}
                      </Pie>
                      <Tooltip content={<CustomPieTooltip />} />
                    </PieChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-full flex flex-col items-center justify-center text-zinc-400">
                    <PieChartIcon size={48} strokeWidth={1} className="mb-2" />
                    <p>No expense data to display</p>
                  </div>
                )}
              </div>
            </section>

            {/* Recent Transactions */}
            <section className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <div className="flex items-center justify-between mb-6 md:mb-8">
                <h3 className="text-base md:text-lg font-semibold flex items-center gap-2">
                  <ArrowUpRight size={20} className="text-indigo-600" />
                  Recent Transactions
                </h3>
                <button 
                  onClick={() => setActiveTab('history')}
                  className="text-sm font-medium text-indigo-600 hover:text-indigo-700"
                >
                  View All
                </button>
              </div>
              <div className="space-y-4">
                {recentTransactions.length > 0 ? (
                  recentTransactions.map((tx) => (
                    <div key={tx.id} className="flex items-center justify-between p-3 md:p-4 rounded-2xl bg-zinc-50 hover:bg-zinc-100 transition-colors group">
                      <div className="flex items-center gap-3 md:gap-4">
                        <div className={cn(
                          "p-2 md:p-3 rounded-xl",
                          tx.type === 'income' ? "bg-emerald-100 text-emerald-600" : "bg-rose-100 text-rose-600"
                        )}>
                          {tx.type === 'income' ? <ArrowDownLeft size={16} className="md:w-5 md:h-5" /> : <ArrowUpRight size={16} className="md:w-5 md:h-5" />}
                        </div>
                        <div>
                          <p className="font-semibold text-zinc-900 text-sm md:text-base">{tx.description}</p>
                          <p className="text-[10px] md:text-xs text-zinc-500">{tx.category} • {format(new Date(tx.date), 'MMM d, yyyy')}</p>
                        </div>
                      </div>
                      <div className="text-right">
                        <p className={cn(
                          "font-bold text-sm md:text-base",
                          tx.type === 'income' ? "text-emerald-600" : "text-rose-600"
                        )}>
                          {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                        </p>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className="text-center py-12 text-zinc-400">
                    <p>No transactions yet</p>
                  </div>
                )}
              </div>
            </section>
          </div>

          {/* Income vs Expenses Bar Chart */}
          <section className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm mt-6 md:mt-8">
            <div className="flex items-center justify-between mb-6 md:mb-8">
              <h3 className="text-base md:text-lg font-semibold flex items-center gap-2">
                <BarChart3 size={20} className="text-indigo-600" />
                Income vs Expenses
              </h3>
            </div>
            <div className="h-[250px] md:h-[300px] w-full">
              {barChartData.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={barChartData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e4e4e7" />
                    <XAxis dataKey="name" axisLine={false} tickLine={false} tick={{ fill: '#71717a', fontSize: 12 }} dy={10} />
                    <YAxis axisLine={false} tickLine={false} tick={{ fill: '#71717a', fontSize: 12 }} tickFormatter={(value) => `₹${value}`} />
                    <Tooltip cursor={{ fill: '#f4f4f5', opacity: 0.4 }} content={<CustomBarTooltip />} />
                    <Bar 
                      dataKey="income" 
                      name="Income" 
                      fill="url(#incomeGradient)" 
                      radius={[4, 4, 0, 0]} 
                      maxBarSize={40} 
                      onClick={(data) => setSelectedChartItem({ type: 'month', value: data.name })}
                      className="cursor-pointer"
                    />
                    <Bar 
                      dataKey="expense" 
                      name="Expense" 
                      fill="url(#expenseGradient)" 
                      radius={[4, 4, 0, 0]} 
                      maxBarSize={40} 
                      onClick={(data) => setSelectedChartItem({ type: 'month', value: data.name })}
                      className="cursor-pointer"
                    />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <div className="h-full flex flex-col items-center justify-center text-zinc-400">
                  <BarChart3 size={48} strokeWidth={1} className="mb-2" />
                  <p>No data to display</p>
                </div>
              )}
            </div>
          </section>
        </div>
      ) : activeTab === 'history' ? (
        /* History Tab */
        <motion.div 
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm"
        >
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
            <div>
              <h3 className="text-xl font-bold">Transaction History</h3>
              <div className="text-sm text-zinc-500">
                Showing {filteredTransactions.length} transactions
              </div>
            </div>
            
            <div className="flex flex-col sm:flex-row gap-3 w-full sm:w-auto">
              <div className="relative w-full sm:w-64">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Search size={16} className="text-zinc-400" />
                </div>
                <input
                  type="text"
                  placeholder="Search transactions..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full pl-10 pr-4 py-2 bg-zinc-50 border border-zinc-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                />
              </div>
              
              <div className="flex gap-2">
                <div className="relative group">
                  <button className="flex items-center gap-2 px-3 py-2 bg-zinc-50 border border-zinc-200 rounded-xl text-sm font-medium text-zinc-700 hover:bg-zinc-100 transition-colors">
                    <Filter size={16} />
                    <span className="hidden sm:inline">Category</span>
                  </button>
                  <div className="absolute right-0 mt-2 w-48 bg-white border border-zinc-200 rounded-xl shadow-lg opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all z-10">
                    <div className="p-2 max-h-60 overflow-y-auto">
                      <button
                        onClick={() => setFilterCategory('all')}
                        className={cn(
                          "w-full text-left px-3 py-2 text-sm rounded-lg transition-colors",
                          filterCategory === 'all' ? "bg-indigo-50 text-indigo-700 font-medium" : "text-zinc-700 hover:bg-zinc-50"
                        )}
                      >
                        All Categories
                      </button>
                      {categories.map(cat => (
                        <button
                          key={cat.id}
                          onClick={() => setFilterCategory(cat.name)}
                          className={cn(
                            "w-full text-left px-3 py-2 text-sm rounded-lg transition-colors",
                            filterCategory === cat.name ? "bg-indigo-50 text-indigo-700 font-medium" : "text-zinc-700 hover:bg-zinc-50"
                          )}
                        >
                          {cat.name}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>

                <div className="relative group">
                  <button className="flex items-center gap-2 px-3 py-2 bg-zinc-50 border border-zinc-200 rounded-xl text-sm font-medium text-zinc-700 hover:bg-zinc-100 transition-colors">
                    <ArrowUpDown size={16} />
                    <span className="hidden sm:inline">Sort</span>
                  </button>
                  <div className="absolute right-0 mt-2 w-48 bg-white border border-zinc-200 rounded-xl shadow-lg opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all z-10">
                    <div className="p-2">
                      <div className="px-3 py-1 text-xs font-semibold text-zinc-400 uppercase tracking-wider">Sort By</div>
                      {['date', 'amount', 'category'].map(field => (
                        <button
                          key={field}
                          onClick={() => setSortField(field as any)}
                          className={cn(
                            "w-full text-left px-3 py-2 text-sm rounded-lg transition-colors capitalize",
                            sortField === field ? "bg-indigo-50 text-indigo-700 font-medium" : "text-zinc-700 hover:bg-zinc-50"
                          )}
                        >
                          {field}
                        </button>
                      ))}
                      <div className="border-t border-zinc-100 my-1"></div>
                      <div className="px-3 py-1 text-xs font-semibold text-zinc-400 uppercase tracking-wider">Order</div>
                      {['desc', 'asc'].map(order => (
                        <button
                          key={order}
                          onClick={() => setSortOrder(order as any)}
                          className={cn(
                            "w-full text-left px-3 py-2 text-sm rounded-lg transition-colors",
                            sortOrder === order ? "bg-indigo-50 text-indigo-700 font-medium" : "text-zinc-700 hover:bg-zinc-50"
                          )}
                        >
                          {order === 'desc' ? 'Descending' : 'Ascending'}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          
          <div className="overflow-x-auto hidden md:block">
            <table className="w-full">
              <thead>
                <tr className="text-left text-xs font-semibold uppercase tracking-wider text-zinc-400 border-b border-zinc-100">
                  <th className="pb-4 px-4">Date</th>
                  <th className="pb-4 px-4">Description / Source</th>
                  <th className="pb-4 px-4">Category</th>
                  <th className="pb-4 px-4">Type</th>
                  <th className="pb-4 px-4 text-right">Amount</th>
                  <th className="pb-4 px-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-50">
                {filteredTransactions.length > 0 ? (
                  filteredTransactions.map((tx) => (
                    <tr key={tx.id} className="group hover:bg-zinc-50 transition-colors">
                      <td className="py-4 px-4 text-sm text-zinc-600">
                        {format(new Date(tx.date), 'MMM d, yyyy')}
                      </td>
                      <td className="py-4 px-4 font-medium text-zinc-900">
                        {tx.description}
                      </td>
                      <td className="py-4 px-4">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-zinc-100 text-zinc-800">
                          {tx.category}
                        </span>
                      </td>
                      <td className="py-4 px-4">
                        <span className={cn(
                          "inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium",
                          tx.type === 'income' ? "bg-emerald-100 text-emerald-800" : "bg-rose-100 text-rose-800"
                        )}>
                          {tx.type}
                        </span>
                      </td>
                      <td className={cn(
                        "py-4 px-4 text-right font-bold",
                        tx.type === 'income' ? "text-emerald-600" : "text-rose-600"
                      )}>
                        {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                      </td>
                      <td className="py-4 px-4 text-right">
                        <button 
                          onClick={() => deleteTransaction(tx.id)}
                          className="p-2 text-zinc-400 hover:text-rose-600 transition-colors"
                        >
                          <Trash2 size={18} />
                        </button>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-zinc-500">
                      No transactions found matching "{searchQuery}"
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
                <div key={tx.id} className="p-4 rounded-2xl bg-zinc-50 border border-zinc-100 flex flex-col gap-3">
                  <div className="flex justify-between items-start">
                    <div>
                      <h4 className="font-bold text-zinc-900">{tx.description}</h4>
                      <p className="text-xs text-zinc-500">{format(new Date(tx.date), 'MMM d, yyyy')}</p>
                    </div>
                    <div className="flex flex-col items-end gap-1">
                      <span className={cn(
                        "font-bold text-lg",
                        tx.type === 'income' ? "text-emerald-600" : "text-rose-600"
                      )}>
                        {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                      </span>
                      <button 
                        onClick={() => deleteTransaction(tx.id)}
                        className="text-zinc-400 hover:text-rose-600 transition-colors"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-medium bg-zinc-200 text-zinc-800">
                      {tx.category}
                    </span>
                    <span className={cn(
                      "inline-flex items-center px-2.5 py-0.5 rounded-full text-[10px] font-medium",
                      tx.type === 'income' ? "bg-emerald-100 text-emerald-800" : "bg-rose-100 text-rose-800"
                    )}>
                      {tx.type}
                    </span>
                  </div>
                </div>
              ))
            ) : (
              <div className="py-8 text-center text-zinc-500">
                No transactions found matching "{searchQuery}"
              </div>
            )}
          </div>
        </motion.div>
      ) : activeTab === 'budgets' ? (
        /* Budgets Tab */
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="space-y-6 md:space-y-8"
        >
          <div className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
            <div className="mb-6 md:mb-8">
              <h3 className="text-xl font-bold">Monthly Budgets</h3>
              <p className="text-sm text-zinc-500">Set and track your spending limits for each category.</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 md:gap-6">
              {categories.filter(c => c.type === 'expense').map((cat) => {
                const category = cat.name;
                const budget = budgets.find(b => b.category === category)?.amount || 0;
                const spent = transactions
                  .filter(t => t.type === 'expense' && t.category === category && format(new Date(t.date), 'MMM yyyy') === format(new Date(), 'MMM yyyy'))
                  .reduce((acc, t) => acc + t.amount, 0);
                const percentage = budget > 0 ? Math.min((spent / budget) * 100, 100) : 0;
                const isOverBudget = spent > budget && budget > 0;

                return (
                  <div key={category} className="p-5 md:p-6 rounded-2xl bg-zinc-50 border border-zinc-100 space-y-4">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                      <div className="flex items-center gap-3">
                        <div className="w-3 h-3 rounded-full flex-shrink-0" style={{ backgroundColor: cat.color || '#94a3b8' }} />
                        <span className="font-semibold text-zinc-900">{category}</span>
                      </div>
                      <div className="flex items-center gap-2 self-end sm:self-auto">
                        <span className="text-[10px] md:text-xs text-zinc-400 font-medium uppercase">Budget:</span>
                        <input
                          type="number"
                          placeholder="Set limit"
                          defaultValue={budget || ''}
                          onBlur={(e) => handleSetBudget(category, parseFloat(e.target.value) || 0)}
                          className="w-20 md:w-24 px-2 py-1 bg-white border border-zinc-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                        />
                      </div>
                    </div>

                    <div className="space-y-2">
                      <div className="flex justify-between text-xs md:text-sm">
                        <span className="text-zinc-500">Spent: {formatCurrency(spent)}</span>
                        <span className={cn(
                          "font-medium",
                          isOverBudget ? "text-rose-600" : "text-zinc-900"
                        )}>
                          {budget > 0 ? `${Math.round((spent / budget) * 100)}%` : 'No budget set'}
                        </span>
                      </div>
                      <div className="h-2 w-full bg-zinc-200 rounded-full overflow-hidden">
                        <motion.div
                          initial={{ width: 0 }}
                          animate={{ width: `${percentage}%` }}
                          className={cn(
                            "h-full rounded-full transition-all",
                            isOverBudget ? "bg-rose-500" : "bg-indigo-500"
                          )}
                        />
                      </div>
                      {isOverBudget && (
                        <p className="text-[10px] md:text-xs text-rose-600 font-medium">Over budget by {formatCurrency(spent - budget)}</p>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </motion.div>
      ) : activeTab === 'categories' ? (
        /* Categories Tab */
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="space-y-6 md:space-y-8"
        >
          <div className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-8">
              <div>
                <h3 className="text-xl font-bold">Manage Categories</h3>
                <p className="text-sm text-zinc-500">Add or remove categories for your transactions.</p>
              </div>
              <button 
                onClick={() => setShowAddCategoryModal(true)}
                className="flex items-center justify-center gap-2 px-6 py-3 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all active:scale-[0.98]"
              >
                <Plus size={20} />
                Add Category
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wider mb-4">Expense Categories</h4>
                <div className="space-y-3">
                  {categories.filter(c => c.type === 'expense').map(cat => (
                    <div key={cat.id} className="flex items-center justify-between p-4 rounded-2xl bg-zinc-50 border border-zinc-100">
                      <div className="flex items-center gap-3">
                        <div className="w-4 h-4 rounded-full" style={{ backgroundColor: cat.color }} />
                        <span className="font-semibold text-zinc-900">{cat.name}</span>
                      </div>
                      <button 
                        onClick={() => handleDeleteCategory(cat.id)}
                        className="p-2 text-zinc-400 hover:text-rose-600 hover:bg-rose-50 rounded-lg transition-all"
                      >
                        <Trash2 size={18} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wider mb-4">Income Categories</h4>
                <div className="space-y-3">
                  {categories.filter(c => c.type === 'income').map(cat => (
                    <div key={cat.id} className="flex items-center justify-between p-4 rounded-2xl bg-zinc-50 border border-zinc-100">
                      <div className="flex items-center gap-3">
                        <div className="w-4 h-4 rounded-full" style={{ backgroundColor: cat.color }} />
                        <span className="font-semibold text-zinc-900">{cat.name}</span>
                      </div>
                      <button 
                        onClick={() => handleDeleteCategory(cat.id)}
                        className="p-2 text-zinc-400 hover:text-rose-600 hover:bg-rose-50 rounded-lg transition-all"
                      >
                        <Trash2 size={18} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </motion.div>
      ) : activeTab === 'summary' ? (
        /* Summary Tab */
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="space-y-6 md:space-y-8"
        >
          {/* Summary Cards */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 md:gap-6">
            <div className="p-4 md:p-6 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <p className="text-[10px] md:text-xs font-semibold text-zinc-400 uppercase mb-1">Monthly Income</p>
              <h4 className="text-lg md:text-2xl font-bold text-emerald-600">{formatCurrency(monthlyIncome)}</h4>
            </div>
            <div className="p-4 md:p-6 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <p className="text-[10px] md:text-xs font-semibold text-zinc-400 uppercase mb-1">Monthly Expenses</p>
              <h4 className="text-lg md:text-2xl font-bold text-rose-600">{formatCurrency(monthlyExpenses)}</h4>
            </div>
            <div className="p-4 md:p-6 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <p className="text-[10px] md:text-xs font-semibold text-zinc-400 uppercase mb-1">Monthly Savings</p>
              <h4 className="text-lg md:text-2xl font-bold text-indigo-600">{formatCurrency(monthlySavings)}</h4>
            </div>
            <div className="p-4 md:p-6 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <p className="text-[10px] md:text-xs font-semibold text-zinc-400 uppercase mb-1">Budget Adherence</p>
              <h4 className="text-lg md:text-2xl font-bold text-zinc-900">{Math.round(budgetAdherence)}%</h4>
            </div>
          </div>

          {/* Insights Row */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
            <div className="p-6 rounded-3xl bg-indigo-50 border border-indigo-100 flex flex-col justify-center items-center text-center">
              <p className="text-xs font-bold text-indigo-400 uppercase mb-2">Savings Rate</p>
              <div className="relative w-24 h-24 flex items-center justify-center">
                <svg className="w-full h-full transform -rotate-90">
                  <circle cx="48" cy="48" r="40" stroke="currentColor" strokeWidth="8" fill="transparent" className="text-indigo-100" />
                  <circle cx="48" cy="48" r="40" stroke="currentColor" strokeWidth="8" fill="transparent" strokeDasharray={251.2} strokeDashoffset={251.2 - (251.2 * Math.max(0, Math.min(savingsRate, 100))) / 100} className="text-indigo-600" />
                </svg>
                <span className="absolute text-xl font-bold text-indigo-600">{Math.round(savingsRate)}%</span>
              </div>
              <p className="text-xs text-indigo-500 mt-2 font-medium">of income saved this month</p>
            </div>

            <div className="p-6 rounded-3xl bg-rose-50 border border-rose-100 flex flex-col justify-center">
              <p className="text-xs font-bold text-rose-400 uppercase mb-1">Highest Spending Category</p>
              <h4 className="text-xl font-bold text-rose-600">{topCategory?.category || 'N/A'}</h4>
              <p className="text-sm text-rose-500 mt-1">Average: {formatCurrency(topCategory?.average || 0)} / month</p>
            </div>

            <div className="p-6 rounded-3xl bg-emerald-50 border border-emerald-100 flex flex-col justify-center">
              <p className="text-xs font-bold text-emerald-400 uppercase mb-1">Financial Health</p>
              <h4 className="text-xl font-bold text-emerald-600">
                {savingsRate > 20 ? 'Excellent' : savingsRate > 10 ? 'Good' : 'Needs Attention'}
              </h4>
              <p className="text-sm text-emerald-500 mt-1">
                {savingsRate > 20 ? 'You are saving a significant portion of your income.' : 'Try to aim for at least 20% savings rate.'}
              </p>
            </div>
          </div>

          {/* Average Monthly Spending Section */}
          <div className="p-6 md:p-8 rounded-3xl bg-amber-50 border border-amber-100 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
            <div>
              <h3 className="text-lg md:text-xl font-bold text-amber-900 mb-1">Average Monthly Spending</h3>
              <p className="text-xs md:text-sm text-amber-700">Calculated across all categories based on your historical transaction data.</p>
            </div>
            <div className="w-full sm:w-auto text-left sm:text-right bg-white px-6 py-4 rounded-2xl shadow-sm border border-amber-100/50">
              <h4 className="text-2xl md:text-3xl font-bold text-amber-600">{formatCurrency(averageMonthlySpending)}</h4>
              <p className="text-[10px] md:text-xs font-bold text-amber-500 uppercase tracking-wider mt-1">Per Month</p>
            </div>
          </div>

          <div className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
            <h3 className="text-lg font-bold mb-6">Average Spending Visualization</h3>
            <div className="h-[250px] md:h-[350px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={categoryAverages} margin={{ top: 10, right: 10, left: -20, bottom: 60 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e4e4e7" />
                  <XAxis 
                    dataKey="category" 
                    axisLine={false} 
                    tickLine={false} 
                    tick={{ fill: '#71717a', fontSize: 10 }} 
                    interval={0}
                    angle={-45}
                    textAnchor="end"
                  />
                  <YAxis axisLine={false} tickLine={false} tick={{ fill: '#71717a', fontSize: 12 }} tickFormatter={(value) => `₹${value}`} />
                  <Tooltip cursor={{ fill: '#f4f4f5', opacity: 0.4 }} content={<CustomAvgTooltip />} />
                  <Bar dataKey="average" name="Avg. Spending" radius={[4, 4, 0, 0]} maxBarSize={40}>
                    {categoryAverages.map((entry, index) => (
                      <Cell 
                        key={`cell-${index}`} 
                        fill={categoryColorMap[entry.category] || '#6366f1'} 
                        stroke="none"
                        className="transition-opacity duration-300 hover:opacity-80 cursor-pointer"
                      />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 md:gap-8">
            <div className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <h3 className="text-lg font-bold mb-4 md:mb-6">Average Spending per Category</h3>
              <div className="space-y-3 md:space-y-4">
                {categoryAverages.map(({ category, average }) => (
                  <div key={category} className="flex items-center justify-between p-3 md:p-4 rounded-2xl bg-zinc-50">
                    <div className="flex items-center gap-3">
                      <div className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: categoryColorMap[category] }} />
                      <span className="text-xs md:text-sm font-medium text-zinc-700">{category}</span>
                    </div>
                    <span className="text-xs md:text-sm font-bold text-zinc-900">{formatCurrency(average)}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <h3 className="text-lg font-bold mb-4 md:mb-6">Monthly Budget Adherence</h3>
              <div className="space-y-4 md:space-y-6">
                {categories.filter(c => c.type === 'expense').map((cat) => {
                  const category = cat.name;
                  const budget = budgets.find(b => b.category === category)?.amount || 0;
                  const spent = transactions
                    .filter(t => t.type === 'expense' && t.category === category && format(new Date(t.date), 'MMM yyyy') === format(new Date(), 'MMM yyyy'))
                    .reduce((acc, t) => acc + t.amount, 0);
                  const isOver = spent > budget && budget > 0;
                  const diff = budget - spent;

                  return (
                    <div key={category} className="flex flex-col gap-2">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                        <div>
                          <p className="text-sm font-bold text-zinc-900">{category}</p>
                          <p className="text-xs text-zinc-500">
                            {budget > 0 ? `${formatCurrency(spent)} of ${formatCurrency(budget)}` : 'No budget set'}
                          </p>
                        </div>
                        {budget > 0 && (
                          <div className={cn(
                            "px-3 py-1 rounded-lg text-[10px] md:text-xs font-bold w-fit",
                            isOver ? "bg-rose-100 text-rose-700" : "bg-emerald-100 text-emerald-700"
                          )}>
                            {isOver ? `Over by ${formatCurrency(Math.abs(diff))}` : `Remaining: ${formatCurrency(diff)}`}
                          </div>
                        )}
                      </div>
                      {budget > 0 && (
                        <div className="w-full h-1.5 bg-zinc-100 rounded-full overflow-hidden">
                          <motion.div 
                            initial={{ width: 0 }}
                            animate={{ width: `${Math.min(100, (spent / budget) * 100)}%` }}
                            transition={{ duration: 1, ease: "easeOut" }}
                            className={cn(
                              "h-full rounded-full",
                              isOver ? "bg-rose-500" : "bg-emerald-500"
                            )}
                          />
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </motion.div>
      ) : activeTab === 'reports' ? (
        /* Reports Tab */
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="space-y-8"
        >
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div>
              <h3 className="text-2xl font-bold text-zinc-900">Financial Reports</h3>
              <p className="text-zinc-500">{periodLabel}</p>
            </div>
            <div className="flex bg-zinc-100 p-1 rounded-xl w-fit">
              {(['weekly', 'monthly', 'yearly'] as const).map((period) => (
                <button
                  key={period}
                  onClick={() => setReportPeriod(period)}
                  className={cn(
                    "px-4 py-2 text-sm font-medium rounded-lg transition-all capitalize",
                    reportPeriod === period ? "bg-white shadow-sm text-indigo-600" : "text-zinc-500 hover:text-zinc-700"
                  )}
                >
                  {period}
                </button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
            <div className="md:col-span-2 p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <div className="flex items-center justify-between mb-6 md:mb-8">
                <h4 className="text-base md:text-lg font-bold flex items-center gap-2">
                  <TrendingUp size={20} className="text-indigo-600" />
                  Spending Trend
                </h4>
                <div className="text-right">
                  <p className="text-[10px] md:text-xs font-semibold text-zinc-400 uppercase">Total Spending</p>
                  <p className="text-lg md:text-xl font-bold text-rose-600">{formatCurrency(reportTotalSpending)}</p>
                </div>
              </div>
              <div className="h-[250px] md:h-[300px] w-full">
                {reportTrendData.length > 0 ? (
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={reportTrendData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e4e4e7" />
                      <XAxis dataKey="name" axisLine={false} tickLine={false} tick={{ fill: '#71717a', fontSize: 12 }} dy={10} />
                      <YAxis axisLine={false} tickLine={false} tick={{ fill: '#71717a', fontSize: 12 }} tickFormatter={(value) => `₹${value}`} />
                      <Tooltip cursor={{ fill: '#f4f4f5', opacity: 0.4 }} content={<CustomReportBarTooltip />} />
                      <Area 
                        type="monotone" 
                        dataKey="value" 
                        stroke="#f43f5e" 
                        strokeWidth={3}
                        fill="url(#expenseGradient)" 
                        animationDuration={1500}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-full flex flex-col items-center justify-center text-zinc-400">
                    <BarChart3 size={48} strokeWidth={1} className="mb-2" />
                    <p>No data for this period</p>
                  </div>
                )}
              </div>
            </div>

            <div className="p-6 md:p-8 rounded-3xl bg-white border border-zinc-200 shadow-sm">
              <h4 className="text-base md:text-lg font-bold flex items-center gap-2 mb-6 md:mb-8">
                <PieChartIcon size={20} className="text-indigo-600" />
                By Category
              </h4>
              <div className="h-[200px] md:h-[250px] w-full">
                {reportCategoryData.length > 0 ? (
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        activeIndex={reportPieIndex}
                        activeShape={renderActiveShape}
                        data={reportCategoryData}
                        cx="50%"
                        cy="50%"
                        innerRadius={60}
                        outerRadius={80}
                        paddingAngle={5}
                        dataKey="value"
                        onMouseEnter={(_, index) => setReportPieIndex(index)}
                      >
                        {reportCategoryData.map((entry, index) => (
                          <Cell key={`cell-${index}`} fill={categories.find(c => c.name === entry.name)?.color || '#94a3b8'} stroke="none" />
                        ))}
                      </Pie>
                      <Tooltip content={<CustomReportPieTooltip />} />
                    </PieChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-full flex flex-col items-center justify-center text-zinc-400 text-center">
                    <PieChartIcon size={48} strokeWidth={1} className="mb-2" />
                    <p>No category data</p>
                  </div>
                )}
              </div>
              <div className="mt-4 space-y-2">
                {reportCategoryData.slice(0, 4).map((item) => (
                  <div key={item.name} className="flex items-center justify-between text-sm">
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 rounded-full" style={{ backgroundColor: categoryColorMap[item.name] }} />
                      <span className="text-zinc-600">{item.name}</span>
                    </div>
                    <span className="font-bold text-zinc-900">{formatCurrency(item.value)}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </motion.div>
      ) : null}

      {/* Floating Action Button */}
      <div className="fixed bottom-20 md:bottom-8 right-4 md:right-8 flex flex-col gap-4 z-40">
        <button
          onClick={() => setShowSMSModal(true)}
          className="p-3 md:p-4 bg-white text-indigo-600 rounded-2xl shadow-xl border border-indigo-100 hover:bg-zinc-50 transition-all hover:scale-110 active:scale-95 flex items-center gap-2 font-medium"
        >
          <Search size={20} />
          <span className="hidden md:inline">Import SMS</span>
        </button>
        <button
          onClick={() => setShowAddModal(true)}
          className="p-3 md:p-4 bg-indigo-600 text-white rounded-2xl shadow-2xl shadow-indigo-300 hover:bg-indigo-700 transition-all hover:scale-110 active:scale-95 flex items-center justify-center"
        >
          <Plus size={24} />
        </button>
      </div>

      {/* Add Category Modal */}
      <AnimatePresence>
        {showAddCategoryModal && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setShowAddCategoryModal(false)}
              className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm"
            />
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              className="relative w-full max-w-md bg-white rounded-3xl shadow-2xl overflow-hidden"
            >
              <div className="p-6 border-b border-zinc-100 flex items-center justify-between">
                <h3 className="text-xl font-bold">Add Category</h3>
                <button 
                  onClick={() => setShowAddCategoryModal(false)}
                  className="p-2 hover:bg-zinc-100 rounded-xl transition-colors"
                >
                  <X size={20} />
                </button>
              </div>
              
              <form onSubmit={handleAddCategory} className="p-6 space-y-4">
                <div className="flex p-1 bg-zinc-100 rounded-xl">
                  <button
                    type="button"
                    onClick={() => setCategoryFormData({ ...categoryFormData, type: 'expense' })}
                    className={cn(
                      "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                      categoryFormData.type === 'expense' ? "bg-white shadow-sm text-rose-600" : "text-zinc-500"
                    )}
                  >
                    Expense
                  </button>
                  <button
                    type="button"
                    onClick={() => setCategoryFormData({ ...categoryFormData, type: 'income' })}
                    className={cn(
                      "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                      categoryFormData.type === 'income' ? "bg-white shadow-sm text-emerald-600" : "text-zinc-500"
                    )}
                  >
                    Income
                  </button>
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Category Name</label>
                  <input
                    required
                    type="text"
                    placeholder="e.g. Subscriptions, Side Hustle"
                    value={categoryFormData.name}
                    onChange={(e) => setCategoryFormData({ ...categoryFormData, name: e.target.value })}
                    className="w-full p-3 bg-zinc-50 border border-zinc-200 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                  />
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Color</label>
                  <div className="flex flex-wrap gap-2 p-2 bg-zinc-50 border border-zinc-200 rounded-2xl">
                    {['#f43f5e', '#ec4899', '#d946ef', '#a855f7', '#8b5cf6', '#6366f1', '#3b82f6', '#0ea5e9', '#06b6d4', '#14b8a6', '#10b981', '#22c55e', '#84cc16', '#eab308', '#f59e0b', '#f97316', '#ef4444', '#64748b'].map(color => (
                      <button
                        key={color}
                        type="button"
                        onClick={() => setCategoryFormData({ ...categoryFormData, color })}
                        className={cn(
                          "w-8 h-8 rounded-full border-2 transition-all",
                          categoryFormData.color === color ? "border-zinc-900 scale-110" : "border-transparent hover:scale-105"
                        )}
                        style={{ backgroundColor: color }}
                      />
                    ))}
                  </div>
                </div>

                <button
                  type="submit"
                  className="w-full py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all active:scale-[0.98] mt-4"
                >
                  Create Category
                </button>
              </form>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* SMS Import Modal */}
      <AnimatePresence>
        {showSMSModal && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setShowSMSModal(false)}
              className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm"
            />
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              className="relative w-full max-w-md bg-white rounded-3xl shadow-2xl overflow-hidden"
            >
              <div className="p-6 border-b border-zinc-100 flex items-center justify-between">
                <h3 className="text-xl font-bold">Import from SMS</h3>
                <button 
                  onClick={() => setShowSMSModal(false)}
                  className="p-2 hover:bg-zinc-100 rounded-xl transition-colors"
                >
                  <X size={20} />
                </button>
              </div>
              
              <div className="p-6 space-y-4">
                <p className="text-sm text-zinc-500">Paste the transaction SMS you received from your bank or service provider.</p>
                <textarea
                  value={smsText}
                  onChange={(e) => setSmsText(e.target.value)}
                  placeholder="e.g. Paid ₹500 to Starbucks on 2026-02-28..."
                  className="w-full h-32 p-4 bg-zinc-50 border border-zinc-200 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all resize-none"
                />
                <button
                  onClick={parseSMS}
                  disabled={isParsing || !smsText.trim()}
                  className="w-full py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                >
                  {isParsing ? (
                    <>
                      <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-white"></div>
                      Parsing with AI...
                    </>
                  ) : (
                    'Parse Transaction'
                  )}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Add Transaction Modal */}
      <AnimatePresence>
        {showAddModal && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setShowAddModal(false)}
              className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm"
            />
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              className="relative w-full max-w-md bg-white rounded-3xl shadow-2xl overflow-hidden"
            >
              <div className="p-6 border-b border-zinc-100 flex items-center justify-between">
                <h3 className="text-xl font-bold">Add Transaction</h3>
                <button 
                  onClick={() => setShowAddModal(false)}
                  className="p-2 hover:bg-zinc-100 rounded-xl transition-colors"
                >
                  <X size={20} />
                </button>
              </div>
              
              <form onSubmit={handleAddTransaction} className="p-6 space-y-4">
                <div className="flex p-1 bg-zinc-100 rounded-xl">
                  <button
                    type="button"
                    onClick={() => {
                      const firstExpenseCat = categories.find(c => c.type === 'expense')?.name || '';
                      setFormData({ ...formData, type: 'expense', category: firstExpenseCat });
                    }}
                    className={cn(
                      "flex-1 py-2 rounded-lg text-sm font-medium transition-all",
                      formData.type === 'expense' ? "bg-white shadow-sm text-rose-600" : "text-zinc-500"
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
                      formData.type === 'income' ? "bg-white shadow-sm text-emerald-600" : "text-zinc-500"
                    )}
                  >
                    Income
                  </button>
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">
                    {formData.type === 'income' ? 'Source' : 'Description'}
                  </label>
                  <input
                    required
                    type="text"
                    placeholder={formData.type === 'income' ? 'e.g., Employer, Client Name' : 'What was it for?'}
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    className="w-full p-3 bg-zinc-50 border border-zinc-200 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                  />
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-1">
                    <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Amount</label>
                    <div className="relative">
                      <span className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-400">₹</span>
                      <input
                        required
                        type="number"
                        step="1"
                        placeholder="0"
                        value={formData.amount}
                        onChange={(e) => setFormData({ ...formData, amount: e.target.value })}
                        className="w-full p-3 pl-7 bg-zinc-50 border border-zinc-200 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                      />
                    </div>
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Date</label>
                    <input
                      required
                      type="date"
                      value={formData.date}
                      onChange={(e) => setFormData({ ...formData, date: e.target.value })}
                      className="w-full p-3 bg-zinc-50 border border-zinc-200 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                    />
                  </div>
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-semibold text-zinc-400 uppercase ml-1">Category</label>
                  <select
                    value={formData.category}
                    onChange={(e) => setFormData({ ...formData, category: e.target.value })}
                    className="w-full p-3 bg-zinc-50 border border-zinc-200 rounded-2xl focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all appearance-none"
                  >
                    {categories.filter(c => c.type === formData.type).map((cat) => (
                      <option key={cat.id} value={cat.name}>{cat.name}</option>
                    ))}
                  </select>
                </div>

                <button
                  type="submit"
                  className="w-full py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all active:scale-[0.98] mt-4"
                >
                  Save Transaction
                </button>
              </form>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Chart Detail Modal */}
      <AnimatePresence>
        {selectedChartItem && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setSelectedChartItem(null)}
              className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm"
            />
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              className="relative w-full max-w-2xl bg-white rounded-3xl shadow-2xl overflow-hidden"
            >
              <div className="p-6 border-b border-zinc-100 flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold">
                    {selectedChartItem.type === 'category' ? `Category: ${selectedChartItem.value}` : `Month: ${selectedChartItem.value}`}
                  </h3>
                  <p className="text-sm text-zinc-500">Transactions for this selection</p>
                </div>
                <button 
                  onClick={() => setSelectedChartItem(null)}
                  className="p-2 hover:bg-zinc-100 rounded-xl transition-colors"
                >
                  <X size={20} />
                </button>
              </div>
              
              <div className="p-6 max-h-[60vh] overflow-y-auto">
                <div className="space-y-3">
                  {transactions
                    .filter(tx => {
                      if (selectedChartItem.type === 'category') {
                        return tx.category === selectedChartItem.value && tx.type === chartType;
                      } else {
                        return format(new Date(tx.date), 'MMM yyyy') === selectedChartItem.value;
                      }
                    })
                    .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
                    .map(tx => (
                      <div key={tx.id} className="flex items-center justify-between p-4 rounded-2xl bg-zinc-50">
                        <div className="flex items-center gap-4">
                          <div className={cn(
                            "p-2 rounded-lg",
                            tx.type === 'income' ? "bg-emerald-100 text-emerald-600" : "bg-rose-100 text-rose-600"
                          )}>
                            {tx.type === 'income' ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}
                          </div>
                          <div>
                            <p className="font-semibold text-zinc-900">{tx.description}</p>
                            <p className="text-xs text-zinc-500">{tx.category} • {format(new Date(tx.date), 'MMM d, yyyy')}</p>
                          </div>
                        </div>
                        <p className={cn(
                          "font-bold",
                          tx.type === 'income' ? "text-emerald-600" : "text-rose-600"
                        )}>
                          {tx.type === 'income' ? '+' : '-'}{formatCurrency(tx.amount)}
                        </p>
                      </div>
                    ))}
                </div>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Bottom Navigation for Mobile */}
      <div className="md:hidden fixed bottom-0 left-0 right-0 bg-white border-t border-zinc-200 px-4 py-2 flex justify-between items-center z-40 pb-safe">
        <button
          onClick={() => setActiveTab('dashboard')}
          className={cn(
            "flex flex-col items-center gap-1 p-2 rounded-xl transition-all",
            activeTab === 'dashboard' ? "text-indigo-600" : "text-zinc-400 hover:text-zinc-600"
          )}
        >
          <LayoutDashboard size={20} />
          <span className="text-[10px] font-medium">Home</span>
        </button>
        <button
          onClick={() => setActiveTab('history')}
          className={cn(
            "flex flex-col items-center gap-1 p-2 rounded-xl transition-all",
            activeTab === 'history' ? "text-indigo-600" : "text-zinc-400 hover:text-zinc-600"
          )}
        >
          <History size={20} />
          <span className="text-[10px] font-medium">History</span>
        </button>
        <button
          onClick={() => setActiveTab('budgets')}
          className={cn(
            "flex flex-col items-center gap-1 p-2 rounded-xl transition-all",
            activeTab === 'budgets' ? "text-indigo-600" : "text-zinc-400 hover:text-zinc-600"
          )}
        >
          <Target size={20} />
          <span className="text-[10px] font-medium">Budgets</span>
        </button>
        <button
          onClick={() => setActiveTab('categories')}
          className={cn(
            "flex flex-col items-center gap-1 p-2 rounded-xl transition-all",
            activeTab === 'categories' ? "text-indigo-600" : "text-zinc-400 hover:text-zinc-600"
          )}
        >
          <Tag size={20} />
          <span className="text-[10px] font-medium">Categories</span>
        </button>
        <button
          onClick={() => setActiveTab('reports')}
          className={cn(
            "flex flex-col items-center gap-1 p-2 rounded-xl transition-all",
            activeTab === 'reports' ? "text-indigo-600" : "text-zinc-400 hover:text-zinc-600"
          )}
        >
          <BarChart3 size={20} />
          <span className="text-[10px] font-medium">Reports</span>
        </button>
      </div>
    </div>
  );
}
