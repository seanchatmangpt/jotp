# Required Dependencies for Real-Time Integration

## Installation Commands

Run these commands in the benchmark-site directory:

```bash
# Navigate to benchmark-site
cd /Users/sac/jotp/benchmark-site

# Install required dependencies
npm install xstate@5.28.0 @xstate/react framer-motion

# Install if not already present
npm install --save-dev @types/node
```

## package.json Updates

Add these to your `package.json`:

```json
{
  "dependencies": {
    "xstate": "5.28.0",
    "@xstate/react": "^4.1.3",
    "framer-motion": "^11.0.0",
    "react": "^18.3.0",
    "next": "^15.0.0"
  },
  "devDependencies": {
    "@types/node": "^20.0.0",
    "@types/react": "^18.3.0",
    "typescript": "^5.3.0"
  }
}
```

## Dependency Details

### XState v5.28.0
- **Purpose**: State machine management
- **Why this version**: Latest stable with full TypeScript support
- **Required**: Yes (exact version specified)

### @xstate/react
- **Purpose**: React integration for XState
- **Version**: Latest compatible with XState 5.28.0
- **Required**: Yes

### framer-motion
- **Purpose**: Animation library for UI components
- **Version**: Latest v11
- **Required**: Yes

## Verification

Verify installation:

```bash
# Check XState version
npm list xstate

# Check all dependencies
npm list --depth=0

# TypeScript compilation check
npx tsc --noEmit
```

## Common Issues

### Issue: "Cannot find module 'xstate'"
**Solution:**
```bash
npm install xstate@5.28.0
```

### Issue: "Cannot find module '@xstate/react'"
**Solution:**
```bash
npm install @xstate/react
```

### Issue: "Cannot find module 'framer-motion'"
**Solution:**
```bash
npm install framer-motion
```

### Issue: TypeScript errors
**Solution:**
```bash
npm install --save-dev @types/node @types/react
```

## Development Server

Start the development server after installation:

```bash
npm run dev
```

Access the application at: `http://localhost:3000`

## Production Build

Build for production:

```bash
npm run build
npm start
```

## Environment Variables

No additional environment variables required for the mock endpoints.

For production with real WebSocket server:

```env
# .env.local
NEXT_PUBLIC_WS_URL=ws://localhost:8080/benchmarks
NEXT_PUBLIC_API_URL=http://localhost:8080/api
```

## TypeScript Configuration

Ensure your `tsconfig.json` includes:

```json
{
  "compilerOptions": {
    "strict": true,
    "target": "ES2020",
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "jsx": "preserve",
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "incremental": true,
    "plugins": [
      {
        "name": "next"
      }
    ],
    "paths": {
      "@/*": ["./*"]
    }
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx", ".next/types/**/*.ts"],
  "exclude": ["node_modules"]
}
```

## Next Steps

After installing dependencies:

1. **Review the integration guide**: `docs/REALTIME-INTEGRATION.md`
2. **Follow the setup guide**: `docs/SETUP-GUIDE.md`
3. **Check the summary**: `docs/REALTIME-INTEGRATION-SUMMARY.md`
4. **Run the development server**: `npm run dev`
5. **View the example flow**: Navigate to `/flows` (create this page)

## Support

For issues or questions:

- Check the documentation in `docs/`
- Review the example code in `components/flows/`
- Examine the state machines in `lib/state-machines/`
- Test the API endpoints in `app/api/`
