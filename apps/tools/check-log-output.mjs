#!/usr/bin/env bun
// Grep-style tripwire, not a language parser: route new sinks through reviewed logging files.
import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const allowed = new Set([
  'apps/ios/Noah/Services/Logging/AppLog.swift',
  'apps/ios/Noah/Services/Logging/AppTelemetry.swift',
  'apps/android/app/src/main/java/com/noahwallet/services/logging/AppLog.kt',
  'apps/android/app/src/main/java/com/noahwallet/services/logging/AppTelemetry.kt',
]);
const directOutput = /\b(?:print|println|debugPrint|dump|NSLog|NSLogv|os_log|os_signpost|__android_log_print|__android_log_write|printf|fprintf|fputs|puts|Logger|OSLog)\s*\(|\b(?:Log\s*\.\s*(?:[vdiwe]|wtf|println)|printStackTrace)\s*\(|\bSystem\s*\.\s*(?:out|err)\b|\bFileHandle\s*\.\s*standard(?:Output|Error)\b|\bSentry(?:SDK)?\s*\.\s*(?:capture\w*|start)\s*\(|\bimport\s+(?:OSLog\b|os\b|(?:static\s+)?android\.util\.Log\b|io\.sentry\.Sentry(?:\s|;|\.|$)|java\.util\.logging\b|timber\.log\b)/g;

function violations(path, source) {
  if (allowed.has(path)) return [];
  return [...source.matchAll(directOutput)].map(match =>
    `${path}:${source.slice(0, match.index).split('\n').length}: ${match[0].trim()}`);
}

// Keep the check itself honest without native toolchains or modifying production files.
for (const source of [
  'print("raw")', 'Swift.debugPrint ("raw")', 'NSLog("%@", error)', 'os_log("raw")',
  'let log = Logger(subsystem: "app", category: "raw")', 'FileHandle.standardError.write(data)',
  'android.util.Log.e("tag", error.message, error)', 'Log\n . println(1, "tag", text)',
  'import android.util.Log as Raw', 'import static android.util.Log.e;',
  'System.out.println(error)', 'error.printStackTrace()', 'SentrySDK.capture(message: text)',
  'io.sentry.Sentry.captureException(error)', 'import io.sentry.Sentry as Raw',
]) assert.ok(violations('new/Production.swift', source).length, source);
assert.equal(violations('new/Production.kt', 'AppLog("wallet", file).e(safe)').length, 0);
for (const path of allowed) assert.equal(violations(path, 'println("sink")').length, 0);

async function scan(path) {
  const found = [];
  for (const entry of await readdir(resolve(root, path), { withFileTypes: true })) {
    if (entry.isDirectory()) {
      // Android's test source sets and iOS test/driver targets are outside production.
      if (path !== 'apps/android/app/src' || !/^(?:test|androidTest)/.test(entry.name)) found.push(...await scan(`${path}/${entry.name}`));
    } else if (/\.(?:swift|kt|java|[cmh]|mm|cpp)$/.test(entry.name)) {
      const file = `${path}/${entry.name}`;
      found.push(...violations(file, await readFile(resolve(root, file), 'utf8')));
    }
  }
  return found;
}

const found = (await Promise.all([
  scan('apps/ios/Noah'), scan('apps/ios/NoahWidget'), scan('apps/android/app/src'),
])).flat();
if (found.length) {
  console.error('Direct output outside the logging allowlist; use AppLog/AppTelemetry:\n' + found.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`Native output check passed (${allowed.size} permitted logging files).`);
}
