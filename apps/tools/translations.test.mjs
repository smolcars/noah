import { test, expect } from 'bun:test';
import { mkdtemp, mkdir, readFile, writeFile, rm, copyFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, dirname } from 'node:path';
import { flatten, format, entries, iosCatalog, androidCatalog, resourceName, locales, sourceEntries, translationJSON, readTranslations, seedTranslations, extract, generate, root, translate, reuse, missingGroups, platformOnly } from './translations.mjs';

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
test('plural selection stays numeric while Android display counts are strings', () => {
  const node = { t: 'Plural', d: { t: 'p', b: { one: 'One place', other: [{ v: 'v', k: 'count' }, ' places'] } } };
  const catalogs = Object.fromEntries(['en', 'es', 'ja', 'ko', 'sv'].map(locale => [locale, { id: node }]));
  const all = entries(catalogs);
  expect(all[0].quantity).toBe('count');
  const catalog = JSON.parse(iosCatalog(all, []));
  expect(catalog.strings['{count} places'].localizations.en.variations.plural.other.stringUnit.value).toBe('%1$lld places');
  expect(androidCatalog(all, 'en')).toContain('<item quantity="other">"%1$s places"</item>');
  // Android requires Spanish's "many"; it reuses "other", and iOS keeps only the catalog's forms.
  expect(androidCatalog(all, 'es')).toContain('<item quantity="many">"%1$s places"</item>');
  expect(catalog.strings['{count} places'].localizations.es.variations.plural.many).toBeUndefined();
  expect(resourceName('Cancel')).toBe(resourceName('Cancel'));
});
test('missing translations and unknown variables fail generation', () => {
  expect(() => entries({ en: { id: 'Hello' } })).toThrow('Missing');
  const catalogs = Object.fromEntries(['en', 'es', 'ja', 'ko', 'sv'].map(locale => [locale, { id: locale === 'en' ? '{name}' : '{wrong}' }]));
  expect(() => entries(catalogs)).toThrow('Placeholder drift');
});

test('native templates and literal percent signs round trip through JSON', () => {
  const all = sourceEntries({ ios: new Map(['Payment to {recipient} was skipped.', '100% ready'].map(source => [source, new Set()])) });
  const catalogs = Object.fromEntries(locales.slice(1).map(locale => [locale, translationJSON(all)]));
  const translated = readTranslations(all, catalogs);
  const strings = JSON.parse(iosCatalog(translated)).strings;
  expect(strings['Payment to {recipient} was skipped.'].localizations.es.stringUnit.value).toBe('Payment to %1$@ was skipped.');
  expect(strings['100% ready'].localizations.en.stringUnit.value).toBe('100% ready');
  expect(androidCatalog(translated, 'en')).toContain('formatted="false">"100% ready"');
});

test('native inventory handles nested interpolation and ignores comments and raw regexes', async () => {
  const { nativeLiterals } = await import('./translations.mjs');
  expect(nativeLiterals('// "Ignore"\nText("Hello \\(name ?? "friend")")\nlet regex = #"["\\w]"#', true).map(item => item.text)).toEqual(['Hello {nativeValue1}']);
  expect(nativeLiterals('Text("Hello ${name ?: "friend"}") // "Ignore"\nval quote = \'"\'', false).map(item => item.text)).toEqual(['Hello {nativeValue1}']);
});

test('checked-in native English, catalogs and coverage stay current in all five locales', async () => {
  const { generate } = await import('./translations.mjs');
  await generate(true);
}, 30_000);

test('extraction unions platforms, deduplicates and produces stable IDs independent of traversal order', () => {
  const a = { ios: new Map([['Send', new Set()], ['Hello {name}', new Set()]]), android: new Map([['Send', new Set()], ['Receive', new Set()]]) };
  const b = { android: new Map([...a.android].reverse()), ios: new Map([...a.ios].reverse()) };
  expect(translationJSON(sourceEntries(a))).toEqual(translationJSON(sourceEntries(b)));
  expect(translationJSON(sourceEntries(a))).toEqual({ [resourceName('Send')]: 'Send', [resourceName('Hello {name}')]: 'Hello {{name}}', [resourceName('Receive')]: 'Receive' });
  expect(resourceName('Send')).not.toBe(resourceName('Send now'));
});

