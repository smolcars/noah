#!/usr/bin/env node

const crypto = require("crypto");
const fs = require("fs");
const { promisify } = require("util");
const path = require("node:path");
const { openArchive } = require("./backup_archive");

const readFile = promisify(fs.readFile);
const writeFile = promisify(fs.writeFile);

/**
 * Derive encryption key from mnemonic using PBKDF2
 */
function deriveKey(mnemonic, salt) {
  return crypto.pbkdf2Sync(
    mnemonic,
    salt,
    600_000, // iterations
    32, // key length
    "sha256",
  );
}

/**
 * Decrypt Noah backup data
 */
function decryptBackup(encryptedData, mnemonic) {
  // Parse the encrypted data structure
  const version = encryptedData[0];
  if (version !== 1 && version !== 2) {
    throw new Error(`Unsupported backup version: ${version}`);
  }

  if (encryptedData.length < 45) throw new Error("Truncated backup envelope");

  const salt = encryptedData.slice(1, 17);
  const iv = encryptedData.slice(17, 29);
  const tag = encryptedData.slice(-16);
  const ciphertext = encryptedData.slice(29, -16);

  console.log(`Version: ${version}`);
  console.log(`Salt length: ${salt.length}`);
  console.log(`IV length: ${iv.length}`);
  console.log(`Ciphertext length: ${ciphertext.length}`);
  console.log(`Tag length: ${tag.length}`);

  console.log("Deriving key from mnemonic...");
  const key = deriveKey(mnemonic, salt);

  console.log("Decrypting backup...");
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);

  const decrypted = Buffer.concat([
    decipher.update(ciphertext),
    decipher.final(),
  ]);

  return decrypted;
}

/**
 * Main function
 */
async function main() {
  const args = process.argv.slice(2);

  if (args.length < 3 || args.includes("--help") || args.includes("-h")) {
    console.log(`
Noah Backup Decryption Tool

Usage:
  node decrypt_backup.js <input_file> <output_file> "<mnemonic>" [--extract <directory>]

Arguments:
  input_file   Binary v1/v2 envelope or base64 encoded envelope
  output_file  Path to output decrypted zip file
  mnemonic     12 or 24 word mnemonic phrase (in quotes)
  --extract    Also extract validated v2 manifest.json + db.sqlite to a NEW directory

Examples:
  # Decrypt backup
  node scripts/decrypt_backup.js backup.txt backup.zip "word1 word2 ... word12"

Security Warning:
  This script handles sensitive wallet data. Always keep your mnemonic secure
  and delete decrypted backups after inspection. The output zip and optional
  extraction contain private wallet data. No mnemonic, key or plaintext is printed.
  The phrase is a command-line argument and may be visible in process listings.
`);
    process.exit(args.includes("--help") || args.includes("-h") ? 0 : 1);
  }

  const inputFile = args[0];
  const outputFile = args[1];
  const mnemonic = args[2];
  const extraction = args.length === 5 && args[3] === "--extract" ? args[4] : null;

  try {
    if (args.length !== 3 && !extraction) throw new Error("Invalid arguments; use --help");
    console.log(`Reading encrypted backup from: ${inputFile}`);

    // Read as binary first
    let encryptedData = await readFile(inputFile);
    console.log(`Raw file size: ${encryptedData.length} bytes`);

    // Check if it's base64 text or binary
    const firstByte = encryptedData[0];

    // A raw envelope starts with version 1 or 2; otherwise try base64 text.
    if (firstByte !== 1 && firstByte !== 2) {
      console.log("File appears to be base64 encoded text, decoding...");
      const base64String = encryptedData.toString("utf8").trim();

      try {
        encryptedData = Buffer.from(base64String, "base64");
        console.log(`Decoded from base64, size: ${encryptedData.length} bytes`);
      } catch (err) {
        throw new Error("Failed to decode base64: " + err.message);
      }
    } else {
      console.log("File is already in binary format");
    }

    console.log(`Encrypted data size: ${encryptedData.length} bytes`);

    const decryptedData = decryptBackup(encryptedData, mnemonic);
    if (encryptedData[0] === 2) openArchive(decryptedData);
    console.log(`Decrypted data size: ${decryptedData.length} bytes`);

    if (extraction) {
      if (encryptedData[0] !== 2) throw new Error("--extract requires a v2 backup");
      extractArchive(decryptedData, extraction);
    }

    console.log(`Writing decrypted backup to: ${outputFile}`);
    await writeFile(outputFile, decryptedData, { mode: 0o600 });

    console.log("✓ Backup decrypted successfully!");
  } catch (err) {
    console.error(`✗ Error: ${err.message}`);
    if (err.code === "ENOENT") {
      console.error(`  File not found: ${err.path}`);
    }
    process.exit(1);
  }
}

// Validate completely before creating anything; never overwrite an existing directory.
function extractArchive(zip, destination) {
  const { files } = openArchive(zip);
  fs.mkdirSync(destination, { mode: 0o700 });
  try {
    for (const [name, bytes] of Object.entries(files)) {
      fs.writeFileSync(path.join(destination, name), bytes, { flag: "wx", mode: 0o600 });
    }
  } catch (error) {
    fs.rmSync(destination, { recursive: true, force: true });
    throw error;
  }
}

// Run if executed directly
if (require.main === module) {
  main();
}

module.exports = { decryptBackup, deriveKey, extractArchive };
