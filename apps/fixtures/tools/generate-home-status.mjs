// Run from the repo root. Evaluate the portable RN predicates and copy its facts/symbols.
import { readFileSync, writeFileSync } from 'node:fs';
const read = (path) => readFileSync(`client/src/${path}`, 'utf8');
const roundSource = read('components/PendingRoundStatusBanner.tsx');
const activeExpression = roundSource.match(/const isRoundActive = .*?=>\s*([^;]+);/s)[1];
const active = new Function('round', `return ${activeExpression}`);
const backupSource = read('components/BackupStatusBanner.tsx');
const successExpression = backupSource.match(/const showSuccess =\s*([^;]+);/s)[1];
const success = new Function('backupPending', 'lastBackupStatus', 'lastBackupAt', 'now', 'AUTO_BACKUP_SUCCESS_BANNER_MS', `return ${successExpression}`);
const cases = [];
for (const status of ['idle', 'in_progress', 'failed', 'success']) for (const pending of [false, true]) for (const elapsed of [0, 4999, 5000, 5001]) {
  const at = 10000, now = at + elapsed;
  const expected = status === 'in_progress' ? 'Backing up wallet' : status === 'failed' ? 'Backup failed' : success(pending, status, at, now, 5000) ? 'Backup completed' : null;
  cases.push({ name: `${status}, pending=${pending}, elapsed=${elapsed}`, input: { operation: 'backup', status, pending, at, now }, expected });
}
cases.push({ name: 'success without a timestamp', input: { operation: 'backup', status: 'success', pending: false, now: 10000 }, expected: null });
for (const status of ['pending', 'unconfirmed', 'confirmed', 'failed', 'canceled']) for (const final of [true, false]) {
  cases.push({ name: `${status}, final=${final}`, input: { operation: 'round', status, final }, expected: active({ status, is_final: final }) });
}
const facts = new Function(`return ${read('constants.ts').match(/BITCOIN_FACTS = msg\((\[[\s\S]*?\])\);/)[1]}`)();
const symbols = Object.fromEntries([...read('lib/fiatCurrency.ts').matchAll(/(\w+): \{ code: "\w+", name: "[^"]+", symbol: "([^"]+)"/g)].map(([, code, symbol]) => [code, symbol]));
writeFileSync('apps/fixtures/home-status.json', JSON.stringify({ description: 'RN Home status predicates, five-second expiry, facts and hidden-balance currency symbols.', facts, symbols, cases }, null, 2) + '\n');
