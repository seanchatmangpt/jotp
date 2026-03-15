# JOTP Benchmark Dashboard - Component Library Documentation

This documentation covers all shadcn/ui components installed and custom components created for the JOTP benchmark dashboard.

## Installation Summary

### Base shadcn/ui Components
The following shadcn/ui components have been successfully installed:

1. **button** - Interactive button component
2. **card** - Card container component
3. **table** - Table component for data display
4. **badge** - Badge component for status indicators
5. **tabs** - Tab navigation component
6. **tooltip** - Tooltip component with provider
7. **select** - Dropdown select component
8. **dialog** - Modal dialog component
9. **separator** - Visual separator component
10. **scroll-area** - Custom scrollable area

### Custom JOTP Components
Custom components built on top of shadcn/ui for JOTP-specific use cases:

1. **benchmark-card** - Metric display card with trend indicators
2. **status-badge** - Status indicator badge with icons
3. **metric-display** - Compact metric display component
4. **chart** - Recharts integration wrapper

---

## Base Components Reference

### Button
**Location:** `/components/ui/button.tsx`

**Usage:**
```tsx
import { Button } from "@/components/ui/button"

<Button variant="default">Default Button</Button>
<Button variant="destructive">Destructive</Button>
<Button variant="outline">Outline</Button>
<Button variant="secondary">Secondary</Button>
<Button variant="ghost">Ghost</Button>
<Button variant="link">Link</Button>
<Button size="sm">Small</Button>
<Button size="lg">Large</Button>
```

**Variants:** `default | destructive | outline | secondary | ghost | link`
**Sizes:** `default | sm | lg | icon`

---

### Card
**Location:** `/components/ui/card.tsx`

**Usage:**
```tsx
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from "@/components/ui/card"

<Card>
  <CardHeader>
    <CardTitle>Card Title</CardTitle>
    <CardDescription>Card description text</CardDescription>
  </CardHeader>
  <CardContent>
    <p>Card content goes here</p>
  </CardContent>
  <CardFooter>
    <Button>Action</Button>
  </CardFooter>
</Card>
```

**Components:**
- `Card` - Main container
- `CardHeader` - Header section
- `CardTitle` - Title text
- `CardDescription` - Description text
- `CardContent` - Main content area
- `CardFooter` - Footer section

---

### Table
**Location:** `/components/ui/table.tsx`

**Usage:**
```tsx
import { Table, TableHeader, TableRow, TableHead, TableBody, TableCell } from "@/components/ui/table"

<Table>
  <TableHeader>
    <TableRow>
      <TableHead>Name</TableHead>
      <TableHead>Status</TableHead>
      <TableHead>Value</TableHead>
    </TableRow>
  </TableHeader>
  <TableBody>
    <TableRow>
      <TableCell>Test 1</TableCell>
      <TableCell><StatusBadge status="pass" /></TableCell>
      <TableCell>1.23ms</TableCell>
    </TableRow>
  </TableBody>
</Table>
```

**Components:**
- `Table` - Root container
- `TableHeader` - Header section
- `TableBody` - Body section
- `TableFooter` - Footer section
- `TableRow` - Row container
- `TableHead` - Header cell
- `TableCell` - Data cell

---

### Badge
**Location:** `/components/ui/badge.tsx`

**Usage:**
```tsx
import { Badge } from "@/components/ui/badge"

<Badge>Default</Badge>
<Badge variant="secondary">Secondary</Badge>
<Badge variant="destructive">Destructive</Badge>
<Badge variant="outline">Outline</Badge>
```

**Variants:** `default | secondary | destructive | outline`

---

### Tabs
**Location:** `/components/ui/tabs.tsx`

**Usage:**
```tsx
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"

<Tabs defaultValue="tab1">
  <TabsList>
    <TabsTrigger value="tab1">Tab 1</TabsTrigger>
    <TabsTrigger value="tab2">Tab 2</TabsTrigger>
  </TabsList>
  <TabsContent value="tab1">
    Content for tab 1
  </TabsContent>
  <TabsContent value="tab2">
    Content for tab 2
  </TabsContent>
</Tabs>
```

**Components:**
- `Tabs` - Root container
- `TabsList` - List of triggers
- `TabsTrigger` - Individual tab trigger
- `TabsContent` - Tab content panel

---

### Tooltip
**Location:** `/components/ui/tooltip.tsx`

**Usage:**
```tsx
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"

<TooltipProvider>
  <Tooltip>
    <TooltipTrigger>Hover me</TooltipTrigger>
    <TooltipContent>
      <p>Tooltip content</p>
    </TooltipContent>
  </Tooltip>
</TooltipProvider>
```

**Note:** The app is already wrapped with `TooltipProvider` in `layout.tsx`

---

### Select
**Location:** `/components/ui/select.tsx`

**Usage:**
```tsx
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

<Select>
  <SelectTrigger>
    <SelectValue placeholder="Select option" />
  </SelectTrigger>
  <SelectContent>
    <SelectItem value="option1">Option 1</SelectItem>
    <SelectItem value="option2">Option 2</SelectItem>
  </SelectContent>
</Select>
```

