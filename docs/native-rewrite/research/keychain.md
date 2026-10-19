Verified 2026-10-19. Repo remained read-only. Installed keychain **10.0.0 is hoisted under `node_modules/`**, not `client/node_modules/`. Below, `K` means `node_modules/react-native-keychain`; `A` means `K/android/src/main/java/com/oblador/keychain`. References are relative to the Noah repo.

**iOS existing item.** Noah writes username `noah`, service `com.noah.mnemonic.<variant>`, password mnemonic; auth uses `com.noah.auth-token.<variant>` (`client/src/constants.ts:17`; `client/src/lib/crypto.ts:76,125`). Exact native attributes:

- `kSecClassGenericPassword`; service/account above; mnemonic as UTF-8 `kSecValueData`.
- `kSecAttrAccessibleAfterFirstUnlock` by default—not WhenUnlocked.
- `kSecAttrSynchronizable = false`; no access-control object, biometry or user-presence flags.
- No explicit access group. `kSecUseDataProtectionKeychain = true` on iOS 13+.

Source: `K/ios/RNKeychainManager/RNKeychainManager.m:95,130,184,212,445`. The [accessibility docs](https://oblador.github.io/react-native-keychain/docs/api/enumerations/ACCESSIBLE/) agree with the source.

Apple defaults creation to the first keychain entitlement group, otherwise the application identifier, normally `$(AppIdentifierPrefix)com.noahwallet.<variant>`. Preserve the actual prefix, not merely Team ID. [Apple access-group rules](https://developer.apple.com/documentation/security/ksecattraccessgroup).

`client/ios/Noah/noah.entitlements:7` contains all three `group.com.noahwallet.*` app groups, no `keychain-access-groups`. `client/ios/NoahWidget{Mainnet,Signet,Regtest}Extension.entitlements:5` each has its corresponding app group; `NoahWidgetExtension.entitlements:5` has regtest. None explicitly shares the app’s default keychain group. Verify final signed entitlements; source plists alone do not establish distribution entitlements.

Swift reader; pass either exact mnemonic or token service:

```swift
import Foundation
import Security

func readLegacySecret(service: String) throws -> String? {
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: "noah",
        kSecAttrSynchronizable as String: false,
        kSecReturnData as String: true,
        kSecMatchLimit as String: kSecMatchLimitOne
    ]
    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess else {
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
    guard let data = result as? Data,
          let value = String(data: data, encoding: .utf8) else {
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(errSecDecode))
    }
    return value
}
```

Recommendation: retain the SAME item/service/account for the first native releases. Framework replacement needs no iOS rewrite. Later, deliberately choose `AfterFirstUnlockThisDeviceOnly`, updating with `SecItemUpdate` after validation; never delete-then-add. `WhenUnlockedThisDeviceOnly` prevents locked background access. Both AfterFirstUnlock classes require one unlock after reboot. Existing non-synchronizable items do not use iCloud Keychain sync, but their current accessibility permits encrypted-backup migration to another device; ThisDeviceOnly prevents that. This is a recovery-policy change, not a migration prerequisite. [Apple backup behavior](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlock?language=objc), [device-only behavior](https://developer.apple.com/documentation/Security/kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly).

**Android RN storage.** V10 uses Preferences DataStore `files/datastore/RN_KEYCHAIN.preferences_pb`, automatically importing old `shared_prefs/RN_KEYCHAIN.xml`. Entries are Base64.DEFAULT strings `<service>:u`, `<service>:p`, plus plaintext cipher marker `<service>:c` (`A/DataStorePrefsStorage.kt:29,37,64`; `A/PrefsStorageBase.kt:31`). Marker is separate, not a ciphertext prefix. Keystore alias is the nonempty service itself.

`KeystoreAESGCM_NoAuth`: AES-256/GCM/NoPadding, no authentication requirement; decoded bytes are 12-byte IV followed by ciphertext and 16-byte authentication tag, separately for username/password, UTF-8 plaintext, no AAD. `KeystoreAESCBC`: AES-256/CBC/PKCS7Padding, 16-byte IV followed by ciphertext. Sources: `A/cipherStorage/CipherStorageKeystoreAesGcm.kt:52,87,156,213`; `CipherStorageKeystoreAesCbc.kt:35,214`; `CipherStorageBase.kt:54,300`.

**Do not assume GCM.** V10 registers CBC and GCM; both unauthenticated implementations rank equally and selection traverses a HashMap (`A/KeychainModule.kt:125,142,567`; `CipherStorageBase.kt:97`). Read `:c`. RSA/authenticated GCM require their own authentication-aware reader; Conceal is unsupported by v10. A Kotlin update can use the same AndroidKeyStore keys if application identity, signing compatibility and app data survive. [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

Kotlin reader requires AndroidX Preferences DataStore and coroutines. Use one singleton delegate per process/file, without RN concurrently opening it. No SharedPreferences migration is configured, preserving legacy records:

```kotlin
import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

private val Context.legacySecrets by preferencesDataStore("RN_KEYCHAIN")

suspend fun readLegacySecret(ctx: Context, service: String): String? {
    val prefs = ctx.applicationContext.legacySecrets.data.first()
    val suffixes = listOf("u", "p", "c")
    val ds = suffixes.associateWith {
        prefs[stringPreferencesKey("$service:$it")]
    }
    val row = if (ds.values.any { it != null }) ds else {
        val old = ctx.getSharedPreferences("RN_KEYCHAIN", Context.MODE_PRIVATE)
        suffixes.associateWith { old.getString("$service:$it", null) }
    }
    if (row.values.all { it == null }) return null
    val gcm = when (row["c"]) {
        "KeystoreAESGCM_NoAuth" -> true
        "KeystoreAESCBC" -> false
        else -> error("Unsupported legacy cipher")
    }
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = ks.getKey(service, null) as? SecretKey
        ?: error("Legacy key unavailable") // Never generate a replacement.
    fun decrypt(field: String): String {
        val bytes = Base64.decode(requireNotNull(row[field]), Base64.DEFAULT)
        val n = if (gcm) 12 else 16
        require(bytes.size >= n + 16)
        val cipher = Cipher.getInstance(
            if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding")
        val iv = bytes.copyOfRange(0, n)
        if (gcm) cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        else cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(bytes, n, bytes.size - n).toString(Charsets.UTF_8)
    }
    check(decrypt("u") == "noah")
    return decrypt("p")
}
```

History: Noah initially adopted `^10.0.0` at `ec13a8b16` (2025-07-01, root package.json), moved it at `6b666742`, pinned 10.0.0 at `90f313bf`. All 34 local release tags declare v10; actual distributed binaries are unverified. There is no evidence here of Noah v8/v9 users. Upstream [v8.2 source](https://github.com/oblador/react-native-keychain/blob/v8.2.0/android/src/main/java/com/oblador/keychain/PrefsStorage.java#L18) used SharedPreferences/CBC or Conceal/RSA; missing `:c` meant Conceal. [V9.2](https://github.com/oblador/react-native-keychain/releases/tag/v9.2.0) introduced DataStore and GCM separately; importing preferences does not re-encrypt ciphertext. [V10](https://github.com/oblador/react-native-keychain/releases/tag/v10.0.0) removed Conceal, recommending migration while still on v9. Inventory release artifacts before excluding historical readers.

**Android extra copy.** `NoahTools.kt:336` under `client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/` writes `noah_native_secrets` / `mnemonic_<variant>` with `MasterKeys.AES256_GCM_SPEC`, encrypted keys AES256_SIV and values AES256_GCM. Default master alias: `_androidx_security_master_key_`. `NoahPushService.kt:683` reads it.

It is **not guaranteed**: only real Android devices without Google Play Services qualify (`client/src/constants.ts:27`); writes occur at create/restore (`walletApi.ts:115,210`; `backupService.ts:518`). No startup backfill exists. GMS users, emulators, older installations never restored after this feature, and failed writes may lack it; create/normal restore merely warn on failure.

Kotlin can retain `EncryptedSharedPreferences.create("noah_native_secrets", existingMasterAlias, context, AES256_SIV, AES256_GCM).getString("mnemonic_$variant", null)` with the corresponding enum constants. Check the existing master key and encrypted keysets first; never recreate missing keys. Deprecation does not make existing ciphertext unreadable. [AndroidX documentation](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences).

For new Android storage, use a separate Keystore AES-GCM key, fresh nonce per write, versioned ciphertext in an atomic private file/DataStore, without user-authentication gating for background work. Tink is optional. Never store plaintext in DataStore. Exclude ciphertext/keyset files from backup: Keystore keys do not restore with them. Current manifest allows backup (`client/android/app/src/main/AndroidManifest.xml:31`); audit merged backup rules.

**Migration algorithm.** Serialize migration before wallet initialization or push work. Read new store → legacy RN → Android extra copy, recording absent, locked, unsupported and corrupt separately. On iOS, “new store” can simply wrap the existing item. Validate BIP39 wordlist/checksum and normalization, network and derived identity; stop on conflicting valid seeds. Preserve `Documents/noah-data-<variant>` (`constants.ts:40`). Compare against existing DB identity on a safe snapshot: Nitro exposes walletFingerprint/mailboxPubkey and snapshot validation (`node_modules/react-native-nitro-ark/cpp/NitroArk.hpp:364,514`), but fingerprint derivation and seed-versus-DB validation inside Bark remain **unverified**. Do not assume successful `loadWallet` proves identity.

After validation, atomically write any new store, reopen/decrypt, compare, then mark completion; retry safely after crashes. Never delete legacy items/keys in initial releases. Specifically reject RN’s missing-DB seed-deletion behavior (`Navigators.tsx:543`; `walletApi.ts:615`). Unlock errors require retry; unrecoverable failures require recovery-phrase UX, never automatic wallet creation. Telemetry: only migration version, platform, variant, source and coarse result codes—no seed, token, signatures, ciphertext, hashes or wallet identifiers.

Auth token loss is recoverable: `client/src/lib/api.ts:169` gets `/v0/getk1`, derives variant-specific key index 0, signs, posts `{key,sig,k1}` to `/v0/auth/login`, saves `access_token`. `lnurlAuth.ts:29` handles external callbacks, not this token issuance.

**Upgrade test.** Automate with disposable regtest wallets and prebuilt signed artifacts. Install old RN, create/restore a fixture, capture identity only inside test assertions, terminate, then install native over it:

```sh
xcrun simctl install "$SIM_UDID" /tmp/OldNoah.app
# Create wallet through UI; Expo server required for debug builds.
xcrun simctl terminate "$SIM_UDID" com.noahwallet.regtest
xcrun simctl install "$SIM_UDID" /tmp/NativeNoah.app
xcrun simctl launch "$SIM_UDID" com.noahwallet.regtest

adb -s "$ANDROID_SERIAL" install /tmp/old-noah.apk
# Create wallet through UI.
adb -s "$ANDROID_SERIAL" shell am force-stop com.noahwallet.regtest
adb -s "$ANDROID_SERIAL" install -r /tmp/native-noah.apk
```

Never uninstall/clear data between steps. Android requires compatible signing and versionCode; [`-r` preserves data](https://developer.android.com/tools/adb). Run XCTest/instrumentation assertions for identical seed-derived identity, preserved DB, repeat migration, interrupted write, CBC/GCM, absent extra copy, missing key, corrupted ciphertext and conflicting seeds. Exercise every variant and supported old release. Verify locked-after-unlock and reboot-before-unlock behavior on real devices; simulator success does not validate production entitlements or hardware protection.

Swift reader passed host `swiftc -typecheck`. Unverified: Kotlin compilation, live upgrade/decryption, distribution entitlements and shipped binaries. No real secrets were accessed.
