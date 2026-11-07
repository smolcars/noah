// RN Home round status. Run: node apps/fixtures/tools/generate-home-parity.mjs
import { writeFileSync } from 'node:fs';
const write = (name, cases) => writeFileSync(new URL(`../${name}.json`, import.meta.url), JSON.stringify({cases}, null, 2) + '\n');
write('home-rounds', ['pending','unconfirmed','confirmed','failed','canceled','future_status'].flatMap(status => [false,true].map(isFinal => ({
  name: `${status}/${isFinal}`, input: {status, isFinal}, expected: !isFinal || status === 'pending' || status === 'unconfirmed',
}))));