**Components:**
- `Select` - Root container
- `SelectTrigger` - Trigger button
- `SelectValue` - Display value
- `SelectContent` - Dropdown content
- `SelectItem` - Individual option
- `SelectGroup` - Group items
- `SelectLabel` - Group label

---

### Dialog
**Location:** `/components/ui/dialog.tsx`

**Usage:**
```tsx
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"

<Dialog>
  <DialogTrigger asChild>
    <Button>Open Dialog</Button>
  </DialogTrigger>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>Dialog Title</DialogTitle>
      <DialogDescription>Dialog description</DialogDescription>
    </DialogHeader>
    <div>Dialog content</div>
  </DialogContent>
</Dialog>
```

**Components:**
- `Dialog` - Root container
- `DialogTrigger` - Trigger element
- `DialogContent` - Content panel
- `DialogHeader` - Header section
- `DialogTitle` - Title text
- `DialogDescription` - Description text
- `DialogFooter` - Footer section

---

### Separator
**Location:** `/components/ui/separator.tsx`

**Usage:**
```tsx
import { Separator } from "@/components/ui/separator"

<Separator />
<Separator orientation="vertical" />
```

**Props:**
- `orientation` - `horizontal | vertical` (default: horizontal)

---

### ScrollArea
**Location:** `/components/ui/scroll-area.tsx`

**Usage:**
```tsx
import { ScrollArea } from "@/components/ui/scroll-area"

<ScrollArea className="h-96">
  <div>
    Long content here...
  </div>
</ScrollArea>
```

---

## Custom JOTP Components

### BenchmarkCard
**Location:** `/components/ui/benchmark-card.tsx`

A specialized card component for displaying benchmark metrics with trend indicators and status badges.

**Usage:**
```tsx
import { BenchmarkCard } from "@/components/ui/benchmark-card"

<BenchmarkCard
  title="Average Response Time"
  description="Process spawn latency"
  value={1.23}
  unit="ms"
  trend="down"
  trendValue="-12%"
  status="pass"
/>
```

**Props:**
- `title` (required) - Card title
- `description` - Optional description
- `value` (required) - Main metric value
- `unit` - Unit of measurement
- `trend` - `"up" | "down" | "neutral"` - Trend direction
- `trendValue` - Trend percentage/text
- `status` - `"pass" | "fail" | "warning"` - Status indicator
- `footer` - Optional footer content
- `className` - Additional CSS classes

**Features:**
- Automatic trend icons (up/down/neutral arrows)
- Color-coded trends (green/red/gray)
- Status badges with icons
- JOTP-branded gradient background

---

### StatusBadge
**Location:** `/components/ui/status-badge.tsx`

A status indicator badge with automatic icons and color coding.

**Usage:**
```tsx
import { StatusBadge } from "@/components/ui/status-badge"

<StatusBadge status="pass" />
<StatusBadge status="fail" />
<StatusBadge status="warning" />
<StatusBadge status="info" />
```

**Props:**
- `status` (required) - `"pass" | "fail" | "warning" | "info"`
- `className` - Additional CSS classes

**Status Colors:**
- `pass` - Green with checkmark icon
- `fail` - Red with X icon
- `warning` - Yellow with warning triangle
- `info` - Blue with no icon

---

### MetricDisplay
**Location:** `/components/ui/metric-display.tsx`

A compact metric display component for showing values with optional trend indicators.

**Usage:**
```tsx
import { MetricDisplay } from "@/components/ui/metric-display"

<MetricDisplay
  label="Throughput"
  value={1000}
  unit="ops/sec"
  trend="up"
  trendValue="+5.3%"
  description="Operations per second"
/>
```

**Props:**
- `label` (required) - Metric label
- `value` (required) - Metric value
- `unit` - Unit of measurement
- `trend` - `"up" | "down" | "neutral"` - Trend direction
- `trendValue` - Trend percentage/text
- `description` - Additional description
- `className` - Additional CSS classes
- `showTrendIcon` - Show/hide trend icon (default: true)

**Features:**
- Compact layout for space-efficient displays
- Optional trend indicators
- Flexible sizing and styling

---

### Chart Components
**Location:** `/components/ui/chart.tsx`

Recharts integration with shadcn/ui styling.

**Usage:**
```tsx
import { ChartContainer, ChartTooltip, ChartTooltipContent } from "@/components/ui/chart"
import { BarChart, Bar, XAxis, YAxis } from "recharts"

const data = [
  { name: "Test A", value: 100 },
  { name: "Test B", value: 200 },
]

<ChartContainer config={{}}>
  <BarChart data={data}>
    <XAxis dataKey="name" />
    <YAxis />
    <ChartTooltip content={<ChartTooltipContent />} />
    <Bar dataKey="value" fill="hsl(var(--primary))" />
  </BarChart>
</ChartContainer>
```

**Components:**
- `ChartContainer` - Styled container for charts
- `ChartTooltip` - Recharts tooltip wrapper
- `ChartTooltipContent` - Custom styled tooltip content

---

## Theme Customization

### JOTP Brand Colors
The dashboard uses a custom JOTP green theme:

```css
--primary: 142 76% 36%; /* JOTP Green */
```

