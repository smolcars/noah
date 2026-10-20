const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { decryptBackup, extractArchive } = require('./decrypt_backup');
const { openArchive, parseManifest } = require('./backup_archive');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const fixtures = name => JSON.parse(fs.readFileSync(path.join(__dirname, '../apps/fixtures', `${name}.json`))).cases;

for (const domain of ['backup-crypto', 'backup-crypto-unicode']) {
  for (const { name, input, expected } of fixtures(domain)) test(name, () => {
    assert.deepEqual(decryptBackup(Buffer.from(expected.envelopeHex, 'hex'), input.mnemonic), Buffer.from(input.plaintextHex, 'hex'));
  });
}
const archiveFixtures = fixtures('backup-archives');
const v2 = archiveFixtures.find(({ input }) => input.kind === 'v2');
const zip = decryptBackup(Buffer.from(v2.input.fileBase64, 'base64'), v2.input.mnemonic);
openArchive(zip);

for (const fixture of archiveFixtures) test(`${fixture.name}: binary and base64 CLI`, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'noah-decrypt-test-'));
  try {
    const envelope = Buffer.from(fixture.input.fileBase64 ?? fixture.input.fileText, 'base64');
    for (const [index, data] of [envelope, envelope.toString('base64')].entries()) {
      const input = path.join(root, `input-${index}`), output = path.join(root, `output-${index}.zip`);
      fs.writeFileSync(input, data);
      const extraction = path.join(root, `extracted-${index}`);
      const result = spawnSync(process.execPath, [path.join(__dirname, 'decrypt_backup.js'), input, output,
        fixture.input.mnemonic, ...(envelope[0] === 2 ? ['--extract', extraction] : [])], { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
      assert.equal(result.stdout.includes(fixture.input.mnemonic), false);
      assert.equal(fs.readFileSync(output).readUInt32LE(), 0x04034b50);
      if (envelope[0] === 2) {
        assert.deepEqual(fs.readdirSync(extraction).sort(), ['db.sqlite', 'manifest.json']);
        assert.deepEqual(JSON.parse(fs.readFileSync(path.join(extraction, 'manifest.json'))), fixture.expected.manifest);
        assert.equal(sha(fs.readFileSync(path.join(extraction, 'db.sqlite'))), fixture.expected.files['db.sqlite']);
      }
    }
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

for (const [name, mutation] of Object.entries({
  'future format': m => { m.formatVersion = 3; },
  'missing nullable key': m => { delete m.serverPubkey; },
  'wrong nullable type': m => { m.mailboxPubkey = 1; },
  'string schema': m => { m.barkSchemaVersion = '42'; },
  'fractional schema': m => { m.barkSchemaVersion = 1.5; },
  'boolean schema': m => { m.barkSchemaVersion = true; },
  'wrong string type': m => { m.createdAt = 42; },
})) test(`strict manifest rejects ${name}`, () => {
  const manifest = { ...v2.expected.manifest }; mutation(manifest);
  assert.throws(() => parseManifest(Buffer.from(JSON.stringify(manifest))));
});

test('unknown manifest fields remain compatible', () => {
  assert.equal(parseManifest(Buffer.from(JSON.stringify({ ...v2.expected.manifest, future: true }))).formatVersion, 2);
});

// Mutate actual independently written ZIP headers: rejection must precede disk writes.
for (const [name, mutation] of Object.entries({
  'unknown entry': bytes => { bytes.write('xx.sqlite', bytes.indexOf('db.sqlite')); },
  'CRC mismatch': bytes => { const central = bytes.indexOf(Buffer.from('504b0102', 'hex')); bytes.writeUInt32LE(123, central + 16); },
  'symlink': bytes => { const central = bytes.indexOf(Buffer.from('504b0102', 'hex')); bytes.writeUInt32LE(0xa1ff0000, central + 38); },
  'manifest exceeds cap': bytes => { const central = bytes.indexOf(Buffer.from('504b0102', 'hex')); bytes.writeUInt32LE(65537, central + 24); },
  'multidisk': bytes => { bytes.writeUInt16LE(1, bytes.length - 18); },
  'missing snapshot': bytes => { bytes.writeUInt16LE(1, bytes.length - 12); bytes.writeUInt16LE(1, bytes.length - 14); },
})) test(`archive rejects ${name} without output`, () => {
  const corrupted = Buffer.from(zip); mutation(corrupted);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'noah-decrypt-test-'));
  const destination = path.join(root, 'extracted');
  try { assert.throws(() => extractArchive(corrupted, destination)); assert.equal(fs.existsSync(destination), false); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('extraction never overwrites an existing directory', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'noah-decrypt-test-'));
  try { fs.writeFileSync(path.join(root, 'keep'), 'original'); assert.throws(() => extractArchive(zip, root)); assert.deepEqual(fs.readdirSync(root), ['keep']); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('bad password, authentication tag, future version and truncation are rejected', () => {
  const envelope = Buffer.from(v2.input.fileBase64, 'base64');
  assert.throws(() => decryptBackup(envelope, 'unrelated public test password'));
  const damaged = Buffer.from(envelope); damaged[damaged.length - 1] ^= 1;
  assert.throws(() => decryptBackup(damaged, v2.input.mnemonic));
  damaged[0] = 3; assert.throws(() => decryptBackup(damaged, v2.input.mnemonic));
  assert.throws(() => decryptBackup(Buffer.from([2]), v2.input.mnemonic));
});

test('native service exports have matching hashes and strict manifests', () => {
  const file = path.join(__dirname, '../apps/fixtures/native-backups.json');
  assert.ok(fs.existsSync(file), 'generate both native exports first');
  for (const fixture of fixtures('native-backups')) {
    const result = openArchive(decryptBackup(Buffer.from(fixture.input.fileBase64, 'base64'), fixture.input.mnemonic));
    assert.equal(result.manifest.walletFingerprint, fixture.expected.fingerprint);
    assert.equal(result.manifest.network, 'regtest');
    assert.equal(result.files['db.sqlite'].subarray(0, 16).toString(), 'SQLite format 3\0');
  }
});

// Stored ZIP writer for structural probes, independent of the parser and fixture writers.
function storedZip(entries) {
  const locals = [], directory = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const filename = Buffer.from(name), local = Buffer.alloc(30), central = Buffer.alloc(46);
    const crc = require('node:zlib').crc32(data);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(filename.length, 26);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    locals.push(local, filename, data); directory.push(central, filename);
    offset += local.length + filename.length + data.length;
  }
  const end = Buffer.alloc(22), central = Buffer.concat(directory);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(central.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, central, end]);
}
const validEntries = Object.entries(openArchive(zip).files);
for (const name of ['../db.sqlite', 'nested/db.sqlite', '/db.sqlite', 'notes.txt']) test(`rejects entry path ${name}`, () => {
  assert.throws(() => openArchive(storedZip([...validEntries, [name, Buffer.from('x')]])));
});
test('rejects duplicates including ./ aliases', () => {
  for (const name of ['db.sqlite', './db.sqlite']) assert.throws(() => openArchive(storedZip([...validEntries, [name, Buffer.from('x')]])));
});
test('accepts stored entries and optional ./ prefix', () => {
  assert.deepEqual(openArchive(storedZip(validEntries.map(([name, data]) => [`./${name}`, data]))).manifest, v2.expected.manifest);
});
test('rejects snapshot hash mismatch even with valid ZIP CRC', () => {
  assert.throws(() => openArchive(storedZip(validEntries.map(([name, data]) => [name, name === 'db.sqlite' ? Buffer.from('other database') : data]))), /SHA-256/);
});
