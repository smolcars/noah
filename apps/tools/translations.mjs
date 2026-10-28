#!/usr/bin/env bun
// Native English is extracted offline. Only translate contacts GT; seed optionally reads RN.
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
  return (names.length ? text.replaceAll('%', '%%') : text).replace(variablePattern, (_, name) => {
    const index = names.indexOf(name);
    if (index < 0) throw new Error(`Unknown placeholder ${name}: ${text}`);
    return `%${index + 1}$${platform === 'ios' ? (name === quantity ? 'lld' : '@') : 's'}`;
  });
}
export async function extractRN() {
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
        if (variables(text).some(name => !names.includes(name)) || (category === 'other' && names.some(name => !variables(text).includes(name)))) throw new Error(`Placeholder drift: ${locale}/${id}`);
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
// Skip comments and nested interpolations so native-only sentences are inventoried once,
// including strings whose interpolation expressions contain quoted strings of their own.
export function nativeLiterals(code, swift) {
  const output = [];
  function group(start) {
    const close = { '(': ')', '{': '}', '[': ']' }[code[start]];
    let i = start + 1;
    while (i < code.length) {
      if (!swift && code[i] === "'") { const char = /^'(?:\\.|[^'\\])*'/.exec(code.slice(i)); i += char?.[0].length ?? 1; }
      else if (code[i] === '"') i = quoted(i, false).end;
      else if ('({['.includes(code[i])) i = group(i);
      else if (code[i++] === close) return i;
    }
    throw new Error('Unclosed native interpolation');
  }
  function quoted(start, collect) {
    let i = start + 1, text = '', count = 0;
    while (i < code.length) {
      if (code.startsWith(swift ? '\\(' : '${', i)) {
        i = group(i + 1); text += `{nativeValue${++count}}`;
      } else if (!swift && /^\$\w/.test(code.slice(i, i + 2))) {
        const match = /^\$(\w+)/.exec(code.slice(i)); i += match[0].length;
        text += `{nativeValue${++count}}`;
      } else if (code[i] === '\\') { text += code.slice(i, i + 2); i += 2; }
      else if (code[i] === '"') {
        if (collect) {
          try {
            const decoded = JSON.parse(`"${text.replace(/\\u\{([0-9a-f]+)\}/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16))).replaceAll('\\$', '$')}"`);
            output.push({ text: decoded, index: start });
          } catch { /* Platform-only escape syntax is not UI copy. */ }
        }
        return { end: i + 1 };
      } else text += code[i++];
    }
    throw new Error('Unclosed native string');
  }
  let i = 0;
  while (i < code.length) {
    if (code.startsWith('//', i)) { const end = code.indexOf('\n', i); i = end < 0 ? code.length : end; }
    else if (code.startsWith('/*', i)) { const end = code.indexOf('*/', i + 2); i = end < 0 ? code.length : end + 2; }
    else if (swift && code.startsWith('#"', i)) { const end = code.indexOf('"#', i + 2); i = end < 0 ? code.length : end + 2; }
    else if (!swift && code.startsWith('"""', i)) { const end = code.indexOf('"""', i + 3); i = end < 0 ? code.length : end + 3; }
    else if (!swift && code[i] === "'") { const char = /^'(?:\\.|[^'\\])*'/.exec(code.slice(i)); i += char?.[0].length ?? 1; }
    else if (code[i] === '"') i = quoted(i, true).end;
    else i++;
  }
  return output;
}
// Inventory UI literals, display labels in native models, and user-facing errors. Dynamic copy is explicitly
// localized at call sites; identifiers, URLs, SF Symbols and persistence keys are not copy.
export async function inventory(base = root) {
  const result = { ios: new Map(), android: new Map() };
  for (const [platform, directory, ext] of [['ios', 'apps/ios/Noah', '.swift'], ['ios', 'apps/ios/NoahWidget', '.swift'], ['android', 'apps/android/app/src/main/java/com/noahwallet', '.kt']]) {
    for (const file of await filesBelow(resolve(base, directory), ext)) {
      if (file.includes('/API/') || file.includes('/api/')) continue;
      const code = await readFile(file, 'utf8');
      let literals;
      try { literals = nativeLiterals(code, platform === 'ios'); } catch (error) { throw new Error(`${file}: ${error.message}`); }
      for (const { text, index } of literals) {
        const uiFile = /Service[s]?\/(?:Wallet\/(?:WalletBalance|WalletSession|WalletBackgroundActivity)|Server\/ServerRegistration)|services\/wallet\/(?:WalletBalance|WalletSession|WalletBackgroundActivity)/.test(file) || /\/(Features|DesignSystem|ui|btcmap|onchain|recurring|send|history)\//.test(file) || /Screen\.kt$/.test(file);
        const context = code.slice(Math.max(0, index - 200), index);
        const uiLiteral = uiFile && /(?:Text|Button|Label|Link|Tab|LabeledContent|Section|TextField|navigationTitle|accessibilityLabel|row|detail|\w*Row|DetailValue|SettingToggle|SectionTitle|NoahForm|NoahButton|settingsStyle|title:|detail:)\s*\(?\s*(?:(?:tr|LocalizedStringKey)\(\s*)?$/.test(context);
        const localized = /(?:L10n\.(?:text|plural)\(\s*(?:context,\s*)?(?:[^(),\n]*(?:\?\?|\?:)\s*)?|\btr(?:Plural)?\(\s*)$/.test(context);
        const notification = /\/NotificationCopy\.(swift|kt)$/.test(file) && /^[A-Z]/.test(text);
        const visible = text.replace(/\{[\w]+\}/g, '');
        const human = /^(?:["©]\s*)?(?:[A-Z][a-z]*(?:$|[\s'’….,!?:/(-])|[A-Z][A-Za-z0-9]+\s|[A-Z0-9]{2,}$)/.test(text);
        const errorCopy = /(?:Error|Failure|Exception|error)\(\s*(?:message:\s*)?$/.test(context) || /require\([^\n]+\)\s*\{\s*$/.test(context);
        const fontName = /^Inter-(Regular|Medium|SemiBold|Bold)$/.test(text);
        const identifier = /(?:accessibilityIdentifier|testTag|systemName|Image|icon|forKey|URL|Regex|font|Font|setValue)\s*[:(]\s*$/.test(context);
        if (!text || identifier || fontName || (!(localized || notification) && (!(uiLiteral || (uiFile && human) || (errorCopy && human)) || !/[A-Za-z]/.test(visible) || text.includes('://') || (/^[a-z]+[._-]/.test(text) && !text.startsWith('e.g.'))))) continue;
        if (!result[platform].has(text)) result[platform].set(text, new Set());
        const occurrences = result[platform].get(text);
        occurrences.add(file.slice(base.length + 1));
        if (/(?:L10n\.plural\(\s*(?:context,\s*)?|\btrPlural\(\s*)$/.test(context)) occurrences.plural = true;
      }
    }
  }
  return result;
}
const xml = text => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('\\', '\\\\').replaceAll("'", "\\'").replaceAll('"', '\\"').replaceAll('\n', '\\n').replaceAll('\t', '\\t');
const unit = value => ({ stringUnit: { state: 'translated', value } });
export function iosCatalog(all) {
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
  return json({ sourceLanguage: 'en', strings: Object.fromEntries(Object.entries(strings).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)), version: '1.0' });
}
// Plural categories Android's CLDR data requires per locale (Lint's MissingQuantity); absent forms reuse "other",
// which Android would fall back to anyway. Fixed here so output doesn't depend on the host's ICU.
const androidPluralForms = { en: ['one', 'other'], es: ['one', 'many', 'other'], ja: ['other'], ko: ['other'], sv: ['one', 'other'] };
export function androidCatalog(all, locale) {
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Generated by apps/tools/translations.mjs. Do not edit. -->', '<resources>'];
  for (const entry of all) {
    const name = resourceName(entry.source);
    if (entry.quantity) {
      lines.push(`  <plurals name="${name}">`);
      const forms = entry.translations[locale];
      for (const category of categories.filter(category => category in forms || androidPluralForms[locale].includes(category))) {
        lines.push(`    <item quantity="${category}">"${xml(format(forms[category] ?? forms.other, entry.names, 'android', entry.quantity))}"</item>`);
      }
      lines.push('  </plurals>');
    } else {
      const text = format(entry.translations[locale].other, entry.names, 'android');
      // Without arguments a literal % stays as is (callers never format), so aapt mustn't read it as a format string.
      lines.push(`  <string name="${name}"${!entry.names.length && text.includes('%') ? ' formatted="false"' : ''}>"${xml(text)}"</string>`);
    }
  }
  // Existing static resource callers (tabs/launch gate, the widget's layout and picker descriptions) retain their IDs.
  if (locale !== 'en') for (const [name, source] of Object.entries({ home: 'Home', receive: 'Receive', send: 'Send', launch_welcome: 'Welcome to Noah',
    widget_onchain: 'Onchain', widget_offchain: 'Offchain', widget_mainnet_description: 'View your Bitcoin balance',
    widget_signet_description: 'View your Bitcoin balance on signet', widget_regtest_description: 'View your Bitcoin balance on regtest' })) {
    const entry = all.find(entry => entry.source === source);
    if (entry) lines.push(`  <string name="${name}">"${xml(entry.translations[locale].other)}"</string>`);
  }
  return lines.concat('</resources>', '').join('\n');
}

export const toI18next = text => text.replace(variablePattern, (_, name) => `{{${name}}}`);
export function fromI18next(text) {
  if (typeof text !== 'string') throw new Error('Translation must be a string');
  const native = text.replace(/\{\{(\w+)\}\}/g, '{$1}');
  if (/\{\{|\}\}/.test(native)) throw new Error(`Unsupported i18next placeholder: ${text}`);
  return native;
}
const sorted = object => Object.fromEntries(Object.entries(object).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0));
const categories = ['zero', 'one', 'two', 'few', 'many', 'other'];
const hasTranslation = (catalog, key) => typeof catalog?.[key] === 'string' && catalog[key].length > 0;
const translationKey = (entry, category) => entry.id + (entry.quantity ? `_${category}` : '');

export function sourceEntries(used, plurals = {}) {
  const sources = [...new Set(Object.values(used).flatMap(platform => [...platform.keys()]))].sort();
  return sources.map(source => {
    if (Object.values(used).some(platform => platform.get(source)?.plural) && !plurals[source]) throw new Error(`Missing plural forms in apps/i18n/plurals.json: ${source}`);
    const forms = plurals[source] ?? { other: source };
    if (forms.other !== source || Object.keys(forms).some(category => !categories.includes(category))) throw new Error(`Invalid plural source: ${source}`);
    const names = variables(source);
    for (const text of Object.values(forms)) if (variables(text).some(name => !names.includes(name))) throw new Error(`Placeholder drift in source: ${source}`);
    const quantity = plurals[source] ? names[0] : undefined;
    if (plurals[source] && !quantity) throw new Error(`Plural without count: ${source}`);
    return { id: resourceName(source), source, names, quantity, translations: { en: forms } };
  });
}
export function translationJSON(all, locale = 'en') {
  return sorted(Object.fromEntries(all.flatMap(entry => Object.entries(entry.translations[locale]).map(([category, text]) => [translationKey(entry, category), toI18next(text)]))));
}
export function readTranslations(all, catalogs) {
  return all.map(entry => {
    const translations = { en: entry.translations.en };
    for (const locale of locales.slice(1)) {
      const forms = entry.quantity ? categories.filter(category => category in entry.translations.en || `${entry.id}_${category}` in (catalogs[locale] ?? {})) : ['other'];
      translations[locale] = Object.fromEntries(forms.map(category => {
        const key = translationKey(entry, category);
        const text = fromI18next(catalogs[locale]?.[key] || toI18next(entry.translations.en[category] ?? entry.source));
        const names = variables(text);
        const required = variables(entry.translations.en[category] ?? entry.source);
        if (names.some(name => !entry.names.includes(name)) || required.some(name => !names.includes(name))) throw new Error(`Placeholder drift: ${locale}/${key}`);
        return [category, text];
      }));
    }
    return { ...entry, translations };
  });
}
export async function extract(base = root) {
  const used = await inventory(base);
  const plurals = JSON.parse(await readFile(resolve(base, 'apps/i18n/plurals.json'), 'utf8'));
  const all = sourceEntries(used, plurals);
  return { used, all, english: translationJSON(all) };
}
async function outputFiles(outputs, check, base) {
  const stale = [];
  for (const [file, content] of outputs) {
    const path = resolve(base, file);
    if (check) { if (await readFile(path, 'utf8').catch(() => null) !== content) stale.push(file); }
    else { await mkdir(dirname(path), { recursive: true }); await writeFile(path, content); }
  }
  if (stale.length) throw new Error(`Stale native translations. Run bun apps/tools/translations.mjs translate:\n${stale.join('\n')}`);
}
export async function extractFile(base = root) {
  const extracted = await extract(base);
  await outputFiles(new Map([['apps/i18n/en/native.json', json(extracted.english)]]), false, base);
  return extracted;
}
async function localeFiles(base) {
  return Object.fromEntries(await Promise.all(locales.slice(1).map(async locale => [locale,
    JSON.parse(await readFile(resolve(base, `apps/i18n/${locale}/native.json`), 'utf8').catch(error => { if (error.code === 'ENOENT') return '{}'; throw error; })),
  ])));
}
export function seedTranslations(all, rn, existing, force = false) {
  const seeded = {}, counts = {};
  const bySource = new Map(rn.map(entry => [entry.source, entry]));
  for (const locale of locales.slice(1)) {
    const catalog = { ...existing[locale] };
    let count = 0;
    for (const entry of all) {
      const match = bySource.get(entry.source);
      if (!match || Boolean(match.quantity) !== Boolean(entry.quantity)) continue;
      for (const [category, text] of Object.entries(match.translations[locale])) {
        const key = translationKey(entry, category);
        if (!force && Object.hasOwn(catalog, key)) continue;
        catalog[key] = toI18next(text); count++;
      }
    }
    seeded[locale] = sorted(catalog); counts[locale] = count;
  }
  return { catalogs: seeded, counts };
}
export async function seed(force = false) {
  const { all } = await extractFile();
  const { catalogs, counts } = seedTranslations(all, entries(await extractRN()), await localeFiles(root), force);
  await outputFiles(new Map(locales.slice(1).map(locale => [`apps/i18n/${locale}/native.json`, json(catalogs[locale])])), false, root);
  console.log(JSON.stringify({ seededKeys: counts }));
  return counts;
}
/** Copy one platform uses alone must be listed for that platform with a reason; a listed entry must still be that platform's alone. */
export function platformOnly(used, allowlist = {}) {
  const problems = [];
  for (const [platform, other] of [['ios', 'android'], ['android', 'ios']]) {
    const listed = allowlist[platform] ?? {};
    for (const source of used[platform].keys()) if (!used[other].has(source) && !Object.hasOwn(listed, source)) problems.push(`${platform} only, not listed: ${source}`);
    for (const [source, reason] of Object.entries(listed)) {
      if (!used[platform].has(source)) problems.push(`Listed for ${platform}, which no longer uses it: ${source}`);
      else if (used[other].has(source)) problems.push(`Listed for ${platform}, but ${other} uses it too: ${source}`);
      if (typeof reason !== 'string' || !reason.trim()) problems.push(`Listed for ${platform} without a reason: ${source}`);
    }
  }
  return problems;
}
export async function generate(check = false, base = root) {
  const { all: sources, used, english } = await extract(base);
  const catalogs = await localeFiles(base);
  const all = readTranslations(sources, catalogs);
  const outputs = new Map([['apps/i18n/en/native.json', json(english)]]);
  outputs.set('apps/ios/Noah/Resources/Localizable.xcstrings', iosCatalog(all));
  for (const locale of locales) outputs.set(`apps/android/app/src/main/res/values${locale === 'en' ? '' : '-' + locale}/strings.xml`, androidCatalog(all, locale));
  const coverage = Object.fromEntries(['ios', 'android'].map(platform => {
    const platformEntries = sources.filter(entry => used[platform].has(entry.source));
    return [platform, {
      used: [...used[platform].keys()].sort(),
      untranslated: Object.fromEntries(locales.slice(1).map(locale => [locale, platformEntries.flatMap(entry =>
        Object.keys(entry.translations.en).map(category => translationKey(entry, category)).filter(key => !hasTranslation(catalogs[locale], key)),
      ).sort()])),
    }];
  }));
  outputs.set('apps/fixtures/translations-coverage.json', json({ locales, catalogEntries: all.length, sourceKeys: Object.keys(english).length, coverage }));
  await outputFiles(outputs, check, base);
  if (check) {
    const allowlist = JSON.parse(await readFile(resolve(base, 'apps/i18n/platform-only.json'), 'utf8').catch(error => { if (error.code === 'ENOENT') return '{}'; throw error; }));
    const problems = platformOnly(used, allowlist);
    if (problems.length) throw new Error(`Single-platform copy. Use the same English on both platforms, or list it with a reason in apps/i18n/platform-only.json:\n${problems.join('\n')}`);
  }
  return { catalogEntries: all.length, sourceKeys: Object.keys(english).length, untranslated: Object.fromEntries(Object.entries(coverage).map(([platform, value]) => [platform, Object.fromEntries(Object.entries(value.untranslated).map(([locale, keys]) => [locale, keys.length]))])) };
}
async function runGT(command, directory, base) {
  const process = Bun.spawn([resolve(base, 'node_modules/.bin/gt'), command], { cwd: directory, stdin: 'inherit', stdout: 'inherit', stderr: 'inherit' });
  if (await process.exited) throw new Error(`gt ${command} failed`);
}
export function missingGroups(english, catalogs) {
  const groups = new Map();
  for (const locale of locales.slice(1)) {
    const keys = Object.keys(english).filter(key => !hasTranslation(catalogs[locale], key));
    if (!keys.length) continue;
    const signature = JSON.stringify(keys);
    if (!groups.has(signature)) groups.set(signature, { locales: [], source: Object.fromEntries(keys.map(key => [key, english[key]])) });
    groups.get(signature).locales.push(locale);
  }
  // Bound response size, especially for Korean; keep sibling plural forms together.
  return [...groups.values()].flatMap(group => {
    const keys = Object.keys(group.source), batches = [];
    for (let start = 0; start < keys.length;) {
      let end = Math.min(start + 100, keys.length);
      const stem = key => key.replace(/_(zero|one|two|few|many|other)$/, '');
      while (end < keys.length && stem(keys[end]) === stem(keys[end - 1])) end++;
      batches.push({ locales: group.locales, source: Object.fromEntries(keys.slice(start, end).map(key => [key, group.source[key]])) });
      start = end;
    }
    return batches;
  });
}
const writeLocales = (catalogs, base, only = locales.slice(1)) => outputFiles(new Map(only.map(locale => [`apps/i18n/${locale}/native.json`, json(sorted(catalogs[locale]))])), false, base);
// CI reuses an open bot PR's translations (another apps/i18n under `other`) so GT never translates a key twice.
// Plural form keys hash only the `other` text, so a key is reused only while its English is unchanged.
export async function reuse(other, base = root) {
  const { all, english } = await extract(base);
  const catalogs = await localeFiles(base), previous = await localeFiles(other);
  const previousEnglish = JSON.parse(await readFile(resolve(other, 'apps/i18n/en/native.json'), 'utf8'));
  const reused = {};
  for (const locale of locales.slice(1)) {
    const keys = Object.keys(english).filter(key => !hasTranslation(catalogs[locale], key) && hasTranslation(previous[locale], key) && previousEnglish[key] === english[key]);
    for (const key of keys) catalogs[locale][key] = previous[locale][key];
    reused[locale] = keys.length;
  }
  readTranslations(all, catalogs);
  await writeLocales(catalogs, base, locales.slice(1).filter(locale => reused[locale]));
  return { reused };
}
export async function translate(base = root, { dryRun = false } = {}) {
  const { all, english } = dryRun ? await extract(base) : await extractFile(base);
  const catalogs = await localeFiles(base);
  readTranslations(all, catalogs);
  const groups = missingGroups(english, catalogs);
  // Reports what GT would be sent without writing files or contacting GT.
  if (dryRun) return { missing: Object.fromEntries(locales.slice(1).map(locale => [locale, groups.filter(group => group.locales.includes(locale)).reduce((count, group) => count + Object.keys(group.source).length, 0)])), batches: groups.length };
  const temporary = await mkdtemp(resolve(tmpdir(), 'noah-gt-missing-'));
  try {
    const config = JSON.parse(await readFile(resolve(base, 'apps/i18n/gt.config.json'), 'utf8'));
    await writeFile(resolve(temporary, 'package.json'), await readFile(resolve(base, 'apps/i18n/package.json')));
    for (const group of groups) {
      // GT treats a partial upload as a complete file. Submit only missing keys,
      // using the same relative filename/project; keep native files untouched until success.
      await outputFiles(new Map([
        ['gt.config.json', json({ ...config, locales: group.locales })],
        ['en/native.json', json(group.source)],
      ]), false, temporary);
      await runGT('translate', temporary, base);
      for (const locale of group.locales) {
        const translated = JSON.parse(await readFile(resolve(temporary, `${locale}/native.json`), 'utf8').catch(error => { throw new Error(`GT did not return ${locale}/native.json; rerun translate to retry`, { cause: error }); }));
        for (const key of Object.keys(group.source)) {
          if (typeof translated[key] !== 'string' || !translated[key]) throw new Error(`GT did not translate ${locale}/${key}`);
          catalogs[locale][key] = translated[key];
        }
      }
    }
    readTranslations(all, catalogs);
    await writeLocales(catalogs, base);
  } finally { await rm(temporary, { recursive: true, force: true }); }
  // The dashboard and committed lock describe the complete catalogs, including seeds.
  await runGT('upload', resolve(base, 'apps/i18n'), base);
  return generate(false, base);
}
if (import.meta.main) {
  const command = Bun.argv[2] ?? 'generate';
  if (command === '--check') console.log(JSON.stringify(await generate(true)));
  else if (command === 'extract') await extractFile();
  else if (command === 'seed') await seed(Bun.argv.includes('--force'));
  else if (command === 'translate') console.log(JSON.stringify(await translate(root, { dryRun: Bun.argv.includes('--dry-run') })));
  else if (command === 'reuse') {
    if (!Bun.argv[3]) throw new Error('Usage: reuse <directory containing apps/i18n>');
    console.log(JSON.stringify(await reuse(resolve(Bun.argv[3]))));
  }
  else if (command === 'generate') console.log(JSON.stringify(await generate()));
  else throw new Error(`Unknown command: ${command}`);
}
