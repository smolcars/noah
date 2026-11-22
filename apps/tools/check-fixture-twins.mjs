#!/usr/bin/env bun
// Every shared fixture in apps/fixtures is loaded by an iOS test and an Android test that pull requests run,
// unless apps/fixtures/one-platform.txt names the app that lacks it and why.
// Usage: bun apps/tools/check-fixture-twins.mjs [repo root]
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const exceptionsPath = 'apps/fixtures/one-platform.txt';
const notFixtures = new Set(['README.md', 'tools', 'one-platform.txt']);
// What pull requests run: native-ios.yml runs -only-testing:NoahTests; native-android.yml runs
// testRegtestDebugUnitTest and only compiles androidTest.
const apps = {
  ios: { name: 'iOS', tests: 'apps/ios/NoahTests', ext: '.swift' },
  android: { name: 'Android', tests: 'apps/android/app/src/test', ext: '.kt', unrun: 'apps/android/app/src/androidTest' },
};

function sources(dir, ext) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter(entry => entry.isFile() && entry.name.endsWith(ext))
    .map(entry => readFileSync(join(entry.parentPath, entry.name), 'utf8'));
}

// String literals outside line comments. Fixtures are named by literal: Fixtures.load("push-gate", ...),
// SharedFixtures.load("push-gate"), listOf("exit-timeline", ...) fed to a loader, or a path such as
// "fixtures/recurring-storage.json", "/recurring-rn-range.json" or "rn-mmkv/$name".
function literals(dir, ext) {
  const found = new Set();
  for (const source of sources(dir, ext)) {
    for (const line of source.split('\n')) {
      if (/^\s*(?:\/\/|\/\*|\*)/.test(line)) continue;
      for (const [, literal] of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) found.add(literal);
    }
  }
  return found;
}

// shortcut: a bare literal equal to the stem counts even when it is not passed to a loader;
// parse call sites if a generic stem such as "balance" ever hides a missing loader.
const loads = (found, fixture) => {
  const stem = fixture.replace(/\.[^.]+$/, '');
  return [...found].some(literal => literal === stem || literal.split('/').includes(fixture));
};

export function check(root) {
  const fixtures = readdirSync(join(root, 'apps/fixtures')).filter(name => !notFixtures.has(name)).sort();
  const loaded = Object.fromEntries(Object.entries(apps).map(([key, app]) => [key, {
    run: literals(join(root, app.tests), app.ext),
    unrun: app.unrun ? literals(join(root, app.unrun), app.ext) : new Set(),
  }]));

  const errors = [];
  const excused = new Set();
  const file = join(root, exceptionsPath);
  const lines = existsSync(file) ? readFileSync(file, 'utf8').split('\n') : [];
  lines.forEach((line, index) => {
    if (!line.trim() || line.startsWith('#')) return;
    const where = `${exceptionsPath}:${index + 1}`;
    const [, fixture, key, reason] = line.match(/^(\S+) (ios|android) (\S.*)$/) ?? [];
    if (!reason) {
      errors.push(`${where} must read "<fixture> <ios|android> <reason>": ${line}`);
    } else if (!fixtures.includes(fixture)) {
      errors.push(`${where} names apps/fixtures/${fixture}, which no longer exists. Delete the line.`);
    } else if (loads(loaded[key].run, fixture)) {
      errors.push(`${where} says no ${apps[key].name} test loads ${fixture}, but one does now. Delete the line.`);
    } else {
      excused.add(`${fixture} ${key}`);
    }
  });

  for (const fixture of fixtures) {
    for (const [key, app] of Object.entries(apps)) {
      if (excused.has(`${fixture} ${key}`) || loads(loaded[key].run, fixture)) continue;
      const unrun = loads(loaded[key].unrun, fixture) ? ` (${app.unrun} loads it, but pull requests do not run those tests)` : '';
      errors.push(`apps/fixtures/${fixture} is loaded by no ${app.name} test${unrun}. Add a test under ${app.tests} that loads it, or add "${fixture} ${key} <reason>" to ${exceptionsPath}.`);
    }
  }
  return errors;
}

if (import.meta.main) {
  const root = resolve(process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '../..'));
  const errors = check(root);
  for (const error of errors) console.error(error);
  if (errors.length) process.exit(1);
  console.log('Every shared fixture is loaded by both apps\' tests or listed in ' + exceptionsPath + '.');
}
