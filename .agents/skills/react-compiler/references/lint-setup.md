# React Compiler Lint Setup

Use this reference when configuring ESLint for a React Compiler-enabled project.

## Checks

1. Inspect the repo's existing ESLint style before editing. Prefer the project's current flat config or legacy config shape.
2. Confirm `eslint-plugin-react-hooks` is installed at v6 or newer.
3. Use the plugin's recommended compiler-aware config when available.
4. Promote compiler-related rules from `warn` to `error` so CI fails when the compiler skips code.

## Flat Config Shape

Adapt names to the installed plugin and local config style:

```js
import reactHooks from "eslint-plugin-react-hooks";

export default [
  reactHooks.configs.flat.recommended,
  {
    plugins: {
      "react-hooks": reactHooks,
    },
    rules: {
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "error",
      "react-hooks/config": "error",
      "react-hooks/error-boundaries": "error",
      "react-hooks/purity": "error",
      "react-hooks/refs": "error",
      "react-hooks/globals": "error",
      "react-hooks/immutability": "error",
      "react-hooks/set-state-in-render": "error",
      "react-hooks/set-state-in-effect": "error",
      "react-hooks/static-components": "error",
      "react-hooks/use-memo": "error",
      "react-hooks/component-hook-factories": "error",
      "react-hooks/unsupported-syntax": "error",
      "react-hooks/incompatible-library": "error",
      "react-hooks/preserve-manual-memoization": "error",
    },
  },
];
```

If a rule name is not present in the installed plugin, do not invent a compatibility shim. Use the package's exported recommended config and promote the rules that exist locally.

## Audit Commands

Use the repo's command surface first, for example `just check`, `bun lint`, `npm run lint`, or framework-specific lint commands. Then grep for directives:

```bash
rg '"use no memo"|useMemo|useCallback|React\.memo|\bmemo\(' .
```

Count `"use no memo"` directives before and after large refactors so the performance health trend is visible.
