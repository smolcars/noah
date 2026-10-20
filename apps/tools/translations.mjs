#!/usr/bin/env bun
// RN catalogs are read-only. GT extracts its own source offline into a temporary directory.
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, readdir, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const locales = ['en', 'es', 'ja', 'ko', 'sv'];
export const resourceName = source => 'gt_' + createHash('sha256').update(source).digest('hex').slice(0, 16);
const json = value => JSON.stringify(value, null, 2) + '\n';
const variablePattern = /\{([\w]+)\}/g;
export const variables = text => [...new Set([...text.matchAll(variablePattern)].map(match => match[1]))];

export function flatten(node, category = 'other') {
  if (typeof node === 'string') return node;
  if (node == null) return '';
  if (Array.isArray(node)) return node.map(child => flatten(child, category)).join('');
  if (node.v === 'v') return `{${node.k}}`;
  if (node.d?.t === 'p') return flatten(node.d.b[category] ?? node.d.b.other, category);
  if ('c' in node) return flatten(node.c, category);
  throw new Error(`Unsupported GT node: ${JSON.stringify(node)}`);
}
function plural(node) {
  if (!node || typeof node !== 'object') return null;
  if (node.d?.t === 'p') return node.d.b;
  for (const child of Array.isArray(node) ? node : Object.values(node)) {
    const found = plural(child);
    if (found) return found;
  }
  return null;
}
export function format(text, names, platform, quantity) {
  return text.replaceAll('%', '%%').replace(variablePattern, (_, name) => {
    const index = names.indexOf(name);
    if (index < 0) throw new Error(`Unknown placeholder ${name}: ${text}`);
    return `%${index + 1}$${name === quantity ? (platform === 'ios' ? 'lld' : 'd') : (platform === 'ios' ? '@' : 's')}`;
  });
}
export async function extract() {
  const temporary = await mkdtemp(resolve(tmpdir(), 'noah-native-translations-'));
  try {
    const config = JSON.parse(await readFile(resolve(root, 'client/gt.config.json'), 'utf8'));
    const configPath = resolve(temporary, 'gt.config.json');
    await writeFile(configPath, json({ ...config, src: config.src.map(path => resolve(root, 'client', path)), files: { gt: { output: resolve(temporary, '[locale].json') } } }));
    const cli = resolve(root, 'node_modules/gt/bin/main.js');
    const process = Bun.spawn([Bun.argv[0], cli, 'generate', '--config', configPath, '--omit-config-ids'], {
      cwd: resolve(root, 'client'), stdout: 'ignore', stderr: 'pipe',
    });
    const error = await new Response(process.stderr).text();
    if (await process.exited) throw new Error(error);
    const catalogs = { en: JSON.parse(await readFile(resolve(temporary, 'en.json'), 'utf8')) };
    for (const locale of locales.slice(1)) catalogs[locale] = JSON.parse(await readFile(resolve(root, `client/src/_gt/${locale}.json`), 'utf8'));
    return catalogs;
  } finally { await rm(temporary, { recursive: true, force: true }); }
}
export function entries(catalogs) {
  const result = new Map();
  // A source-keyed platform cannot distinguish GT context IDs. Lowest hash wins, consistently.
  for (const id of Object.keys(catalogs.en).sort()) {
    const source = flatten(catalogs.en[id]);
    const forms = plural(catalogs.en[id]);
    const names = variables(source);
    const quantity = forms ? variables(flatten(forms.other))[0] : undefined;
    if (forms && !quantity) throw new Error(`Plural without count: ${id}`);
    if (quantity) names.sort((a, b) => a === quantity ? -1 : b === quantity ? 1 : 0);
    const translations = {};
    for (const locale of locales) {
      const node = catalogs[locale]?.[id];
      if (node == null) throw new Error(`Missing ${locale} translation: ${id}`);
      const categories = forms ? [...new Set(['other', ...Object.keys(plural(node) ?? forms)])] : ['other'];
      translations[locale] = Object.fromEntries(categories.map(category => {
        const text = flatten(node, category);
        if (variables(text).some(name => !names.includes(name))) throw new Error(`Placeholder drift: ${locale}/${id}`);
        return [category, text];
      }));
    }
    if (!result.has(source)) result.set(source, { id, source, names, quantity, translations });
  }
  return [...result.values()].sort((a, b) => a.source < b.source ? -1 : a.source > b.source ? 1 : 0);
}
export async function filesBelow(directory, extension) {
  const files = [];
  for (const item of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, item.name);
    if (item.isDirectory()) files.push(...await filesBelow(path, extension));
    else if (path.endsWith(extension)) files.push(path);
  }
  return files.sort();
}
// Inventory literal UI copy and exact RN matches in native logic. Dynamic copy is explicitly
// localized at call sites; identifiers, URLs, SF Symbols and persistence keys are not copy.
export async function inventory(all) {
  const sources = new Set(all.map(entry => entry.source));
  const result = { ios: new Map(), android: new Map() };
  for (const [platform, directory, ext] of [['ios', 'apps/ios/Noah', '.swift'], ['android', 'apps/android/app/src/main/java/com/noahwallet', '.kt']]) {
    for (const file of await filesBelow(resolve(root, directory), ext)) {
      if (file.includes('/API/') || file.includes('/api/')) continue;
      const code = await readFile(file, 'utf8');
      for (const match of code.matchAll(/"((?:\\.|[^"\\\n])*)"/g)) {
        const raw = match[1];
        if (raw.includes('\\(') || raw.includes('${')) continue;
        let text;
        try { text = JSON.parse(`"${raw}"`); } catch { continue; }
        const uiFile = /\/(Features|DesignSystem|ui)\//.test(file) || /Screen\.kt$/.test(file);
        const context = code.slice(Math.max(0, match.index - 60), match.index);
        const uiLiteral = uiFile && /(?:Text|Button|Label|Section|TextField|navigationTitle|accessibilityLabel|row|SettingRow|SettingToggle|SectionTitle|NoahForm|NoahButton|settingsStyle|title:|detail:)\s*\(?[^\n]*$/.test(context);
        if (!text || (!sources.has(text) && !(uiLiteral && /[A-Za-z]/.test(text) && !/^[a-z]+[._-]/.test(text)))) continue;
        if (!result[platform].has(text)) result[platform].set(text, new Set());
        result[platform].get(text).add(file.slice(root.length + 1));
      }
    }
  }
  return result;
}
const xml = text => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('\\', '\\\\').replaceAll("'", "\\'").replaceAll('"', '\\"').replaceAll('\n', '\\n').replaceAll('\t', '\\t');
const unit = value => ({ stringUnit: { state: 'translated', value } });
export function iosCatalog(all, nativeOnly) {
  const strings = {};
  for (const entry of all) {
    strings[entry.source] = {
      comment: `GT ${entry.id}; arguments: ${entry.names.join(', ') || 'none'}`,
      extractionState: 'manual',
      localizations: Object.fromEntries(locales.map(locale => [locale, entry.quantity ? {
        variations: { plural: Object.fromEntries(Object.entries(entry.translations[locale]).map(([category, text]) => [category, unit(format(text, entry.names, 'ios', entry.quantity))])) },
      } : unit(format(entry.translations[locale].other, entry.names, 'ios'))])),
    };
  }
  for (const text of nativeOnly) strings[text] = { comment: 'Native-only copy; no RN translation. Intentionally English.', extractionState: 'manual', localizations: { en: unit(text) } };
  return json({ sourceLanguage: 'en', strings: Object.fromEntries(Object.entries(strings).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)), version: '1.0' });
}
export function androidCatalog(all, locale) {
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Generated by apps/tools/translations.mjs. Do not edit. -->', '<resources>'];
  for (const entry of all) {
    const name = resourceName(entry.source);
    if (entry.quantity) {
      lines.push(`  <plurals name="${name}">`);
      for (const [category, text] of Object.entries(entry.translations[locale])) lines.push(`    <item quantity="${category}">"${xml(format(text, entry.names, 'android', entry.quantity))}"</item>`);
      lines.push('  </plurals>');
    } else lines.push(`  <string name="${name}">"${xml(format(entry.translations[locale].other, entry.names, 'android'))}"</string>`);
  }
  // Existing static resource callers (tabs/launch gate) retain their IDs.
  if (locale !== 'en') for (const [name, source] of Object.entries({ home: 'Home', receive: 'Receive', send: 'Send', history: 'History', launch_welcome: 'Welcome to Noah' })) {
    const entry = all.find(entry => entry.source === source);
    if (entry) lines.push(`  <string name="${name}">"${xml(entry.translations[locale].other)}"</string>`);
  }
  return lines.concat('</resources>', '').join('\n');
}
export async function generate(check = false) {
  const all = entries(await extract());
  const used = await inventory(all);
  const known = new Set(all.map(entry => entry.source));
  const nativeOnly = platform => [...used[platform].keys()].filter(source => !known.has(source)).sort();
  const outputs = new Map();
  outputs.set('apps/ios/Noah/Resources/Localizable.xcstrings', iosCatalog(all, nativeOnly('ios')));
  for (const locale of locales) outputs.set(`apps/android/app/src/main/res/values${locale === 'en' ? '' : '-' + locale}/strings.xml`, androidCatalog(all, locale));
  const coverage = Object.fromEntries(['ios', 'android'].map(platform => [platform, {
    rnTranslated: [...used[platform].keys()].filter(source => known.has(source)).length,
    nativeOnlyEnglish: nativeOnly(platform).length,
    nativeOnly: nativeOnly(platform),
    used: [...used[platform].keys()].filter(source => known.has(source)).sort(),
  }]));
  outputs.set('apps/fixtures/translations-coverage.json', json({ locales, catalogEntries: all.length, coverage }));
  const stale = [];
  for (const [file, content] of outputs) {
    const path = resolve(root, file);
    if (check) { if (await readFile(path, 'utf8').catch(() => null) !== content) stale.push(file); }
    else { await mkdir(dirname(path), { recursive: true }); await writeFile(path, content); }
  }
  if (stale.length) throw new Error(`Stale native translations. Run bun apps/tools/translations.mjs:\n${stale.join('\n')}`);
  console.log(JSON.stringify({ catalogEntries: all.length, coverage: Object.fromEntries(Object.entries(coverage).map(([platform, value]) => [platform, { translated: value.rnTranslated, nativeOnlyEnglish: value.nativeOnlyEnglish }])) }));
}
if (import.meta.main) await generate(Bun.argv.includes('--check'));