test('i18next variables and all source plural categories survive both native formats', () => {
  const source = '{_gt_value_4} places for {name}{nativeValue1}';
  const forms = { one: 'One place for {name}{nativeValue1}', few: 'A few places for {name}{nativeValue1}', other: source };
  const all = sourceEntries({ ios: new Map([[source, new Set()]]) }, { [source]: forms });
  const english = translationJSON(all);
  expect(english[`${resourceName(source)}_one`]).toBe('One place for {{name}}{{nativeValue1}}');
  const catalogs = Object.fromEntries(locales.slice(1).map(locale => [locale, { ...english, [`${resourceName(source)}_other`]: '{{nativeValue1}}{{name}}: {{_gt_value_4}}' }]));
  const translated = readTranslations(all, catalogs);
  expect(translated[0].names).toEqual(['_gt_value_4', 'name', 'nativeValue1']);
  expect(JSON.parse(iosCatalog(translated)).strings[source].localizations.ja.variations.plural.other.stringUnit.value).toBe('%3$@%2$@: %1$lld');
  expect(androidCatalog(translated, 'sv')).toContain('<item quantity="few">"A few places for %2$s%3$s"</item>');
  expect(androidCatalog(translated, 'sv')).toContain('"%3$s%2$s: %1$s"');
  catalogs.es[`${resourceName(source)}_other`] = '{{wrong}}';
  expect(() => readTranslations(all, catalogs)).toThrow('Placeholder drift');
  const pluralUse = new Set(); pluralUse.plural = true;
  expect(() => sourceEntries({ ios: new Map([[source, pluralUse]]) })).toThrow('Missing plural forms');
});

test('RN seeds preserve exact flattened text, never overwrite native translations, and support force', () => {
  const source = '{count} places';
  const node = { t: 'Plural', d: { t: 'p', b: { one: 'One place', other: [{ v: 'v', k: 'count' }, ' places'] } } };
  const catalogs = Object.fromEntries(locales.map(locale => [locale, { id: node, rich: locale === 'en' ? text : { c: ['¡', { v: 'v', k: 'name' }, '!\n100%'] } }]));
  const rn = entries(catalogs);
  const all = sourceEntries({ ios: new Map([[source, new Set()], ['Hello {name}!', new Set()], ['Native only', new Set()]]) }, { [source]: { one: 'One place', other: source } });
  const key = `${resourceName(source)}_one`;
  const existing = { es: { [key]: 'Native translation' } };
  const result = seedTranslations(all, rn, existing);
  expect(result.catalogs.es[key]).toBe('Native translation');
  expect(result.catalogs.es[resourceName('Hello {name}!')]).toBe('¡{{name}}!\n100%');
  expect(result.catalogs.es[resourceName('Native only')]).toBeUndefined();
  expect(existing.es).toEqual({ [key]: 'Native translation' });
  expect(result.counts).toEqual({ es: 2, ja: 3, ko: 3, sv: 3 });
  expect(seedTranslations(all, rn, result.catalogs).counts).toEqual({ es: 0, ja: 0, ko: 0, sv: 0 });
  expect(seedTranslations(all, rn, existing, true).catalogs.es[key]).toBe('One place');
});

async function fixture(run) {
  const base = await mkdtemp(resolve(tmpdir(), 'noah-i18n-test-'));
  const put = async (path, content) => { await mkdir(dirname(resolve(base, path)), { recursive: true }); await writeFile(resolve(base, path), content); };
  try {
    await put('apps/ios/Noah/Features/Example.swift', 'Text("Hello {name}")\nText("Native only")');
    await put('apps/ios/NoahWidget/Example.swift', '');
    await put('apps/android/app/src/main/java/com/noahwallet/ui/Example.kt', 'Text(tr("Send"))');
    await put('apps/i18n/plurals.json', '{}');
    await put('apps/i18n/platform-only.json', JSON.stringify({ ios: { 'Hello {name}': 'fixture', 'Native only': 'fixture' }, android: { Send: 'fixture' } }));
    await run(base, put);
  } finally { await rm(base, { recursive: true, force: true }); }
}

