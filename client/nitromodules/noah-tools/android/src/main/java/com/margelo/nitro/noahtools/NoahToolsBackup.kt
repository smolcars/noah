package com.margelo.nitro.noahtools

import android.util.Base64
import android.util.Log
import com.margelo.nitro.core.Promise
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object NoahToolsBackup {
    private const val TAG = "com.noah.app"
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16
    private const val KEY_LENGTH = 256
    private const val PBKDF2_ITERATIONS = 600_000
    private const val GCM_TAG_LENGTH = 128
    private const val BUFFER_SIZE = 8192

    fun performEncryptWalletSnapshot(
        snapshotPath: String,
        manifestJson: String,
        destinationPath: String,
        mnemonic: String
    ): Promise<BackupFileInfo> {
        return Promise.async {
            val snapshot = File(snapshotPath)
            val destination = File(destinationPath)
            val parent = destination.parentFile
                ?: throw IllegalArgumentException("Encrypted backup destination has no parent")
            if (!snapshot.isFile) {
                throw IllegalArgumentException("Wallet snapshot does not exist")
            }
            if (destination.exists()) {
                throw IllegalArgumentException("Encrypted backup destination already exists")
            }

            val workDirectory = File(parent, ".noah-backup-${java.util.UUID.randomUUID()}")
            val zipFile = File(parent, ".noah-backup-${java.util.UUID.randomUUID()}.zip")
            try {
                if (!workDirectory.mkdir()) {
                    throw IllegalStateException("Failed to create backup staging directory")
                }
                File(workDirectory, "manifest.json").writeText(manifestJson)
                snapshot.copyTo(File(workDirectory, "db.sqlite"), overwrite = false)
                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                    zipDirectory(workDirectory, "", zipOut)
                }
                destination.writeBytes(encryptV2(zipFile.readBytes(), mnemonic))
                BackupFileInfo(
                    path = destination.absolutePath,
                    sizeBytes = destination.length().toDouble(),
                    sha256 = sha256File(destination)
                )
            } catch (error: Exception) {
                destination.delete()
                throw error
            } finally {
                workDirectory.deleteRecursively()
                zipFile.delete()
            }
        }
    }

    fun performDecryptWalletBackup(
        encryptedPath: String,
        destinationDirectory: String,
        mnemonic: String
    ): Promise<DecryptedBackupInfo> {
        return Promise.async {
            val encrypted = File(encryptedPath)
            val destination = File(destinationDirectory)
            val zipFile = File(
                destination.parentFile,
                ".noah-restore-${java.util.UUID.randomUUID()}.zip"
            )
            if (destination.exists()) {
                throw IllegalArgumentException("Restore destination already exists")
            }
            try {
                zipFile.writeBytes(decryptV2(encrypted.readBytes(), mnemonic))
                unzipFile(zipFile.absolutePath, destination.absolutePath)
                val manifest = File(destination, "manifest.json")
                val snapshot = File(destination, "db.sqlite")
                if (!manifest.isFile || !snapshot.isFile) {
                    throw IllegalArgumentException("Backup payload is incomplete")
                }
                DecryptedBackupInfo(
                    manifestJson = manifest.readText(),
                    snapshotPath = snapshot.absolutePath
                )
            } catch (error: Exception) {
                destination.deleteRecursively()
                throw error
            } finally {
                zipFile.delete()
            }
        }
    }

    fun performInstallWalletSnapshot(
        snapshotPath: String,
        walletDataPath: String
    ): Promise<String> {
        return Promise.async {
            val snapshot = File(snapshotPath)
            val walletDirectory = File(walletDataPath)
            val parent = walletDirectory.parentFile
                ?: throw IllegalArgumentException("Wallet data path has no parent")
            val installDirectory = File(parent, ".wallet-install-${java.util.UUID.randomUUID()}")
            val rollbackDirectory = File(parent, ".wallet-rollback-${java.util.UUID.randomUUID()}")
            if (!installDirectory.mkdir()) {
                throw IllegalStateException("Failed to create wallet install directory")
            }
            try {
                snapshot.copyTo(File(installDirectory, "db.sqlite"), overwrite = false)
                var rollbackPath = ""
                if (walletDirectory.exists()) {
                    if (!walletDirectory.renameTo(rollbackDirectory)) {
                        throw IllegalStateException("Failed to stage current wallet for rollback")
                    }
                    rollbackPath = rollbackDirectory.absolutePath
                }
                if (!installDirectory.renameTo(walletDirectory)) {
                    if (rollbackPath.isNotEmpty()) {
                        rollbackDirectory.renameTo(walletDirectory)
                    }
                    throw IllegalStateException("Failed to install wallet snapshot")
                }
                rollbackPath
            } catch (error: Exception) {
                installDirectory.deleteRecursively()
                throw error
            }
        }
    }

    fun performFinalizeWalletSnapshotInstall(rollbackPath: String): Promise<Unit> {
        return Promise.async {
            if (rollbackPath.isNotEmpty()) {
                File(rollbackPath).deleteRecursively()
            }
        }
    }

    fun performRollbackWalletSnapshotInstall(
        walletDataPath: String,
        rollbackPath: String
    ): Promise<Unit> {
        return Promise.async {
            val walletDirectory = File(walletDataPath)
            walletDirectory.deleteRecursively()
            if (rollbackPath.isNotEmpty()) {
                val rollbackDirectory = File(rollbackPath)
                if (rollbackDirectory.exists() && !rollbackDirectory.renameTo(walletDirectory)) {
                    throw IllegalStateException("Failed to roll back wallet snapshot installation")
                }
            }
        }
    }

    fun performCreateBackup(mnemonic: String): Promise<String> {
        return Promise.async {
            var backupStagingPath: File? = null
            var outputZipPath: File? = null

            try {
                Log.d(TAG, "Starting backup creation with mnemonic length: ${mnemonic.length}")

                if (mnemonic.isBlank()) {
                    throw IllegalArgumentException("Mnemonic cannot be empty")
                }

                Log.d(TAG, "Mnemonic validation passed")
                val appVariant = NoahToolsLogging.performGetAppVariant()
                Log.d(TAG, "App variant: $appVariant")

                Log.d(TAG, "Getting directories...")

                // For Nitro modules, we need to get the application context
                val appContext = NoahToolsLogging.getApplicationContext()
                Log.d(TAG, "Application context is null: ${appContext == null}")

                if (appContext == null) {
                    throw IllegalStateException("No application context available")
                }

                val documentDirectory = appContext.filesDir
                Log.d(TAG, "Document directory: ${documentDirectory?.absolutePath ?: "null"}")
                val cacheDirectory = appContext.cacheDir
                Log.d(TAG, "Cache directory: ${cacheDirectory?.absolutePath ?: "null"}")

                if (documentDirectory == null) {
                    throw IllegalStateException("Document directory is null")
                }
                if (cacheDirectory == null) {
                    throw IllegalStateException("Cache directory is null")
                }

                backupStagingPath = File(cacheDirectory, "backup_staging")
                outputZipPath = File(cacheDirectory, "noah_backup_${System.currentTimeMillis()}.zip")
                Log.d(TAG, "Staging path: ${backupStagingPath.absolutePath}")
                Log.d(TAG, "Output zip path: ${outputZipPath.absolutePath}")

                // 1. Clean up and create staging directory
                Log.d(TAG, "Cleaning and creating staging directory at ${backupStagingPath.absolutePath}")
                if (backupStagingPath.exists()) {
                    backupStagingPath.deleteRecursively()
                }
                backupStagingPath.mkdirs()

                // 2. Define source paths
                val dataPath = File(documentDirectory, "noah-data-${appVariant}")
                Log.d(TAG, "Data path: ${dataPath.absolutePath}")

                // 3. Copy directories to staging
                if (dataPath.exists()) {
                    Log.d(TAG, "Copying data directory")
                    dataPath.copyRecursively(File(backupStagingPath, "noah-data-${appVariant}"))
                } else {
                    Log.w(TAG, "Data directory not found")
                }

                // 4. Zip the staging directory
                Log.d(TAG, "Zipping the staging directory to ${outputZipPath.absolutePath}")
                ZipOutputStream(FileOutputStream(outputZipPath)).use { zipOut ->
                    zipDirectory(backupStagingPath, backupStagingPath.name, zipOut)
                }

                // 5. Encrypt the zip file
                Log.d(TAG, "Encrypting the zip file")
                val backupData = outputZipPath.readBytes()
                val encryptedBackup = encrypt(backupData, mnemonic)
                Log.d(TAG, "Encryption complete, returning Base64 encoded string")

                return@async Base64.encodeToString(encryptedBackup, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create backup", e)
                throw Exception("Failed to create backup: ${e.message}", e)
            } finally {
                // 6. Clean up staging and temporary zip
                Log.d(TAG, "Cleaning up temporary files")
                backupStagingPath?.let { if (it.exists()) it.deleteRecursively() }
                outputZipPath?.let { if (it.exists()) it.delete() }
            }
        }
    }

    fun performRestoreBackup(encryptedData: String, mnemonic: String): Promise<Boolean> {
        return Promise.async {
            val appVariant = NoahToolsLogging.performGetAppVariant()

            val appContext = NoahToolsLogging.getApplicationContext()
                ?: throw IllegalStateException("No application context available")

            val documentDirectory = appContext.filesDir
            val cacheDirectory = appContext.cacheDir

            val tempZipPath = File(cacheDirectory, "decrypted_backup.zip")
            val unzipDirectory = File(cacheDirectory, "restored_backup")

            try {
                // 1. Decrypt the data
                val decodedData = Base64.decode(encryptedData, Base64.NO_WRAP)
                val decryptedData = decrypt(decodedData, mnemonic)

                // 2. Write decrypted data to a temporary zip file
                tempZipPath.writeBytes(decryptedData)

                // 3. Unzip the file
                unzipFile(tempZipPath.absolutePath, unzipDirectory.absolutePath)

                // 4. Define source and destination paths for restore
                val dataSourcePath = File(unzipDirectory, "backup_staging/noah-data-${appVariant}")

                val dataDestPath = File(documentDirectory, "noah-data-${appVariant}")

                // 5. Clean up existing directories at destination
                if (dataDestPath.exists()) {
                    dataDestPath.deleteRecursively()
                }

                // 6. Move files from unzipped backup to final destination
                if (dataSourcePath.exists()) {
                    if (!dataSourcePath.renameTo(dataDestPath)) {
                        throw Exception("Failed to move noah-data directory")
                    }
                }

                return@async true
            } catch (e: Exception) {
                throw Exception("Failed to restore backup: ${e.message}", e)
            } finally {
                // 7. Clean up temporary files
                if (tempZipPath.exists()) {
                    tempZipPath.delete()
                }
                if (unzipDirectory.exists()) {
                    unzipDirectory.deleteRecursively()
                }
            }
        }
    }

    private fun encrypt(data: ByteArray, mnemonic: String): ByteArray {
        if (mnemonic.isBlank()) {
            throw IllegalArgumentException("Mnemonic cannot be empty")
        }

        val salt = generateRandomBytes(SALT_LENGTH)
        val key = deriveKey(mnemonic, salt, PBKDF2_ITERATIONS)
        val iv = generateRandomBytes(IV_LENGTH)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val encryptedData = cipher.doFinal(data)

        val outputSize = 1 + SALT_LENGTH + IV_LENGTH + encryptedData.size
        return ByteBuffer.allocate(outputSize).apply {
            put(FORMAT_VERSION)
            put(salt)
            put(iv)
            put(encryptedData)
        }.array()
    }

    private fun encryptV2(data: ByteArray, mnemonic: String): ByteArray {
        return encrypt(data, mnemonic).also { it[0] = 2 }
    }

    private fun decryptV2(data: ByteArray, mnemonic: String): ByteArray {
        if (data.isEmpty() || data[0].toInt() != 2) {
            throw Exception("Unsupported backup version")
        }
        val legacyLayout = data.copyOf()
        legacyLayout[0] = FORMAT_VERSION
        return decrypt(legacyLayout, mnemonic)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun decrypt(data: ByteArray, mnemonic: String): ByteArray {
        if (mnemonic.isBlank()) {
            throw IllegalArgumentException("Mnemonic cannot be empty")
        }
        if (data.size < 1 + SALT_LENGTH + IV_LENGTH + TAG_LENGTH) {
            throw Exception("Invalid encrypted data format: too short")
        }

        val buffer = ByteBuffer.wrap(data)
        val version = buffer.get()
        if (version != FORMAT_VERSION) {
            throw Exception("Unsupported encryption format version: $version")
        }

        val salt = ByteArray(SALT_LENGTH)
        buffer.get(salt)
        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val key = deriveKey(mnemonic, salt, PBKDF2_ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            key.fill(0)
            throw Exception("Decryption failed: Invalid mnemonic or corrupted data", e)
        }
    }

    private fun deriveKey(mnemonic: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(mnemonic.toCharArray(), salt, iterations, KEY_LENGTH)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun validateZipEntryName(name: String): String {
        return name.replace("../", "").replace("..\\", "")
    }

    private fun zipDirectory(sourceDir: File, baseName: String, zipOut: ZipOutputStream) {
        val files = sourceDir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                val childBase = if (baseName.isEmpty()) file.name else "$baseName/${file.name}"
                zipDirectory(file, childBase, zipOut)
            } else {
                val entryName = if (baseName.isEmpty()) file.name else "$baseName/${file.name}"
                val zipEntry = ZipEntry(entryName)
                zipOut.putNextEntry(zipEntry)

                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        zipOut.write(buffer, 0, length)
                    }
                }
                zipOut.closeEntry()
            }
        }
    }

    private fun unzipFile(zipPath: String, outputDirectory: String) {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            throw Exception("Zip file does not exist: $zipPath")
        }

        val outputDir = File(outputDirectory)
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val entryName = validateZipEntryName(entry.name)
                val entryFile = File(outputDir, entryName)

                if (!entryFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                    throw SecurityException("Zip entry is outside of target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var length: Int
                        while (zipIn.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                    }
                    zipIn.closeEntry()
                }
                entry = zipIn.nextEntry
            }
        }
    }
}
