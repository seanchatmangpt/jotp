import type { Metadata } from 'next'
import './globals.css'
import { Theme } from "@radix-ui/themes"
import "@radix-ui/themes/styles.css"
import { DynamicToolbar } from "@/components/docs/dynamic-toolbar"
import { ThemeProvider } from "@/components/theme-provider"

export const metadata: Metadata = {
  title: 'JOTP - Java 26 Fault-Tolerant Primitives & Patterns',
  description: 'Interactive visualization dashboard for JOTP (Java OTP) - implementing Erlang/OTP fault tolerance patterns in Java 26 with virtual threads, supervision trees, and enterprise patterns.',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <ThemeProvider>
          <Theme appearance="dark" accentColor="blue" grayColor="slate" radius="medium">
            {children}
            <DynamicToolbar className="fixed bottom-6 right-6 z-50" />
          </Theme>
        </ThemeProvider>
      </body>
    </html>
  )
}
