// QRHubScreen + useQRCodeScanner + QRCodeScanner paste policy in RN.
// Run: node apps/fixtures/tools/generate-qr-hub.mjs
import {writeFileSync} from 'node:fs';
const cases = [
  ['address','alice@pay.example.com',false], ['ark','tark1example',false],
  ['unified','bitcoin:bcrt1example?amount=0.00001',false], ['invoice','lnbcrt1example',false],
  ['invalid is sent to importer','invalid request',false], ['empty scan','',false],
  ['empty paste','  \n',true], ['paste preserves input',' alice@pay.example.com ',true],
].map(([name,value,pasted])=>({name,input:{value,pasted},expected:(pasted ? value.trim() : value).length ? value : null}));
writeFileSync(new URL('../qr-hub.json',import.meta.url),JSON.stringify({cases},null,2)+'\n');