test('the iOS widget extension is inventoried with the app', async () => {
  await fixture(async (base, put) => {
    await put('apps/ios/NoahWidget/Example.swift', 'Text(L10n.text("Native only", locale: language))');
    expect([...(await extract(base)).used.ios.get('Native only')]).toEqual(['apps/ios/Noah/Features/Example.swift', 'apps/ios/NoahWidget/Example.swift']);
  });
});

test('L10n calls wrapped onto the next line are still inventoried', async () => {
  await fixture(async (base, put) => {
    await put('apps/ios/Noah/Services/Wrapped.swift', 'let body = L10n.text(\n    "Wrapped copy",\n    locale: locale)');
    await put('apps/android/app/src/main/java/com/noahwallet/services/Wrapped.kt', 'val body = L10n.text(\n    context,\n    "Wrapped copy")');
    const { used } = await extract(base);
    expect(used.ios.has('Wrapped copy') && used.android.has('Wrapped copy')).toBe(true);
  });
});

test('stale checks detect English edits, code additions/removals, locale edits and compiled resource drift', async () => {
  await fixture(async (base, put) => {
    await generate(false, base);
    await generate(true, base);
    await put('apps/i18n/en/native.json', '{}');
    await expect(generate(true, base)).rejects.toThrow('apps/i18n/en/native.json');
    await generate(false, base);
    await put('apps/ios/Noah/Features/Example.swift', 'Text("Replacement")');
    await expect(generate(true, base)).rejects.toThrow('apps/i18n/en/native.json');
    expect((await extract(base)).english[resourceName('Native only')]).toBeUndefined();
    await generate(false, base);
    await put('apps/i18n/es/native.json', JSON.stringify({ [resourceName('Replacement')]: 'Reemplazo' }));
    await expect(generate(true, base)).rejects.toThrow('Localizable.xcstrings');
    await generate(false, base);
    await put('apps/android/app/src/main/res/values/strings.xml', 'stale');
    await expect(generate(true, base)).rejects.toThrow('res/values/strings.xml');
    const coverage = JSON.parse(await readFile(resolve(base, 'apps/fixtures/translations-coverage.json'), 'utf8'));
    expect(coverage.coverage.ios.untranslated.es).toEqual([]);
    expect(coverage.coverage.ios.untranslated.ja).toEqual([resourceName('Replacement')]);
  });
});

test('extract, generate, --check and translate --dry-run run without RN, dependencies, credentials, or invoking GT', async () => {
  await fixture(async (base, put) => {
    await mkdir(resolve(base, 'apps/tools'), { recursive: true });
    await copyFile(resolve(root, 'apps/tools/translations.mjs'), resolve(base, 'apps/tools/translations.mjs'));
    await put('node_modules/.bin/gt', '#!/bin/sh\necho "GT must stay offline" >&2\nexit 97\n');
    await chmod(resolve(base, 'node_modules/.bin/gt'), 0o755);
    for (const command of [['extract'], ['generate'], ['--check'], ['translate', '--dry-run']]) {
      const process = Bun.spawn([Bun.argv[0], 'apps/tools/translations.mjs', ...command], { cwd: base, env: { PATH: '/usr/bin:/bin' }, stdout: 'pipe', stderr: 'pipe' });
      const stderr = await new Response(process.stderr).text();
      expect(stderr).toBe('');
      expect(await process.exited).toBe(0);
    }
  });
});

