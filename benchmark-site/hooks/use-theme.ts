'use client';

import * as React from 'react';
import { useTheme as useNextTheme } from 'next-themes';
import { useTheme as useProviderTheme } from '@/components/theme-provider';

/**
 * Hook to access and switch themes with next-themes
 */
export function useTheme() {
  const nextTheme = useNextTheme();
  const providerTheme = useProviderTheme();

  return {
    theme: nextTheme.theme,
    setTheme: nextTheme.setTheme,
    resolvedTheme: nextTheme.resolvedTheme
  };
}