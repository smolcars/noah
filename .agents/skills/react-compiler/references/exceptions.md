# React Compiler Bailout Patterns

Use this reference after lint reports compiler skips or when reviewing code that looks risky for compiler analysis. Prefer the actual lint output when available.

## Common Patterns

### Mutating props, state, context, or hook values

Problem:

```tsx
function Profile({ user }) {
  user.name = user.name.trim();
  return <Text>{user.name}</Text>;
}
```

Fix: derive a new value instead of mutating the input.

### Reading or writing refs during render

Problem:

```tsx
function Player() {
  const ref = useRef(null);
  const node = ref.current;
  return <View ref={ref}>{node?.id}</View>;
}
```

Fix: read refs in effects, event handlers, or callback refs unless using a documented lazy-initialization pattern.

### Impure render logic

Problem:

```tsx
function Row() {
  analytics.track("rendered");
  return <View />;
}
```

Fix: move side effects to an effect or event handler.

### Dynamic component or hook factories

Problem:

```tsx
function Screen({ kind }) {
  const Dynamic = makeComponent(kind);
  return <Dynamic />;
}
```

Fix: define components and hooks statically at module scope, then select between stable references.

### Unsupported syntax or incompatible libraries

Problem areas include `eval`, `with`, mutation-heavy libraries, proxies with hidden side effects, or third-party hooks that violate React rules.

Fix: isolate the pattern behind a small boundary, replace the library, or add a narrowly-scoped `"use no memo"` directive with a TODO when no immediate fix is reasonable.

## Manual Memoization Triage

Classify each `useMemo`, `useCallback`, and `React.memo`:

- `ref-identity`: keep only for non-React consumers that retain or compare references.
- `expensive`: keep only for heavy non-render work and add a comment naming the cost.
- `effect-dep`: keep only when stability is required for effect correctness.
- `compiler-handles`: delete.

## `"use no memo"` Directives

Treat each directive as temporary. Record:

- the file and line,
- the bailout reason,
- the owner or TODO,
- the condition for removal.
