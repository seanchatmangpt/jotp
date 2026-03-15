'use client';

import { useState } from 'react';
import { Box, Flex } from "@radix-ui/themes";
import Header from '@/components/layout/header';
import Sidebar from '@/components/layout/sidebar';

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <Box minH="100vh" className="bg-gray-50 dark:bg-gray-900">
      <Sidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <Header onMenuClick={() => setSidebarOpen(!sidebarOpen)} />

      {/* Main Content */}
      <Box pt="16" className="md:pl-64">
        <Box p="6">
          {children}
        </Box>
      </Box>
    </Box>
  );
}