### Dark Mode Support
All components support dark mode automatically through CSS custom properties. The theme switches between light and dark modes based on system preferences or user selection.

### Custom Gradients
Two custom gradients are available:

- `.jotp-gradient` - Primary gradient for headers/emphasis
- `.jotp-card-gradient` - Subtle gradient for cards

### Custom Scrollbar
A custom-styled scrollbar matches the JOTP theme:

```css
/* Automatically applied to all scrollable areas */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-thumb {
  background: hsl(var(--primary));
  border-radius: 4px;
}
```

---

## Utilities

### cn() Function
**Location:** `/lib/utils.ts`

A utility function for conditionally combining Tailwind CSS classes.

```tsx
import { cn } from "@/lib/utils"

<div className={cn(
  "base-class",
  isActive && "active-class",
  "another-class"
)} />
```

This is used throughout all components for flexible styling.

---

## Best Practices

### 1. Component Composition
```tsx
// Good: Compose components
<Card>
  <CardHeader>
    <CardTitle>Title</CardTitle>
  </CardHeader>
  <CardContent>
    <BenchmarkCard title="Metric" value={123} />
  </CardContent>
</Card>

// Avoid: Inline styling
<div className="border rounded p-4 shadow">
  {/* Custom card implementation */}
</div>
```

### 2. Status Consistency
```tsx
// Use StatusBadge for consistent status display
<StatusBadge status="pass" />
<StatusBadge status="fail" />
<StatusBadge status="warning" />
```

### 3. Metric Display
```tsx
// Use BenchmarkCard for primary metrics
<BenchmarkCard
  title="Primary Metric"
  value={123}
  unit="ms"
  status="pass"
/>

// Use MetricDisplay for secondary/multiple metrics
<div className="grid grid-cols-2 gap-4">
  <MetricDisplay label="Metric 1" value={100} />
  <MetricDisplay label="Metric 2" value={200} />
</div>
```

### 4. Responsive Layouts
```tsx
// Use Tailwind responsive classes
<div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
  <BenchmarkCard title="Card 1" value={1} />
  <BenchmarkCard title="Card 2" value={2} />
  <BenchmarkCard title="Card 3" value={3} />
</div>
```

---

## Example Dashboard Layout

```tsx
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { BenchmarkCard } from "@/components/ui/benchmark-card"
import { StatusBadge } from "@/components/ui/status-badge"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

export default function Dashboard() {
  return (
    <div className="container mx-auto p-6 space-y-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <h1 className="text-3xl font-bold">JOTP Benchmark Dashboard</h1>
        <StatusBadge status="pass" />
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <BenchmarkCard
          title="Response Time"
          value={1.23}
          unit="ms"
          trend="down"
          trendValue="-5%"
          status="pass"
        />
        <BenchmarkCard
          title="Throughput"
          value={1000}
          unit="ops/sec"
          trend="up"
          trendValue="+10%"
          status="pass"
        />
        <BenchmarkCard
          title="Error Rate"
          value={0.01}
          unit="%"
          trend="neutral"
          status="pass"
        />
        <BenchmarkCard
          title="Memory Usage"
          value={256}
          unit="MB"
          trend="up"
          trendValue="+2%"
          status="warning"
        />
      </div>

      {/* Tabs */}
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="details">Details</TabsTrigger>
          <TabsTrigger value="history">History</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <Card>
            <CardHeader>
              <CardTitle>Performance Overview</CardTitle>
            </CardHeader>
            <CardContent>
              {/* Chart content */}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="details">
          <Card>
            <CardHeader>
              <CardTitle>Detailed Metrics</CardTitle>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Test</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Value</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow>
                    <TableCell>Spawn Test</TableCell>
                    <TableCell><StatusBadge status="pass" /></TableCell>
                    <TableCell>1.23ms</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
```

---

## Component Styling Reference

### CSS Custom Properties

#### Light Mode
```css
--background: 0 0% 100%              /* White background */
--foreground: 240 10% 3.9%           /* Dark text */
--primary: 142 76% 36%               /* JOTP Green */
--primary-foreground: 355.7 100% 97.3% /* White text on primary */
```

#### Dark Mode
```css
--background: 240 10% 3.9%           /* Dark background */
--foreground: 0 0% 98%               /* Light text */
--primary: 142 76% 36%               /* JOTP Green (same) */
--primary-foreground: 355.7 100% 97.3% /* White text */
```

---

## Migration Notes

### Adding New Components
To add new shadcn/ui components:

```bash
npx shadcn@latest add [component-name]
```

### Customizing Components
1. Copy the component from `/components/ui/`
2. Modify as needed
3. Update this documentation

### Theme Updates
Edit `/app/globals.css` to modify:
- Color scheme
- Custom gradients
- Scrollbar styling
- Base typography

---

## Support

For issues or questions:
1. Check the [shadcn/ui documentation](https://ui.shadcn.com/)
2. Review component source files in `/components/ui/`
3. Consult this documentation for usage examples

---

**Last Updated:** 2025-03-14
**Version:** 1.0.0
**Framework:** Next.js 15.1.0 with shadcn/ui
