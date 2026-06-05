import CommonCrypto
import CryptoKit
import Foundation
import NitroModules
import OSLog
import ZIPFoundation

class NoahTools: HybridNoahToolsSpec {
    // MARK: - Public API Methods (called by Nitro)

    func nativePost(url: String, body: String, headers: [String: String], timeoutSeconds: Double)
        throws -> Promise<HttpResponse>
    {
        return try performNativePost(
            url: url, body: body, headers: headers, timeoutSeconds: timeoutSeconds)
    }

    func nativeGet(url: String, headers: [String: String], timeoutSeconds: Double) throws
        -> Promise<HttpResponse>
    {
        return try performNativeGet(url: url, headers: headers, timeoutSeconds: timeoutSeconds)
    }

    func getAppVariant() throws -> String {
        return try performGetAppVariant()
    }

    func getAppLogs() throws -> Promise<[String]> {
        return try performGetAppLogs()
    }

    func createBackup(mnemonic: String) throws -> Promise<String> {
        return try performCreateBackup(mnemonic: mnemonic)
    }

    func restoreBackup(encryptedData: String, mnemonic: String) throws -> Promise<Bool> {
        return try performRestoreBackup(encryptedData: encryptedData, mnemonic: mnemonic)
    }

    func nativeLog(level: String, tag: String, message: String) throws {
        try performNativeLog(level: level, tag: tag, message: message)
    }

    func playAudio(filePath: String) throws -> Promise<Void> {
        return try performPlayAudio(filePath: filePath)
    }

    func pauseAudio() throws {
        try performPauseAudio()
    }

    func stopAudio() throws {
        try performStopAudio()
    }

    func resumeAudio() throws {
        try performResumeAudio()
    }

    func seekAudio(positionSeconds: Double) throws {
        try performSeekAudio(positionSeconds: positionSeconds)
    }

    func getAudioDuration() throws -> Double {
        return try performGetAudioDuration()
    }

    func getAudioPosition() throws -> Double {
        return try performGetAudioPosition()
    }

    func isAudioPlaying() throws -> Bool {
        return try performIsAudioPlaying()
    }

    func updateWidgetData(
        totalBalance: Double,
        onchainBalance: Double,
        offchainBalance: Double,
        pendingBalance: Double,
        closestExpiryBlocks: Double,
        expiryThreshold: Double,
        appGroup: String
    ) throws {
        try performUpdateWidgetData(
            totalBalance: totalBalance,
            onchainBalance: onchainBalance,
            offchainBalance: offchainBalance,
            pendingBalance: pendingBalance,
            closestExpiryBlocks: closestExpiryBlocks,
            expiryThreshold: expiryThreshold,
            appGroup: appGroup
        )
    }

    // MARK: - Android-only methods (placeholders for iOS)

    func isGooglePlayServicesAvailable() throws -> Bool {
        // Google Play Services is Android-only
        return false
    }

    func registerUnifiedPush() throws {
        // UnifiedPush is Android-only, no-op on iOS
    }

    func getUnifiedPushEndpoint() throws -> String {
        // UnifiedPush is Android-only
        return ""
    }

    func getUnifiedPushDistributors() throws -> [UnifiedPushDistributor] {
        // UnifiedPush is Android-only
        return []
    }

    func setUnifiedPushDistributor(distributorId: Variant_NullType_String?) throws {
        // UnifiedPush is Android-only, no-op on iOS
    }

    func storeNativeMnemonic(mnemonic: String) throws -> Promise<Void> {
        // This is Android-only, no-op on iOS
        let promise = Promise<Void>()
        promise.resolve()
        return promise
    }
}

// Include the extensions from other files
// Swift will automatically include all .swift files in the same module/target
