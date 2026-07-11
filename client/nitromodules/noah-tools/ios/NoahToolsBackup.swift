import CommonCrypto
import CryptoKit
import Foundation
import NitroModules
import ZIPFoundation

extension NoahTools {
    internal func performEncryptWalletSnapshot(
        snapshotPath: String,
        manifestJson: String,
        destinationPath: String,
        mnemonic: String
    ) throws -> Promise<BackupFileInfo> {
        return Promise.async {
            let fileManager = FileManager.default
            let snapshotURL = URL(fileURLWithPath: snapshotPath)
            let destinationURL = URL(fileURLWithPath: destinationPath)
            let parentURL = destinationURL.deletingLastPathComponent()
            let workURL = parentURL.appendingPathComponent(".noah-backup-\(UUID().uuidString)")
            let zipURL = parentURL.appendingPathComponent(".noah-backup-\(UUID().uuidString).zip")

            guard fileManager.fileExists(atPath: snapshotURL.path) else {
                throw NSError(
                    domain: "NoahTools", code: 20,
                    userInfo: [NSLocalizedDescriptionKey: "Wallet snapshot does not exist"])
            }
            guard !fileManager.fileExists(atPath: destinationURL.path) else {
                throw NSError(
                    domain: "NoahTools", code: 21,
                    userInfo: [NSLocalizedDescriptionKey: "Encrypted backup destination already exists"])
            }

            defer {
                try? fileManager.removeItem(at: workURL)
                try? fileManager.removeItem(at: zipURL)
            }
            do {
                try fileManager.createDirectory(
                    at: workURL, withIntermediateDirectories: false, attributes: nil)
                try manifestJson.write(
                    to: workURL.appendingPathComponent("manifest.json"),
                    atomically: true,
                    encoding: .utf8
                )
                try fileManager.copyItem(
                    at: snapshotURL, to: workURL.appendingPathComponent("db.sqlite"))
                try fileManager.zipItem(
                    at: workURL, to: zipURL, shouldKeepParent: false,
                    compressionMethod: .deflate)

                let encrypted = try self.encryptV2(
                    data: Data(contentsOf: zipURL), mnemonic: mnemonic)
                try encrypted.write(to: destinationURL, options: [.atomic])
                let attributes = try fileManager.attributesOfItem(atPath: destinationURL.path)
                let size = (attributes[.size] as? NSNumber)?.doubleValue ?? 0
                return BackupFileInfo(
                    path: destinationURL.path,
                    sizeBytes: size,
                    sha256: try self.sha256File(url: destinationURL)
                )
            } catch {
                try? fileManager.removeItem(at: destinationURL)
                throw error
            }
        }
    }

    internal func performDecryptWalletBackup(
        encryptedPath: String,
        destinationDirectory: String,
        mnemonic: String
    ) throws -> Promise<DecryptedBackupInfo> {
        return Promise.async {
            let fileManager = FileManager.default
            let encryptedURL = URL(fileURLWithPath: encryptedPath)
            let destinationURL = URL(fileURLWithPath: destinationDirectory)
            let zipURL = destinationURL.deletingLastPathComponent()
                .appendingPathComponent(".noah-restore-\(UUID().uuidString).zip")

            guard !fileManager.fileExists(atPath: destinationURL.path) else {
                throw NSError(
                    domain: "NoahTools", code: 22,
                    userInfo: [NSLocalizedDescriptionKey: "Restore destination already exists"])
            }

            defer {
                try? fileManager.removeItem(at: zipURL)
            }
            do {
                let decrypted = try self.decryptV2(
                    data: Data(contentsOf: encryptedURL), mnemonic: mnemonic)
                try decrypted.write(to: zipURL, options: [.atomic])
                try fileManager.createDirectory(
                    at: destinationURL, withIntermediateDirectories: false, attributes: nil)
                try fileManager.unzipItem(at: zipURL, to: destinationURL)

                let manifestURL = destinationURL.appendingPathComponent("manifest.json")
                let snapshotURL = destinationURL.appendingPathComponent("db.sqlite")
                guard fileManager.fileExists(atPath: snapshotURL.path) else {
                    throw NSError(
                        domain: "NoahTools", code: 23,
                        userInfo: [NSLocalizedDescriptionKey: "Backup does not contain db.sqlite"])
                }
                let manifestJson = try String(contentsOf: manifestURL, encoding: .utf8)
                return DecryptedBackupInfo(
                    manifestJson: manifestJson,
                    snapshotPath: snapshotURL.path
                )
            } catch {
                try? fileManager.removeItem(at: destinationURL)
                throw error
            }
        }
    }

