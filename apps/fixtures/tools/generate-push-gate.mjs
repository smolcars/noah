// RN Navigators.tsx + Expo's iOS general permission mapping (58.1.1).
// Run: node apps/fixtures/tools/generate-push-gate.mjs
import { writeFileSync } from 'node:fs';
const cases = [];
for (const initialized of [false, true]) for (const physical of [false, true]) {
  for (const authorization of ['checking', 'notDetermined', 'denied', 'authorized', 'provisional', 'ephemeral']) {
    const status = authorization === 'authorized' ? 'granted' : authorization === 'denied' ? 'denied'
      : authorization === 'checking' ? 'checking' : 'undetermined';
    cases.push({name: `${initialized}/${physical}/${authorization}`, input: { initialized, physical, authorization, status },
      expected: initialized && physical && status === 'denied'});
  }
}
writeFileSync(new URL('../push-gate.json', import.meta.url), JSON.stringify({cases}, null, 2) + '\n');
