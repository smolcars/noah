// useAppVersionCheck.ts: trust the server decision, fail open, cache indefinitely.
// Run: node apps/fixtures/tools/generate-app-version.mjs
import {writeFileSync} from 'node:fs';
const cases = [
  ['current', '0.2.0', {update_required:false,minimum_required_version:'0.0.1'}],
  ['update', '0.2.0', {update_required:true,minimum_required_version:'1.0.0'}],
  ['server decision is authoritative', '2.0.0', {update_required:true,minimum_required_version:'1.0.0'}],
  ['offline', '0.2.0', null],
].map(([name,currentVersion,response])=>({name,input:{currentVersion,response},expected:response ?? {update_required:false,minimum_required_version:'0.0.1'}}));
writeFileSync(new URL('../app-version.json',import.meta.url),JSON.stringify({cases},null,2)+'\n');
