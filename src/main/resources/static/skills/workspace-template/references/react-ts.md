# React + TypeScript Scaffolding Reference

## Directory Structure

```
{name}/
├── package.json
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.node.json
├── vite.config.ts
├── index.html
├── eslint.config.js
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── App.css
│   ├── index.css
│   ├── vite-env.d.ts
│   └── components/
│       └── .gitkeep
├── public/
│   └── .gitkeep
├── .gitignore
├── CLAUDE.md
└── README.md
```

---

## File Templates

### package.json

```json
{
  "name": "{name}",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "lint": "eslint .",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "react": "^19.1.0",
    "react-dom": "^19.1.0"
  },
  "devDependencies": {
    "@eslint/js": "^9.22.0",
    "@testing-library/jest-dom": "^6.6.3",
    "@testing-library/react": "^16.3.0",
    "@types/react": "^19.1.0",
    "@types/react-dom": "^19.1.0",
    "@vitejs/plugin-react": "^4.4.1",
    "eslint": "^9.22.0",
    "eslint-plugin-react-hooks": "^5.2.0",
    "eslint-plugin-react-refresh": "^0.4.19",
    "globals": "^16.0.0",
    "jsdom": "^26.0.0",
    "typescript": "~5.7.0",
    "typescript-eslint": "^8.26.0",
    "vite": "^6.3.0",
    "vitest": "^3.1.0"
  }
}
```

**If the project needs routing:**

Add to dependencies:
```json
"react-router-dom": "^7.4.0"
```

**If the project needs state management:**

Add to dependencies:
```json
"zustand": "^5.0.0"
```

**If the project needs Tailwind CSS:**

Add to devDependencies:
```json
"@tailwindcss/vite": "^4.1.0",
"tailwindcss": "^4.1.0"
```

And add to `vite.config.ts`:
```typescript
import tailwindcss from '@tailwindcss/vite'
// add tailwindcss() to plugins array
```

And add to `src/index.css`:
```css
@import "tailwindcss";
```

### tsconfig.json

```json
{
  "files": [],
  "references": [
    { "path": "./tsconfig.app.json" },
    { "path": "./tsconfig.node.json" }
  ]
}
```

### tsconfig.app.json

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "moduleDetection": "force",
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "noUncheckedSideEffectImports": true
  },
  "include": ["src"]
}
```

### tsconfig.node.json

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.node.tsbuildinfo",
    "target": "ES2022",
    "lib": ["ES2023"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "moduleDetection": "force",
    "noEmit": true,
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "noUncheckedSideEffectImports": true
  },
  "include": ["vite.config.ts"]
}
```

### vite.config.ts

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
})
```

### index.html

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>{name}</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

### src/main.tsx

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

### src/App.tsx

```tsx
import './App.css'

function App() {
  return (
    <div>
      <h1>{name}</h1>
      <p>{description}</p>
    </div>
  )
}

export default App
```

### src/App.css

```css
#root {
  max-width: 1280px;
  margin: 0 auto;
  padding: 2rem;
}
```

### src/index.css

```css
:root {
  font-family: system-ui, -apple-system, sans-serif;
  line-height: 1.5;
  font-weight: 400;
  color: #213547;
  background-color: #ffffff;
}

@media (prefers-color-scheme: dark) {
  :root {
    color: rgba(255, 255, 255, 0.87);
    background-color: #242424;
  }
}
```

### src/vite-env.d.ts

```typescript
/// <reference types="vite/client" />
```

### eslint.config.js

```javascript
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
)
```

### .gitignore

```
# Dependencies
node_modules/

# Build
dist/
dist-ssr/

# IDE
.vscode/
.idea/
*.sw?

# OS
.DS_Store
Thumbs.db

# Environment
.env
.env.local

# ADE board
/.board/
```

### CLAUDE.md

```markdown
# CLAUDE.md — {name}

## Build Commands

\`\`\`bash
npm install          # install dependencies
npm run dev          # start dev server (http://localhost:5173)
npm run build        # production build
npm run test         # run tests
npm run lint         # lint code
\`\`\`

## Architecture

React 19 + TypeScript + Vite application.

### Key Conventions

- **Functional components only** — no class components
- **TypeScript strict mode** — no `any`, explicit return types on exports
- **Typed props** — use interfaces or type aliases for component props
- **Hooks** — `useState`, `useEffect`, `useCallback`, `useMemo` for state and side effects
- **Component files** — one component per file, PascalCase naming
- **CSS** — co-located CSS files or Tailwind utility classes

### Directory Structure

\`\`\`
src/
  components/   → Reusable UI components
  pages/        → Route-level page components (if using router)
  hooks/        → Custom hooks
  utils/        → Pure utility functions
  types/        → Shared TypeScript types
\`\`\`
```

### README.md

```markdown
# {name}

{description}

## Getting Started

\`\`\`bash
npm install
npm run dev
\`\`\`

Open http://localhost:5173 in your browser.

## Tech Stack

- React 19
- TypeScript
- Vite
- Vitest
```