    internal func performInstallWalletSnapshot(snapshotPath: String, walletDataPath: String) throws
        -> Promise<String>
    {
        return Promise.async {
            let fileManager = FileManager.default
            let snapshotURL = URL(fileURLWithPath: snapshotPath)
            let walletURL = URL(fileURLWithPath: walletDataPath)
            let parentURL = walletURL.deletingLastPathComponent()
            let installURL = parentURL.appendingPathComponent(".wallet-install-\(UUID().uuidString)")
            let rollbackURL = parentURL.appendingPathComponent(".wallet-rollback-\(UUID().uuidString)")

            try fileManager.createDirectory(
                at: installURL, withIntermediateDirectories: false, attributes: nil)
            do {
                try fileManager.copyItem(at: snapshotURL, to: installURL.appendingPathComponent("db.sqlite"))
                var rollbackPath = ""
                if fileManager.fileExists(atPath: walletURL.path) {
                    try fileManager.moveItem(at: walletURL, to: rollbackURL)
                    rollbackPath = rollbackURL.path
                }
                do {
                    try fileManager.moveItem(at: installURL, to: walletURL)
                } catch {
                    if !rollbackPath.isEmpty {
                        try? fileManager.moveItem(at: rollbackURL, to: walletURL)
                    }
                    throw error
                }
                return rollbackPath
            } catch {
                try? fileManager.removeItem(at: installURL)
                throw error
            }
        }
    }

    internal func performFinalizeWalletSnapshotInstall(rollbackPath: String) throws -> Promise<Void> {
        return Promise.async {
            if !rollbackPath.isEmpty {
                try? FileManager.default.removeItem(atPath: rollbackPath)
            }
        }
    }

    internal func performRollbackWalletSnapshotInstall(walletDataPath: String, rollbackPath: String)
        throws -> Promise<Void>
    {
        return Promise.async {
            let fileManager = FileManager.default
            if fileManager.fileExists(atPath: walletDataPath) {
                try fileManager.removeItem(atPath: walletDataPath)
            }
            if !rollbackPath.isEmpty && fileManager.fileExists(atPath: rollbackPath) {
                try fileManager.moveItem(atPath: rollbackPath, toPath: walletDataPath)
            }
        }
    }

