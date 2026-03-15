'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { Bell, ChevronRight, Menu } from 'lucide-react';
import { ThemeToggle } from '@/components/theme-toggle';

interface HeaderProps {
  onMenuClick?: () => void;
}

export default function Header({ onMenuClick }: HeaderProps) {
  const pathname = usePathname();
  const [showUserMenu, setShowUserMenu] = useState(false);

  const getBreadcrumbs = () => {
    const segments = pathname.split('/').filter(Boolean);
    return segments.map((segment, index) => {
      const path = '/' + segments.slice(0, index + 1).join('/');
      const label = segment.charAt(0).toUpperCase() + segment.slice(1).replace(/-/g, ' ');
      return { path, label };
    });
  };

  const breadcrumbs = getBreadcrumbs();

  return (
    <header
      className="fixed top-0 left-0 right-0 h-16 z-30"
      style={{
        backgroundColor: 'hsl(var(--background))',
        borderBottom: '1px solid hsl(var(--border))',
      }}
    >
      <div className="flex h-full px-4 justify-between items-center">
        {/* Left Section: Menu Toggle + Breadcrumbs */}
        <div className="flex items-center gap-4 flex-1">
          <Button
            variant="ghost"
            size="icon"
            onClick={onMenuClick}
            className="md:hidden"
            aria-label="Toggle menu"
          >
            <Menu className="w-5 h-5" />
          </Button>

          {/* Logo */}
          <Link href="/" className="no-underline">
            <div className="flex items-center gap-2">
              <div
                className="w-8 h-8 rounded-lg flex items-center justify-center shadow-md"
                style={{
                  background: 'linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%)',
                }}
              >
                <span className="text-white font-bold text-sm">JOTP</span>
              </div>
              <h1 className="text-xl font-semibold hidden sm:block">
                Benchmark Dashboard
              </h1>
            </div>
          </Link>

          {/* Breadcrumbs */}
          <div className="hidden md:flex items-center gap-2 ml-8">
            {breadcrumbs.map((crumb, index) => (
              <div key={crumb.path} className="flex items-center gap-2">
                {index > 0 && (
                  <ChevronRight className="w-4 h-4" style={{ color: '#6b7280' }} />
                )}
                <Link
                  href={crumb.path}
                  className="text-sm text-gray-600 hover:text-gray-900 transition-colors no-underline"
                >
                  {crumb.label}
                </Link>
              </div>
            ))}
          </div>
        </div>

        {/* Right Section: Theme Toggle + User Menu */}
        <div className="flex items-center gap-3">
          {/* Theme Toggle - uses shadcn Theme */}
          <ThemeToggle />

          {/* Notifications */}
          <Button
            variant="ghost"
            size="icon"
            className="relative"
          >
            <Bell className="w-5 h-5" />
            <div
              className="absolute top-1 right-1 w-2 h-2 bg-red-700 rounded-full"
            />
          </Button>

          {/* User Menu */}
          <div className="relative">
            <Button
              variant="ghost"
              size="sm"
              className="h-8 px-2"
              onClick={() => setShowUserMenu(!showUserMenu)}
              className="relative group"
            >
              <div
                className="w-8 h-8 rounded-full flex items-center justify-center"
                style={{
                  background: 'linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%)',
                }}
              >
                <span className="text-white font-medium text-sm">U</span>
              </div>
              <ChevronRight className="w-4 h-4" style={{ color: '#6b7280' }} />
            </Button>

            {showUserMenu && (
              <div
                className="absolute right-0 top-full mt-2 w-48"
                style={{
                  backgroundColor: 'hsl(var(--background))',
                  border: '1px solid hsl(var(--border))',
                  borderRadius: '0.5rem',
                  boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                  padding: '0.5rem 0',
                  zIndex: 40,
                }}
              >
                <Link
                  href="/settings"
                  className="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 transition-colors no-underline"
                >
                  Settings
                </Link>
                <Link
                  href="/profile"
                  className="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 transition-colors no-underline"
                >
                  Profile
                </Link>
                <Separator className="my-2" />
                <button
                  className="w-full text-left px-4 py-2 text-sm text-red-700 hover:bg-gray-100 transition-colors"
                >
                  Sign Out
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}
