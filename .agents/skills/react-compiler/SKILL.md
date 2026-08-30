---
name: react-compiler
description: Write and review React code for React 19+ codebases with the React Compiler enabled. Use when writing or refactoring React components, reviewing React PRs, setting up eslint-plugin-react-hooks compiler lint rules, or auditing manual memoization patterns such as useMemo, useCallback, and React.memo.
---

# React Compiler

## Rule

The compiler memoizes. Do not add manual memoization by default.

In React 19+ codebases with the compiler enabled, write components plainly and skip `useMemo`, `useCallback`, and `React.memo` unless a concrete exception applies. The compiler memoizes at finer granularity than hand-written wrappers, and manual memoization is usually noise.

## Default Behavior

Write plain React:

```tsx
function DashboardRow({ entity, onSelect }) {
  const label = formatLabel(entity);
  const total = entity.items.reduce((sum, item) => sum + item.value, 0);

  return (
    <Row onClick={() => onSelect(entity.id)}>
      <Label>{label}</Label>
      <Total>{total}</Total>
    </Row>
  );
}
```

Do not add this by default:

```tsx
const DashboardRow = memo(({ entity, onSelect }) => {
  const label = useMemo(() => formatLabel(entity), [entity]);
  const handleClick = useCallback(() => onSelect(entity.id), [entity.id, onSelect]);
  return null;
});
```

## Exceptions

Keep `useMemo`, `useCallback`, or `React.memo` only when one of these applies:

1. Referential identity is required by a non-React consumer, such as `addEventListener`, `IntersectionObserver`, or a third-party library that retains references or uses `===`.
2. Expensive non-render work would be repeated, such as parsing a large blob, building a large index, or calling a heavy library. Keep it and add a short comment explaining the cost.
3. Effect dependencies must be stable for correctness. Keep the wrapper if removing it would cause an effect to re-run incorrectly.

Delete manual memoization outside these exceptions.

## Audit Workflow

1. Confirm the compiler is enabled before removing memoization. Check framework config such as `next.config`, `vite.config`, `babel.config`, Expo config, or framework defaults.
2. Confirm `eslint-plugin-react-hooks` is v6 or newer before relying on compiler lint rules.
3. Promote compiler-related lint rules to `error`, because warning-only rules can hide compiler skips from CI. Read `references/lint-setup.md` when changing ESLint config.
4. Sweep `useMemo`, `useCallback`, and `React.memo`; delete instances that do not match an exception.
5. Run lint and fix compiler skips iteratively. One pass can reveal downstream issues after upstream skips are fixed.
6. Track `"use no memo"` directives. Treat each directive as a performance cliff that needs an associated TODO or issue.

## Silent Bails

When lint reports compiler skips or the code contains risky patterns, read `references/exceptions.md`. Prefer the lint finding over guessing: use the reference to classify the pattern and choose the smallest fix.

## Output Format for Audits

```text
file: <path>
findings:
  - removed: <useMemo | useCallback | memo> @ <line>, compiler handles
  - kept: <useMemo | useCallback> @ <line>, reason: <ref-identity | expensive | effect-dep>
silent bails detected:
  - <pattern> @ <line>: <one-line fix>
```

## Source

Based on "The React Compiler at Eighteen Months" (`react-compiler-year-in-review`) and follow-up guidance embedded in this skill's references.