    internal func performCreateBackup(mnemonic: String) throws -> Promise<String> {
        return Promise.async {
            let fileManager = FileManager.default
            let appVariant = try self.performGetAppVariant()

            guard
                let documentDirectory = fileManager.urls(
                    for: .documentDirectory, in: .userDomainMask
                )
                .first,
                let cacheDirectory = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)
                    .first
            else {
                throw NSError(
                    domain: "NoahTools", code: 10,
                    userInfo: [NSLocalizedDescriptionKey: "Could not access directories"])
            }

            let backupStagingURL = cacheDirectory.appendingPathComponent("backup_staging")
            let outputZipURL = cacheDirectory.appendingPathComponent(
                "noah_backup_\(Date().timeIntervalSince1970).zip")

            do {
                // 1. Clean up and create staging directory
                if fileManager.fileExists(atPath: backupStagingURL.path) {
                    try fileManager.removeItem(at: backupStagingURL)
                }
                try fileManager.createDirectory(
                    at: backupStagingURL, withIntermediateDirectories: true, attributes: nil)

                // 2. Define source paths
                let dataURL = documentDirectory.appendingPathComponent("noah-data-\(appVariant)")

                // 3. Copy directories to staging
                if fileManager.fileExists(atPath: dataURL.path) {
                    try fileManager.copyItem(
                        at: dataURL,
                        to: backupStagingURL.appendingPathComponent("noah-data-\(appVariant)"))
                }

                // 4. Zip the staging directory
                try fileManager.zipItem(
                    at: backupStagingURL, to: outputZipURL, shouldKeepParent: true,
                    compressionMethod: .deflate)

                // 5. Encrypt the zip file
                let backupData = try Data(contentsOf: outputZipURL)
                let encryptedData = try self.encrypt(data: backupData, mnemonic: mnemonic)

                // 6. Clean up staging and temporary zip
                try? fileManager.removeItem(at: backupStagingURL)
                try? fileManager.removeItem(at: outputZipURL)

                return encryptedData.base64EncodedString()
            } catch {
                // Clean up on error
                try? fileManager.removeItem(at: backupStagingURL)
                try? fileManager.removeItem(at: outputZipURL)
                throw error
            }
        }
    }

    internal func performRestoreBackup(encryptedData: String, mnemonic: String) throws -> Promise<
        Bool
    > {
        return Promise.async {
            let fileManager = FileManager.default
            let appVariant = try self.performGetAppVariant()

            guard
                let documentDirectory = fileManager.urls(
                    for: .documentDirectory, in: .userDomainMask
                )
                .first,
                let cacheDirectory = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)
                    .first
            else {
                throw NSError(
                    domain: "NoahTools", code: 11,
                    userInfo: [NSLocalizedDescriptionKey: "Could not access directories"])
            }

            let tempZipURL = cacheDirectory.appendingPathComponent("decrypted_backup.zip")
            let unzipDirectoryURL = cacheDirectory.appendingPathComponent("restored_backup")

            do {
                // 1. Decrypt the data
                guard let decodedData = Data(base64Encoded: encryptedData) else {
                    throw NSError(
                        domain: "NoahTools", code: 12,
                        userInfo: [NSLocalizedDescriptionKey: "Invalid base64 data"])
                }
                let decryptedData = try self.decrypt(data: decodedData, mnemonic: mnemonic)

                // 2. Write decrypted data to a temporary zip file
                try decryptedData.write(to: tempZipURL)

                // 3. Unzip the file
                if fileManager.fileExists(atPath: unzipDirectoryURL.path) {
                    try fileManager.removeItem(at: unzipDirectoryURL)
                }
                try fileManager.createDirectory(
                    at: unzipDirectoryURL, withIntermediateDirectories: true, attributes: nil)
                try fileManager.unzipItem(at: tempZipURL, to: unzipDirectoryURL)

                // 4. Define source and destination paths for restore
                let dataSourceURL = unzipDirectoryURL.appendingPathComponent(
                    "backup_staging/noah-data-\(appVariant)")

                let dataDestURL = documentDirectory.appendingPathComponent(
                    "noah-data-\(appVariant)")

                // 5. Clean up existing directories at destination
                if fileManager.fileExists(atPath: dataDestURL.path) {
                    try fileManager.removeItem(at: dataDestURL)
                }

                // 6. Move files from unzipped backup to final destination
                if fileManager.fileExists(atPath: dataSourceURL.path) {
                    try fileManager.moveItem(at: dataSourceURL, to: dataDestURL)
                }

                // 7. Clean up temporary files
                try? fileManager.removeItem(at: tempZipURL)
                try? fileManager.removeItem(at: unzipDirectoryURL)

                return true
            } catch {
                // Clean up on error
                try? fileManager.removeItem(at: tempZipURL)
                try? fileManager.removeItem(at: unzipDirectoryURL)
                throw error
            }
        }
    }

    // MARK: - Crypto Helper Methods

    internal func encrypt(data: Data, mnemonic: String) throws -> Data {
        let salt = generateRandomBytes(count: 16)
        let key = try deriveKey(from: mnemonic, salt: salt)
        let iv = generateRandomBytes(count: 12)
        let sealedBox = try AES.GCM.seal(data, using: key, nonce: AES.GCM.Nonce(data: iv))

        let version: [UInt8] = [1]
        var encryptedData = Data(version)
        encryptedData.append(salt)
        encryptedData.append(iv)
        encryptedData.append(sealedBox.ciphertext)
        encryptedData.append(sealedBox.tag)

        return encryptedData
    }

    internal func encryptV2(data: Data, mnemonic: String) throws -> Data {
        var encrypted = try encrypt(data: data, mnemonic: mnemonic)
        encrypted[encrypted.startIndex] = 2
        return encrypted
    }

    internal func decryptV2(data: Data, mnemonic: String) throws -> Data {
        guard data.first == 2 else {
            throw NSError(
                domain: "DecryptionError", code: 3,
                userInfo: [NSLocalizedDescriptionKey: "Unsupported backup version"])
        }
        var legacyLayout = data
        legacyLayout[legacyLayout.startIndex] = 1
        return try decrypt(data: legacyLayout, mnemonic: mnemonic)
    }

    internal func sha256File(url: URL) throws -> String {
        let digest = SHA256.hash(data: try Data(contentsOf: url))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    internal func decrypt(data: Data, mnemonic: String) throws -> Data {
        let version = data.prefix(1)
        guard version.first == 1 else {
            throw NSError(
                domain: "DecryptionError", code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Unsupported backup version"])
        }
        let salt = data.dropFirst(1).prefix(16)
        let iv = data.dropFirst(17).prefix(12)
        let ciphertext = data.dropFirst(29).dropLast(16)
        let tag = data.suffix(16)

        let key = try deriveKey(from: mnemonic, salt: salt)

        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: iv), ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(sealedBox, using: key)
    }

    internal func deriveKey(from mnemonic: String, salt: Data) throws -> SymmetricKey {
        let seedData = mnemonic.data(using: .utf8)!
        let derivedKey = try pbkdf2(
            password: seedData, salt: salt, iterations: 600_000, keyLength: 32)
        return SymmetricKey(data: derivedKey)
    }

    internal func generateRandomBytes(count: Int) -> Data {
        var bytes = Data(count: count)
        let result = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!)
        }
        guard result == errSecSuccess else {
            fatalError("Failed to generate random bytes")
        }
        return bytes
    }

    internal func pbkdf2(password: Data, salt: Data, iterations: Int, keyLength: Int) throws -> Data
    {
        var derivedKey = Data(count: keyLength)
        let result = derivedKey.withUnsafeMutableBytes { derivedKeyBytes in
            salt.withUnsafeBytes { saltBytes in
                password.withUnsafeBytes { passwordBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.baseAddress, password.count,
                        saltBytes.baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        derivedKeyBytes.baseAddress, keyLength
                    )
                }
            }
        }
        guard result == kCCSuccess else {
            throw NSError(
                domain: "CryptoError", code: Int(result),
                userInfo: [NSLocalizedDescriptionKey: "Key derivation failed"])
        }
        return derivedKey
    }
}