test('translate sends only missing keys per locale and preserves seeds verbatim', async () => {
  await fixture(async (base, put) => {
    await put('apps/i18n/gt.config.json', JSON.stringify({ defaultLocale: 'en', locales: locales.slice(1), files: { json: { include: ['[locale]/native.json'] } } }));
    await put('apps/i18n/package.json', '{"dependencies":{"i18next":"*"}}');
    const seedKey = resourceName('Hello {name}');
    await put('apps/i18n/es/native.json', JSON.stringify({ [seedKey]: 'Hola {{name}}\n100%' }));
    const trace = resolve(base, 'calls.jsonl');
    await put('node_modules/.bin/gt', `#!${Bun.argv[0]}
import { readFileSync, writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const source = JSON.parse(readFileSync('en/native.json', 'utf8'));
const config = JSON.parse(readFileSync('gt.config.json', 'utf8'));
appendFileSync(${JSON.stringify(trace)}, JSON.stringify({ command: process.argv[2], locales: config.locales, source }) + '\\n');
if (process.argv[2] === 'translate') for (const locale of config.locales) {
  mkdirSync(locale, { recursive: true });
  writeFileSync(locale + '/native.json', JSON.stringify(Object.fromEntries(Object.entries(source).map(([key, text]) => [key, 'GT ' + text]))));
}
`);
    await chmod(resolve(base, 'node_modules/.bin/gt'), 0o755);
    expect(await translate(base, { dryRun: true })).toEqual({ missing: { es: 2, ja: 3, ko: 3, sv: 3 }, batches: 2 });
    expect(await readFile(trace, 'utf8').catch(() => 'no calls')).toBe('no calls');
    expect(await readFile(resolve(base, 'apps/i18n/en/native.json'), 'utf8').catch(() => 'not written')).toBe('not written');
    await translate(base);
    const calls = (await readFile(trace, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
    expect(calls).toHaveLength(3);
    const spanish = calls.find(call => call.command === 'translate' && call.locales.includes('es'));
    expect(spanish.source[seedKey]).toBeUndefined();
    expect(calls.find(call => call.command === 'translate' && call.locales.includes('ja')).source[seedKey]).toBe('Hello {{name}}');
    expect(calls.at(-1).command).toBe('upload');
    expect(Object.keys(calls.at(-1).source)).toHaveLength(3);
    const actual = JSON.parse(await readFile(resolve(base, 'apps/i18n/es/native.json'), 'utf8'));
    expect(actual[seedKey]).toBe('Hola {{name}}\n100%');
    expect(actual[resourceName('Send')]).toBe('GT Send');
    await generate(true, base);
    expect(await translate(base, { dryRun: true })).toEqual({ missing: { es: 0, ja: 0, ko: 0, sv: 0 }, batches: 0 });
    await translate(base);
    expect((await readFile(trace, 'utf8')).trim().split('\n')).toHaveLength(4); // Complete catalogs only upload.
  });
});

test('reuse fills only missing keys still in English from another catalog, validated, never overwriting', async () => {
  await fixture(async (base, put) => {
    const [hello, send] = [resourceName('Hello {name}'), resourceName('Send')];
    await put('apps/i18n/es/native.json', JSON.stringify({ [hello]: 'Hola {{name}}' }));
    const other = resolve(base, 'bot');
    await expect(reuse(other, base)).rejects.toThrow('en/native.json');
    await put('bot/apps/i18n/en/native.json', JSON.stringify({ [hello]: 'Hello {{name}}', [send]: 'Send' }));
    await put('bot/apps/i18n/es/native.json', JSON.stringify({ [hello]: 'Otro {{name}}', [send]: 'Enviar' }));
    await put('bot/apps/i18n/ja/native.json', JSON.stringify({ [send]: '送信', gt_obsolete: 'Old' }));
    expect(await reuse(other, base)).toEqual({ reused: { es: 1, ja: 1, ko: 0, sv: 0 } });
    expect(JSON.parse(await readFile(resolve(base, 'apps/i18n/es/native.json'), 'utf8'))).toEqual({ [hello]: 'Hola {{name}}', [send]: 'Enviar' });
    expect(JSON.parse(await readFile(resolve(base, 'apps/i18n/ja/native.json'), 'utf8'))).toEqual({ [send]: '送信' });
    expect((await translate(base, { dryRun: true })).missing).toEqual({ es: 1, ja: 2, ko: 3, sv: 3 });
    await put('bot/apps/i18n/ko/native.json', JSON.stringify({ [hello]: '{{wrong}}' }));
    await expect(reuse(other, base)).rejects.toThrow('Placeholder drift');
    expect(await readFile(resolve(base, 'apps/i18n/ko/native.json'), 'utf8').catch(() => 'not written')).toBe('not written');
  });
});

test('reuse skips a plural form whose English changed while its key did not', async () => {
  await fixture(async (base, put) => {
    const source = '{count} withdrawals', id = resourceName(source);
    await put('apps/ios/Noah/Features/Example.swift', `L10n.plural("${source}", count)`);
    await put('apps/i18n/plurals.json', JSON.stringify({ [source]: { one: 'One pending withdrawal', other: source } }));
    await put('bot/apps/i18n/en/native.json', JSON.stringify({ [`${id}_one`]: 'One withdrawal', [`${id}_other`]: '{{count}} withdrawals' }));
    await put('bot/apps/i18n/es/native.json', JSON.stringify({ [`${id}_one`]: 'Un retiro', [`${id}_other`]: '{{count}} retiros' }));
    expect(await reuse(resolve(base, 'bot'), base)).toEqual({ reused: { es: 1, ja: 0, ko: 0, sv: 0 } });
    expect(JSON.parse(await readFile(resolve(base, 'apps/i18n/es/native.json'), 'utf8'))).toEqual({ [`${id}_other`]: '{{count}} retiros' });
    expect((await translate(base, { dryRun: true })).missing.es).toBe(2); // The changed singular and "Send".
  });
});

// GitHub keeps one pending run per concurrency group and replaces it with any newer one, so a dispatch on
// another branch (whose job is then skipped) must not share master's group and drop a queued merge.
test('bot runs queue per ref, so a dispatch on another branch cannot replace master\'s pending run', async () => {
  const workflow = Bun.YAML.parse(await readFile(resolve(root, '.github/workflows/native-translations.yml'), 'utf8'));
  const group = ref => workflow.concurrency.group.replace(/\$\{\{\s*github\.(workflow|ref)\s*\}\}/g, (_, key) => ({ workflow: workflow.name, ref })[key]);
  expect(group('refs/heads/master')).not.toContain('${{');
  expect(group('refs/heads/master')).toBe(group('refs/heads/master'));
  expect(group('refs/heads/bot/native-translations')).not.toBe(group('refs/heads/master'));
  expect(workflow.concurrency['cancel-in-progress']).toBe(false);
  expect(workflow.jobs.translate.if).toBe("github.ref == 'refs/heads/master'");
});

// Runs the bot workflow's own step scripts against a scratch repo, a local origin and a stub gh that applies --jq.
async function botRepo(run) {
  const workflow = Bun.YAML.parse(await readFile(resolve(root, '.github/workflows/native-translations.yml'), 'utf8'));
  const temporary = await mkdtemp(resolve(tmpdir(), 'noah-i18n-bot-'));
  try {
    await fixture(async (base, put) => {
      await mkdir(resolve(base, 'apps/tools'), { recursive: true });
      await copyFile(resolve(root, 'apps/tools/translations.mjs'), resolve(base, 'apps/tools/translations.mjs'));
      for (const locale of locales.slice(1)) await put(`apps/i18n/${locale}/native.json`, '{}');
      await put('apps/i18n/gt-lock.json', '{}');
      const [origin, log, stubs] = ['origin.git', 'gh.log', 'bin'].map(name => resolve(temporary, name));
      await mkdir(stubs);
      await writeFile(resolve(stubs, 'gh'), `#!/bin/bash
printf '%s\\n' "$*" >> "$STUB_LOG"
case "$1 $2" in
  "pr list") while [[ $# -gt 0 && "$1" != --jq ]]; do shift; done; jq -r "$2" <<< "$STUB_PRS" ;;
  "api -H") path="\${4#repos/*/contents/}"; git -C "$STUB_ORIGIN" show "\${4#*\\?ref=}:\${path%%\\?*}" ;;
esac
`);
      await chmod(resolve(stubs, 'gh'), 0o755);
      const env = { ...process.env, ...workflow.env, PATH: `${stubs}:${process.env.PATH}`, HOME: temporary, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1',
        STUB_LOG: log, STUB_ORIGIN: origin, GH_TOKEN: 'stub', GITHUB_REPOSITORY: 'smolcars/noah', RUN_URL: 'https://example.invalid/run', RUNNER_TEMP: temporary, GITHUB_STEP_SUMMARY: resolve(temporary, 'summary.md') };
      const sh = (command, extra = {}) => {
        const result = Bun.spawnSync(command, { cwd: base, env: { ...env, ...extra }, stdout: 'pipe', stderr: 'pipe' });
        if (result.exitCode) throw new Error(`${command.join(' ')}:\n${result.stdout}${result.stderr}`);
        return result.stdout.toString();
      };
      const git = (...args) => sh(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', ...args]);
      git('init', '-q', '-b', 'master'); sh(['git', 'init', '-q', '--bare', origin]); git('remote', 'add', 'origin', origin);
      await run({
        base, put, git, workflow, temporary, bot: workflow.env.BOT_BRANCH,
        open: JSON.stringify([{ number: 7, isCrossRepository: true }, { number: 42, isCrossRepository: false }]),
        // Regenerates, commits everything and pushes it as master; returns the commit.
        commit: async message => { await generate(false, base); git('add', '.'); git('commit', '-qm', message); git('push', '-q', 'origin', 'HEAD:refs/heads/master'); return git('rev-parse', 'HEAD').trim(); },
        // As actions/checkout: an explicit ref checks out that branch's current tip, otherwise the event's commit.
        checkout: sha => { const ref = workflow.jobs.translate.steps.find(step => step.uses?.startsWith('actions/checkout')).with?.ref; git('fetch', '-q', 'origin'); git('checkout', '-q', ref ? `origin/${ref}` : sha); },
        step: (name, prs, sha = git('rev-parse', 'HEAD').trim()) => sh(['bash', '--noprofile', '--norc', '-eo', 'pipefail', '-c', workflow.jobs.translate.steps.find(step => step.name === name).run], { STUB_PRS: prs, GITHUB_SHA: sha }),
        calls: async () => (await readFile(log, 'utf8').catch(() => '')).trim().split('\n').filter(Boolean),
        prCalls: async () => (await readFile(log, 'utf8').catch(() => '')).split('\n').filter(call => /^pr (create|edit|close)/.test(call)),
        clearCalls: () => writeFile(log, ''),
      });
    });
  } finally { await rm(temporary, { recursive: true, force: true }); }
}

test('the bot workflow reuses its open PR, updates it, and closes it once master needs nothing', async () => {
  await botRepo(async ({ base, put, git, bot, open, commit, step, calls, prCalls, clearCalls, temporary }) => {
    await put('apps/i18n/es/native.json', JSON.stringify({ [resourceName('Send')]: 'Enviar' }));
    await commit('master');
    // The open bot PR already has Japanese "Send".
    await put('apps/i18n/ja/native.json', JSON.stringify({ [resourceName('Send')]: '送信' }));
    git('commit', '-qam', 'bot'); git('push', '-q', 'origin', `HEAD:refs/heads/${bot}`); git('reset', '-q', '--hard', 'HEAD~');

    step('Reuse translations from the open bot PR', open);
    expect((await calls()).filter(call => call.startsWith('api')).map(call => call.match(/i18n\/(\w+)\//)[1])).toEqual(locales);
    await generate(false, base);
    step('Open or update the translations PR', open);
    expect((await prCalls()).map(call => call.split(' --')[0])).toEqual(['pr edit 42']);
    expect(git('show', `origin/${bot}:apps/i18n/ja/native.json`)).toContain('送信');
    expect(await readFile(resolve(temporary, 'body.md'), 'utf8')).toContain('| ja | 1 |');

    // Master now has every key the bot PR added, so the PR would only conflict: close it and delete its branch.
    await clearCalls();
    step('Open or update the translations PR', open);
    expect(await prCalls()).toEqual([expect.stringMatching(/^pr close 42 --delete-branch --comment Superseded/)]);
    await clearCalls();
    expect(step('Open or update the translations PR', '[]')).toContain('Nothing to translate.');
    expect(await prCalls()).toEqual([]);
  });
}, 30_000);

// A fully translated older run would close the PR; one still missing a key would push a bot branch on the older master.
test.each([['fully translated'], ['missing a key']])('a rerun of an older run (%s) works on the current master and keeps the bot PR', async older => {
  await botRepo(async ({ base, put, git, bot, open, commit, checkout, step, prCalls, temporary }) => {
    const translateAll = async (skip = '') => {
      const english = JSON.parse(await readFile(resolve(base, 'apps/i18n/en/native.json'), 'utf8'));
      for (const locale of locales.slice(1)) await put(`apps/i18n/${locale}/native.json`, JSON.stringify(Object.fromEntries(Object.entries(english).filter(([key]) => key !== skip).map(([key, text]) => [key, `${locale} ${text}`]))));
    };
    await generate(false, base); await translateAll(older === 'missing a key' ? resourceName('Native only') : '');
    const a = await commit('A');
    await put('apps/ios/Noah/Features/Example.swift', 'Text("Hello {name}")\nText("Native only")\nText("Receive")');
    await put('apps/android/app/src/main/java/com/noahwallet/ui/Example.kt', 'Text(tr("Send"))\nText(tr("Receive"))');
    const newer = await commit('B: adds Receive');
    // The open bot PR translates everything still missing on B.
    await translateAll();
    git('commit', '-qam', 'bot'); git('push', '-q', 'origin', `HEAD:refs/heads/${bot}`);

    // Rerunning A's run keeps A as the event commit.
    checkout(a);
    step('Reuse translations from the open bot PR', open, a);
    await generate(false, base);
    step('Open or update the translations PR', open, a);
    expect((await prCalls()).map(call => call.split(' --')[0])).toEqual(['pr edit 42']);
    expect(git('rev-parse', `origin/${bot}~`).trim()).toBe(newer);
    expect(git('show', `origin/${bot}:apps/i18n/ja/native.json`)).toContain(`ja Receive`);
    expect(await readFile(resolve(temporary, 'body.md'), 'utf8')).toContain(`at ${newer.slice(0, 9)}.`);
  });
}, 30_000);

test('failed GT runs leave seeded files intact', async () => {
  await fixture(async (base, put) => {
    await put('apps/i18n/gt.config.json', '{}');
    await put('apps/i18n/package.json', '{}');
    const seeded = JSON.stringify({ [resourceName('Send')]: 'Enviar' });
    await put('apps/i18n/es/native.json', seeded);
    await put('node_modules/.bin/gt', '#!/bin/sh\nexit 9\n');
    await chmod(resolve(base, 'node_modules/.bin/gt'), 0o755);
    await expect(translate(base)).rejects.toThrow('gt translate failed');
    expect(await readFile(resolve(base, 'apps/i18n/es/native.json'), 'utf8')).toBe(seeded);
  });
});


test('GT batches are bounded and keep plural siblings together', () => {
  const english = Object.fromEntries(Array.from({ length: 99 }, (_, i) => [`gt_${i}`, `Copy ${i}`]));
  Object.assign(english, { gt_plural_one: 'One place', gt_plural_other: '{{count}} places', gt_tail: 'Tail' });
  const catalogs = Object.fromEntries(locales.slice(1).map(locale => [locale, {}]));
  const batches = missingGroups(english, catalogs);
  expect(batches.map(batch => Object.keys(batch.source).length)).toEqual([101, 1]);
  expect(batches[0].locales).toEqual(locales.slice(1));
  expect(batches[0].source.gt_plural_other).toBe('{{count}} places');
});


test('native discovery includes dynamic fallback copy and ignores identifiers and user values', async () => {
  await fixture(async (base, put) => {
    await put('apps/ios/Noah/Features/Example.swift', 'L10n.text(result.lastError ?? "unknown error")\nText("Balance: \\(model.balance)")\nText("Send").accessibilityIdentifier("send-button")\nlet url = "https://example.com"');
    const { english } = await extract(base);
    expect(english[resourceName('unknown error')]).toBe('unknown error');
    expect(english[resourceName('Balance: {nativeValue1}')]).toBe('Balance: {{nativeValue1}}');
    expect(Object.values(english)).not.toContain('send-button');
    expect(Object.values(english)).not.toContain('https://example.com');
  });
});

test('single-platform copy needs a listed reason, and a listed entry must stay single-platform', async () => {
  const used = { ios: new Map([['Shared', new Set()], ['Face ID', new Set()]]), android: new Map([['Shared', new Set()], ['Battery', new Set()]]) };
  expect(platformOnly(used, { ios: { 'Face ID': 'platform' }, android: { Battery: 'platform' } })).toEqual([]);
  expect(platformOnly(used, { ios: { 'Face ID': 'platform' } })).toEqual(['android only, not listed: Battery']);
  expect(platformOnly(used, { ios: { 'Face ID': ' ', Shared: 'stale', Gone: 'stale' }, android: { Battery: 'platform' } })).toEqual([
    'Listed for ios without a reason: Face ID', 'Listed for ios, but android uses it too: Shared', 'Listed for ios, which no longer uses it: Gone']);
  await fixture(async (base, put) => {
    await generate(false, base);
    await generate(true, base);
    await put('apps/i18n/platform-only.json', JSON.stringify({ ios: { 'Hello {name}': 'fixture' }, android: { Send: 'fixture' } }));
    await expect(generate(true, base)).rejects.toThrow('ios only, not listed: Native only');
  });
});

test('every prompt to type "delete" keeps the literal word, which both apps check, in every locale', async () => {
  const english = JSON.parse(await readFile(resolve(root, 'apps/i18n/en/native.json'), 'utf8'));
  const prompts = Object.entries(english).filter(([, text]) => /\btype delete\b/i.test(text));
  expect(prompts.length).toBeGreaterThan(0);
  for (const locale of locales.slice(1)) {
    const catalog = JSON.parse(await readFile(resolve(root, `apps/i18n/${locale}/native.json`), 'utf8'));
    for (const [key, text] of prompts) expect(`${locale}: ${catalog[key]}`).toContain('delete');
  }
});

test('a submitted key never reads as sent in any locale', async () => {
  const read = async locale => JSON.parse(await readFile(resolve(root, `apps/i18n/${locale}/native.json`), 'utf8'));
  const english = await read('en');
  const keyOf = Object.fromEntries(Object.entries(english).map(([key, text]) => [text, key]));
  // "Nothing was submitted" may say "nothing was sent": that claims less, not more.
  const submitted = Object.entries(english).filter(([, text]) => /submitted/i.test(text) && !/(not|nothing was|no payment was) submitted/i.test(text));
  const pairs = submitted.map(([key, text]) => [key, text, keyOf[text.replace('Submitted', 'Sent').replace('submitted', 'sent')]]).filter(([, , sent]) => sent);
  expect(pairs.map(([, text]) => text)).toEqual(expect.arrayContaining(['Payment submitted', 'Submitted']));
  const vocabulary = { es: /iniciad/i, ja: /受け?付/, ko: /제출/, sv: /inskickad|skickats in/i };
  for (const locale of locales.slice(1)) {
    const catalog = await read(locale);
    for (const [key, text, sent] of pairs) expect(`${locale} ${text}: ${catalog[key]}`).not.toBe(`${locale} ${text}: ${catalog[sent]}`);
    for (const [key, text] of submitted) expect(`${locale} ${text}: ${catalog[key]}`).toMatch(vocabulary[locale]);
  }
});
