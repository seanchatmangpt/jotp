#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

// List of files to migrate
const files = [
  'app/page.tsx',
  'app/dashboard/page.tsx',
  'app/dashboard/benchmarks/page.tsx',
  'app/dashboard/analysis/page.tsx',
  'app/dashboard/regression-report/page.tsx',
  'app/dashboard/docs/page.tsx',
  'app/docs/[...slug]/page.tsx',
  'components/flows/messaging/wire-tap-flow.tsx',
  'components/flows/messaging/content-based-router-flow.tsx',
  'components/flows/messaging/point-to-point-flow.tsx',
  'components/flows/messaging/aggregator-flow.tsx',
  'components/flows/messaging/publish-subscribe-flow.tsx',
  'components/flows/messaging/scatter-gather-flow.tsx',
  'components/flows/patterns/saga-flow.tsx',
  'components/flows/patterns/recovery-flow.tsx',
  'components/flows/patterns/multi-tenant-flow.tsx',
  'components/flows/patterns/health-check-flow.tsx',
  'components/flows/patterns/event-bus-flow.tsx',
  'components/flows/patterns/circuit-breaker-flow.tsx',
  'components/flows/patterns/bulkhead-flow.tsx',
  'components/flows/patterns/backpressure-flow.tsx',
  'components/docs/related-content.tsx',
  'components/docs/code-block.tsx',
  'components/docs/ai-assistant.tsx',
  'components/docs/export-menu.tsx',
  'components/docs/keyboard-shortcuts.tsx',
  'components/docs/diff-viewer.tsx',
  'components/docs/search-trigger.tsx',
  'components/docs/collapsible-section.tsx',
  'components/docs/try-button.tsx',
  'components/docs/dynamic-toolbar.tsx',
  'components/docs/next-prev-nav.tsx',
  'components/primitive/primitive-detail.tsx',
  'components/primitive/relationship-graph.tsx',
  'components/primitive/api-reference.tsx',
];

console.log('Migrating Card imports...');

files.forEach(file => {
  const filePath = path.resolve(file);
  if (fs.existsSync(filePath)) {
    let content = fs.readFileSync(filePath, 'utf8');

    // Replace Card import
    content = content.replace(
      /import\s+{\s*Card\s*}.*from\s+['"]@radix-ui\/themes['"];?/g,
      "import { Card } from '@/components/ui/card'"
    );

    // Replace Card usage with proper className
    content = content.replace(
      /<Card>/g,
      '<Card className="p-6">'
    );

    // Add closing tag if it's self-closing
    content = content.replace(
      /<Card\s*\/>/g,
      '<Card className="p-6" />'
    );

    fs.writeFileSync(filePath, content);
    console.log(`✓ Updated: ${file}`);
  } else {
    console.log(`✗ Not found: ${file}`);
  }
});

console.log('\nCard import migration complete!');