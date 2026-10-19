// Supplement to the existing vectors: UTF-8 password bytes, including non-BMP characters.
import { pbkdf2Sync, createCipheriv } from 'node:crypto';
import { writeFileSync } from 'node:fs';
const input = {
  version: 2,
  mnemonic: 'あいこくしん café 🪙',
  saltHex: '00112233445566778899aabbccddeeff',
  nonceHex: '102132435465768798a9bacb',
  plaintextHex: '000102ff',
};
const salt = Buffer.from(input.saltHex, 'hex');
const nonce = Buffer.from(input.nonceHex, 'hex');
const key = pbkdf2Sync(Buffer.from(input.mnemonic, 'utf8'), salt, 600_000, 32, 'sha256');
const cipher = createCipheriv('aes-256-gcm', key, nonce);
const encrypted = Buffer.concat([cipher.update(Buffer.from(input.plaintextHex, 'hex')), cipher.final()]);
const envelopeHex = Buffer.concat([Buffer.from([2]), salt, nonce, encrypted, cipher.getAuthTag()]).toString('hex');
writeFileSync(new URL('../backup-crypto-unicode.json', import.meta.url), JSON.stringify({
  description: 'Supplemental Node crypto vector verifying UTF-8 passwords without normalization.',
  cases: [{ name: 'v2 UTF-8 mnemonic bytes', input, expected: { envelopeHex } }],
}, null, 2) + '\n');
