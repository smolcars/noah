import { test, expect } from 'bun:test';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { check } from './check-fixture-twins.mjs';

const ios = 'apps/ios/NoahTests';
const android = 'apps/android/app/src/test/java/com/noahwallet';

function tree(files) {
  const root = mkdtempSync(join(tmpdir(), 'fixture-twins-'));
  for (const [path, text] of Object.entries({ 'apps/fixtures/README.md': '', ...files })) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  }
  return root;
}

test('a fixture both apps load passes', () => {
  expect(check(tree({
    'apps/fixtures/push-gate.json': '{}',
    [`${ios}/PushGateTests.swift`]: 'let cases = try Fixtures.load("push-gate", input: In.self, expected: Out.self)',
    [`${android}/PushGateTest.kt`]: 'listOf("push-gate", "qr-hub").flatMap { SharedFixtures.load(it) }',
  }))).toEqual([]);
});

test('a fixture one app loads names the other app and both fixes', () => {
  expect(check(tree({
    'apps/fixtures/push-gate.json': '{}',
    [`${ios}/PushGateTests.swift`]: 'try Fixtures.load("push-gate", input: In.self, expected: Out.self)',
    [`${android}/PushGateTest.kt`]: '// SharedFixtures.load("push-gate") once Android has the rule\nval other = "push-gate-extra"',
  }))).toEqual(['apps/fixtures/push-gate.json is loaded by no Android test. Add a test under apps/android/app/src/test that loads it, or add "push-gate.json android <reason>" to apps/fixtures/one-platform.txt.']);
});

test('a device-test loader is reported but does not count', () => {
  const [error] = check(tree({
    'apps/fixtures/qr-hub.json': '{}',
    [`${ios}/QRHubTests.swift`]: 'Fixtures.load("qr-hub", input: In.self, expected: Out.self)',
    'apps/android/app/src/androidTest/java/QRHubDeviceTest.kt': 'SharedFixtures.load("qr-hub")',
  }));
  expect(error).toContain('loaded by no Android test (apps/android/app/src/androidTest loads it, but pull requests do not run those tests)');
});

test('an exception excuses its fixture, and stale or malformed lines say to delete or fix them', () => {
  const files = {
    'apps/fixtures/push-gate.json': '{}',
    'apps/fixtures/qr-hub.json': '{}',
    'apps/fixtures/one-platform.txt': '# comment\npush-gate.json android No Android test loads it yet.\nqr-hub.json ios Stale.\ngone.json ios Removed.\nqr-hub.json\n',
    [`${ios}/Tests.swift`]: 'Fixtures.load("push-gate", input: In.self, expected: Out.self)\nlet url = root.appending(path: "fixtures/qr-hub.json")',
    [`${android}/QRHubTest.kt`]: 'javaClass.getResource("/qr-hub.json")',
  };
  expect(check(tree(files))).toEqual([
    'apps/fixtures/one-platform.txt:3 says no iOS test loads qr-hub.json, but one does now. Delete the line.',
    'apps/fixtures/one-platform.txt:4 names apps/fixtures/gone.json, which no longer exists. Delete the line.',
    'apps/fixtures/one-platform.txt:5 must read "<fixture> <ios|android> <reason>": qr-hub.json',
  ]);
});

test('a fixture neither app loads fails for both apps', () => {
  expect(check(tree({ 'apps/fixtures/rn-mmkv/noah-wallet-storage': '' }))).toHaveLength(2);
  expect(check(tree({
    'apps/fixtures/rn-mmkv/noah-wallet-storage': '',
    [`${ios}/MMKVTests.swift`]: 'let dir = repo.appending(path: "apps/fixtures/rn-mmkv")',
    [`${android}/MMKVTest.kt`]: 'getResourceAsStream("rn-mmkv/$name")',
  }))).toEqual([]);
});

// #423 (merge 2199bedbf) removed selectionTracksCurrentWalletRows from WalletScreenParityTests.swift, the only
// iOS test that loaded wallet-selection.json, and nothing noticed. These are the loader lines around that merge.
test('the #423 regression fails after the merge and not before', () => {
  const shared = {
    'apps/fixtures/wallet-selection.json': '{}',
    [`${android}/onchain/WalletScreenParityTest.kt`]: '        for (item in SharedFixtures.load("wallet-selection")) {',
  };
  const kept = '    @Test func recurringInputLimitsPreserveUnicodeBoundaries() {';
  const before = tree({ ...shared, [`${ios}/WalletScreenParityTests.swift`]: `        for item in try Fixtures.load("wallet-selection", input: Selection.self, expected: Selected.self) {\n${kept}` });
  const after = tree({ ...shared, [`${ios}/WalletScreenParityTests.swift`]: kept });
  expect(check(before)).toEqual([]);
  expect(check(after)).toEqual(['apps/fixtures/wallet-selection.json is loaded by no iOS test. Add a test under apps/ios/NoahTests that loads it, or add "wallet-selection.json ios <reason>" to apps/fixtures/one-platform.txt.']);
});
