// RN HomeScreen + banners + constants. Run: node apps/fixtures/tools/generate-home-parity.mjs
import { readFileSync, writeFileSync } from 'node:fs';
const rn = name => readFileSync(new URL(`../../../client/src/${name}`, import.meta.url), 'utf8');
const write = (name, cases) => writeFileSync(new URL(`../${name}.json`, import.meta.url), JSON.stringify({cases}, null, 2) + '\n');
const facts = JSON.parse(`[${rn('constants.ts').match(/BITCOIN_FACTS = msg\(\[([\s\S]*?)\]\);/)[1].trim().replace(/,$/, '')}]`);
write('home-facts', facts.map((expected, input) => ({name: `fact ${input}`, input, expected})));
const symbols = [...rn('lib/fiatCurrency.ts').matchAll(/^  (\w+): \{[^\n]+symbol: "([^"]+)"/gm)];
write('home-hidden-fiat', symbols.map(([,input,symbol]) => ({name: input, input, expected: `${symbol} ••••`})));
write('home-rounds', ['pending','unconfirmed','confirmed','failed','canceled','future_status'].flatMap(status => [false,true].map(isFinal => ({
  name: `${status}/${isFinal}`, input: {status, isFinal}, expected: !isFinal || status === 'pending' || status === 'unconfirmed',
}))));
const cases = [];
for (const status of ['idle','in_progress','failed','success']) for (const pending of [false,true]) {
  for (const elapsed of [0,4999,5000]) {
    const input = {lastBackupStatus: status, backupPending: pending, lastBackupAt: 10000, now: 10000 + elapsed};
    const expected = status === 'in_progress' ? 'Backing up wallet' : status === 'failed' ? 'Backup failed'
      : !pending && status === 'success' && elapsed < 5000 ? 'Backup completed' : null;
    cases.push({name: `${status}/${pending}/${elapsed}`, input, expected});
  }
}
cases.push({name:'missing success timestamp',input:{lastBackupStatus:'success',backupPending:false,now:10000},expected:null});
write('home-backup', cases);
