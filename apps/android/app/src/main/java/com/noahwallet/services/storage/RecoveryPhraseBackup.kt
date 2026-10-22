package com.noahwallet.services.storage

import kotlinx.serialization.json.*

/**
 * Per-wallet acknowledgement that the recovery phrase was written down, kept in its own native
 * settings key so no RN or earlier native key changes. Missing or unreadable means not backed up:
 * RN never recorded an acknowledgement, so migrated and earlier native wallets keep the Home prompt
 * until the user confirms. Only a restore from a typed phrase (recorded by restore completion, so a
 * replay after process death applies it) or that confirmation records true.
 * Deleting the wallet clears it with every other setting.
 */
object RecoveryPhraseBackup {
    /** Shared with iOS. */
    const val key = "native-recovery-phrase-backup-v1"

    fun isBackedUp(raw: String?): Boolean = raw?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject["backedUp"]?.jsonPrimitive?.takeIf { value -> !value.isString }?.booleanOrNull }.getOrNull()
    } == true

    fun encode(backedUp: Boolean): String = buildJsonObject { put("backedUp", backedUp) }.toString()

    fun isBackedUp(settings: SettingsStorage): Boolean = isBackedUp(settings.string(key))

    fun set(settings: SettingsStorage, backedUp: Boolean) = settings.set(encode(backedUp), key)
}
