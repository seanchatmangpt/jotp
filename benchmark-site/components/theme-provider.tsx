'use client';

import * as React from 'react';
import { ThemeProvider as NextThemesProvider } from 'next-themes';
import type { ThemeProviderProps } from 'next-themes/dist/types';

interface ThemeProviderProps {
  children: React.ReactNode;
  defaultTheme?: 'light' | 'dark';
}

// Context for theme switching
const ThemeSwitchContext = React.createContext<{
  theme: string | undefined;
  setTheme: (theme: string | undefined) => void;
}>({
  theme: undefined,
  setTheme: () => {}
});

/**
 * Hook to access and switch themes
 */
export function useTheme() {
  return React.useContext(ThemeSwitchContext);
}

/**
 * Theme Provider using next-themes
 *
 * - Dark mode is set as default
 * - System theme detection enabled
 * - Theme preference is persisted to localStorage
 */
export function ThemeProvider({
  children,
  defaultTheme = 'dark'
}: ThemeProviderProps) {
  return (
    <NextThemesProvider
      attribute="class"
      defaultTheme={defaultTheme}
      enableSystem
      disableTransitionOnChange
    >
      {children}
    </NextThemesProvider>
  );
}
