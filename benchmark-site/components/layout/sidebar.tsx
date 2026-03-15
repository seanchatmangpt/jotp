'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

interface NavItem {
  name: string;
  href: string;
  icon: string;
  children?: NavItem[];
}

const navigation: NavItem[] = [
  {
    name: 'Overview',
    href: '/dashboard',
    icon: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
  },
  {
    name: 'Benchmarks',
    href: '/dashboard/benchmarks',
    icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z',
    children: [
      { name: 'Throughput', href: '/dashboard/benchmarks/throughput', icon: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6' },
      { name: 'Latency', href: '/dashboard/benchmarks/latency', icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' },
      { name: 'Capacity', href: '/dashboard/benchmarks/capacity', icon: 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4' },
    ],
  },
  {
    name: 'Analysis',
    href: '/dashboard/analysis',
    icon: 'M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z',
    children: [
      { name: 'Root Cause', href: '/dashboard/analysis/root-cause', icon: 'M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z' },
      { name: 'Profiling', href: '/dashboard/analysis/profiling', icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z' },
      { name: 'Flame Graphs', href: '/dashboard/analysis/flamegraphs', icon: 'M17.657 18.657A8 8 0 016.343 7.343S7 9 9 10c0-2 .5-5 2.986-7C14 5 16.09 5.777 17.656 7.343A7.975 7.975 0 0120 13a7.975 7.975 0 01-2.343 5.657z' },
      { name: 'Memory', href: '/dashboard/analysis/memory', icon: 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4' },
    ],
  },
  {
    name: 'Regression Report',
    href: '/dashboard/regression-report',
    icon: 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z',
  },
  {
    name: 'Documentation',
    href: '/dashboard/docs',
    icon: 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253',
  },
];

interface SidebarProps {
  isOpen?: boolean;
  onClose?: () => void;
}

export default function Sidebar({ isOpen = true, onClose }: SidebarProps) {
  const pathname = usePathname();
  const router = useRouter();
  const [expandedItems, setExpandedItems] = useState<Set<string>>(new Set());

  const toggleExpanded = (name: string) => {
    setExpandedItems(prev => {
      const newSet = new Set(prev);
      if (newSet.has(name)) {
        newSet.delete(name);
      } else {
        newSet.add(name);
      }
      return newSet;
    });
  };

  const isActive = (href: string) => {
    if (href === '/dashboard') {
      return pathname === href;
    }
    return pathname.startsWith(href);
  };

  const NavItemComponent = ({ item, level = 0 }: { item: NavItem; level?: number }) => {
    const hasChildren = item.children && item.children.length > 0;
    const isExpanded = expandedItems.has(item.name);
    const active = isActive(item.href);

    return (
      <div className={`flex flex-col ${level > 0 ? 'ml-4' : ''}`}>
        <Link
          href={item.href}
          onClick={(e) => {
            if (hasChildren) {
              e.preventDefault();
              toggleExpanded(item.name);
            } else {
              onClose?.();
            }
          }}
          className={cn(
            'flex items-center justify-between w-full px-4 py-3 rounded-lg',
            'text-gray-900 hover:bg-gray-100 no-underline transition-all duration-200',
            active && 'bg-blue-100 text-blue-900',
            'group'
          )}
          onMouseEnter={(e) => {
            if (!active) {
              e.currentTarget.classList.add('bg-gray-100');
            }
          }}
          onMouseLeave={(e) => {
            if (!active) {
              e.currentTarget.classList.remove('bg-gray-100');
            }
          }}
        >
          <div className="flex items-center gap-3">
            <svg
              className="w-5 h-5 flex-shrink-0"
              style={{
                color: active ? '#2563eb' : '#6b7280'
              }}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={item.icon} />
            </svg>
            <span className="text-base font-medium">{item.name}</span>
          </div>
          {hasChildren && (
            <ChevronRight
              className="w-4 h-4 transition-transform duration-200"
              style={{
                transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)'
              }}
            />
          )}
        </Link>

        {hasChildren && isExpanded && (
          <div className="flex flex-col gap-1 mt-1">
            {item.children!.map(child => (
              <NavItemComponent key={child.href} item={child} level={level + 1} />
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <>
      {/* Mobile Overlay */}
      {isOpen && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 z-20 md:hidden"
          onClick={onClose}
        />
      )}

      {/* Sidebar */}
      <aside
        className="fixed top-0 left-0 h-screen w-full md:w-64 z-30 transform transition-transform duration-200 ease-in-out md:translate-x-0"
        style={{
          backgroundColor: 'hsl(var(--card))',
          borderRight: '1px solid hsl(var(--border))',
          transform: isOpen ? 'translateX(0)' : 'translateX(-100%)',
        }}
      >
        <div className="flex flex-col h-full">
          {/* Logo Section */}
          <div
            className="h-16 border-b border-gray-200"
            style={{
              padding: '0 1.5rem',
            }}
          >
            <Link href="/" className="no-underline">
              <div className="flex items-center gap-3">
                <div
                  className="w-10 h-10 rounded-lg flex items-center justify-center shadow-md"
                  style={{
                    background: 'linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%)',
                  }}
                >
                  <span className="text-white font-bold">JOTP</span>
                </div>
                <div className="flex flex-col">
                  <h2 className="text-2xl font-bold text-gray-900">JOTP</h2>
                  <span className="text-gray-600">Benchmark Suite</span>
                </div>
              </div>
            </Link>
          </div>

          {/* Navigation */}
          <div
            className="flex-1 overflow-y-auto p-4"
            style={{
              gap: '0.25rem'
            }}
          >
            {navigation.map(item => (
              <NavItemComponent key={item.href} item={item} />
            ))}
          </div>

          {/* Footer */}
          <div
            className="p-3"
            style={{
              borderTop: '1px solid hsl(var(--border))',
            }}
          >
            <div
              className="bg-gradient-to-r from-blue-100 to-purple-100 rounded-lg p-4"
            >
              <span className="text-gray-600 mb-2 block">
                Running JOTP v1.0.0
              </span>
              <div className="flex items-center gap-2">
                <div
                  className="w-2 h-2 bg-green-700 rounded-full animate-pulse"
                />
                <span className="text-sm font-medium text-gray-900">
                  All Systems Operational
                </span>
              </div>
            </div>
          </div>
        </div>
      </aside>
    </>
  );
}