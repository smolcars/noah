import { test, expect } from 'bun:test';
import { flatten, format, entries, iosCatalog, androidCatalog, resourceName } from './translations.mjs';

const text = { t: 'Text', c: ['Hello ', { t: 'strong', c: { v: 'v', k: 'name' } }, '!'] };
test('rich text keeps text and variables in reading order', () => {
  expect(flatten(text)).toBe('Hello {name}!');
  expect(() => flatten({ unexpected: true })).toThrow('Unsupported GT node');
});
test('placeholder indices follow source order even when translations reorder them', () => {
  expect(format('{recipient}: {amount} (100%)', ['amount', 'recipient'], 'ios')).toBe('%2$@: %1$@ (100%%)');
  expect(format('{recipient}: {amount}', ['amount', 'recipient'], 'android')).toBe('%2$s: %1$s');
  expect(() => format('{unknown}', [], 'ios')).toThrow();
});
test('plural variants become native plural resources with a numeric argument', () => {
  const node = { t: 'Plural', d: { t: 'p', b: { one: 'One place', other: [{ v: 'v', k: 'count' }, ' places'] } } };
  const catalogs = Object.fromEntries(['en', 'es', 'ja', 'ko', 'sv'].map(locale => [locale, { id: node }]));
  const all = entries(catalogs);
  expect(all[0].quantity).toBe('count');
  const catalog = JSON.parse(iosCatalog(all, []));
  expect(catalog.strings['{count} places'].localizations.en.variations.plural.other.stringUnit.value).toBe('%1$lld places');
  expect(androidCatalog(all, 'en')).toContain('<item quantity="other">"%1$d places"</item>');
  expect(resourceName('Cancel')).toBe(resourceName('Cancel'));
});
test('missing translations and unknown variables fail generation', () => {
  expect(() => entries({ en: { id: 'Hello' } })).toThrow('Missing');
  const catalogs = Object.fromEntries(['en', 'es', 'ja', 'ko', 'sv'].map(locale => [locale, { id: locale === 'en' ? '{name}' : '{wrong}' }]));
  expect(() => entries(catalogs)).toThrow('Placeholder drift');
});

test('native-only templates and literal percent signs are safe', () => {
  const strings = JSON.parse(iosCatalog([], ['Payment to {recipient} was skipped.', '100% ready'])).strings;
  expect(strings['Payment to {recipient} was skipped.'].localizations.en.stringUnit.value).toBe('Payment to %1$@ was skipped.');
  expect(strings['100% ready'].localizations.en.stringUnit.value).toBe('100% ready');
  expect(androidCatalog([], 'en', ['100% ready'])).toContain('"100% ready"');
});

test('native inventory handles nested interpolation and ignores comments and raw regexes', async () => {
  const { nativeLiterals } = await import('./translations.mjs');
  expect(nativeLiterals('// "Ignore"\nText("Hello \\(name ?? "friend")")\nlet regex = #"["\\w]"#', true).map(item => item.text)).toEqual(['Hello {nativeValue1}']);
  expect(nativeLiterals('Text("Hello ${name ?: "friend"}") // "Ignore"\nval quote = \'"\'', false).map(item => item.text)).toEqual(['Hello {nativeValue1}']);
});

test('checked-in catalogs and every native RN key stay current in all five locales', async () => {
  const { generate } = await import('./translations.mjs');
  await generate(true);
}, 30_000);
