// After BackupIntegrationTest.prepareNodeSnapshot, independently zip/seal its public test wallet.
// Run only against your disposable emulator; no real wallet seed or server credentials are read.
import { execFileSync } from 'node:child_process';
import { createHash, pbkdf2Sync, randomBytes, createCipheriv } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const serial = process.argv[2];
if (!/^emulator-\d+$/.test(serial ?? '') || ['emulator-5554', 'emulator-5556', 'emulator-5558'].includes(serial)) {
  throw new Error('Pass the serial of your own disposable emulator');
}
const root = mkdtempSync(join(tmpdir(), 'noah-node-backup-'));
process.on('exit', () => rmSync(root, { recursive: true, force: true }));
process.on('SIGINT', () => process.exit());
process.on('SIGTERM', () => process.exit());
for (const name of ['db.sqlite', 'manifest.json']) {
  const data = execFileSync('adb', ['-s', serial, 'exec-out', 'run-as', 'com.noahwallet.regtest',
    'cat', `cache/backup-node-fixture/${name}`], { maxBuffer: 32 * 1024 * 1024 });
  writeFileSync(join(root, name), data);
}
const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
if (createHash('sha256').update(readFileSync(join(root, 'db.sqlite'))).digest('hex') !== manifest.snapshotSha256) {
  throw new Error('Snapshot hash mismatch');
}
execFileSync('zip', ['-q', join(root, 'snapshot.zip'), 'manifest.json', 'db.sqlite'], { cwd: root });
// The standard public BIP39 vector used by prepareNodeSnapshot; never a user's seed.
const phrase = 'abandon '.repeat(11) + 'about';
const salt = randomBytes(16), nonce = randomBytes(12);
const key = pbkdf2Sync(phrase, salt, 600000, 32, 'sha256');
const cipher = createCipheriv('aes-256-gcm', key, nonce);
const encrypted = Buffer.concat([Buffer.from([2]), salt, nonce,
  cipher.update(readFileSync(join(root, 'snapshot.zip'))), cipher.final(), cipher.getAuthTag()]);
key.fill(0);
const backup = { backup_id: 'node-fixture', created_at: new Date().toISOString(),
  encrypted_sha256: createHash('sha256').update(encrypted).digest('hex'), encrypted_size: encrypted.length, format_version: 2 };
createServer((request, response) => {
  if (request.url === '/fixture') { response.writeHead(200, { 'Content-Length': encrypted.length }); response.end(encrypted); return; }
  response.setHeader('Content-Type', 'application/json');
  if (request.url === '/v0/backup/v2/list') response.end(JSON.stringify([backup]));
  else if (request.url === '/v0/backup/v2/download') response.end(JSON.stringify({ backup, download_url: 'http://10.0.2.2:3161/fixture' }));
  else { response.statusCode = 404; response.end('{}'); }
}).listen(3161, '127.0.0.1', () => process.stdout.write('Node fixture ready on port 3161. Ctrl-C removes the plaintext workspace.\n'));
