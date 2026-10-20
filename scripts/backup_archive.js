const { inflateRawSync, crc32 } = require('node:zlib');
const { createHash } = require('node:crypto');

const MAX_DATABASE = 1 << 30;
const MAX_MANIFEST = 64 * 1024;

function parseManifest(bytes) {
  const value = JSON.parse(bytes.toString('utf8'));
  if (!value || Array.isArray(value) || value.formatVersion !== 2 ||
      !['createdAt', 'snapshotSha256', 'network', 'walletFingerprint'].every(key => typeof value[key] === 'string') ||
      !['serverPubkey', 'mailboxPubkey'].every(key => value[key] === null || typeof value[key] === 'string') ||
      !Number.isInteger(value.barkSchemaVersion) || value.barkSchemaVersion < -2147483648 || value.barkSchemaVersion > 2147483647) {
    throw new Error('Invalid or unsupported backup manifest');
  }
  return value;
}

// Read the central directory, never filesystem paths supplied by the archive.
// V2 has just two small-count entries and needs neither ZIP64 nor split disks.
function openArchive(zip) {
  const invalid = () => { throw new Error('Invalid or unsupported v2 backup archive'); };
  const u16 = offset => zip.readUInt16LE(offset);
  const u32 = offset => zip.readUInt32LE(offset);
  let end = zip.length - 22;
  const earliest = Math.max(0, end - 65535);
  while (end >= earliest && (u32(end) !== 0x06054b50 || end + 22 + u16(end + 20) !== zip.length)) end--;
  if (end < earliest || u16(end + 4) || u16(end + 6) || u16(end + 8) !== u16(end + 10)) invalid();
  const count = u16(end + 10), directoryEnd = u32(end + 16) + u32(end + 12);
  let offset = u32(end + 16);
  if (count > 4 || directoryEnd !== end) invalid();
  const files = {};
  for (let index = 0; index < count; index++) {
    if (offset + 46 > end || u32(offset) !== 0x02014b50) invalid();
    const flags = u16(offset + 8), method = u16(offset + 10), crc = u32(offset + 16);
    const compressed = u32(offset + 20), size = u32(offset + 24), nameLength = u16(offset + 28);
    const next = offset + 46 + nameLength + u16(offset + 30) + u16(offset + 32);
    const local = u32(offset + 42), attributes = u32(offset + 38), mode = attributes >>> 16;
    if (next > end || flags & 1 || u16(offset + 34) || ![0, 8].includes(method)) invalid();
    const rawName = zip.subarray(offset + 46, offset + 46 + nameLength).toString('utf8');
    const name = rawName.startsWith('./') ? rawName.slice(2) : rawName;
    offset = next;
    if ((name === '' || name === '/') && rawName.endsWith('/') && size === 0) continue;
    if (!['manifest.json', 'db.sqlite'].includes(name) || Object.hasOwn(files, name) ||
        (attributes & 0x10) || (mode & 0xf000) && (mode & 0xf000) !== 0x8000) invalid();
    const limit = name === 'manifest.json' ? MAX_MANIFEST : MAX_DATABASE;
    if (size > limit || local + 30 > u32(end + 16) || u32(local) !== 0x04034b50 ||
        u16(local + 6) !== flags || u16(local + 8) !== method) invalid();
    const localNameEnd = local + 30 + u16(local + 26);
    if (zip.subarray(local + 30, localNameEnd).toString('utf8') !== rawName) invalid();
    const start = localNameEnd + u16(local + 28);
    if (start + compressed > u32(end + 16)) invalid();
    const packed = zip.subarray(start, start + compressed);
    const data = method === 0 ? packed : inflateRawSync(packed, { maxOutputLength: limit });
    if (data.length > limit || data.length !== size || crc32(data) !== crc) invalid();
    files[name] = data;
  }
  if (offset !== end || !files['manifest.json'] || !files['db.sqlite']) invalid();
  const manifest = parseManifest(files['manifest.json']);
  if (createHash('sha256').update(files['db.sqlite']).digest('hex') !== manifest.snapshotSha256) {
    throw new Error('Snapshot SHA-256 does not match manifest');
  }
  return { manifest, files };
}

module.exports = { openArchive, parseManifest };
