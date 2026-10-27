package com.noahwallet.services.push

import android.content.Context
import android.content.SharedPreferences
import com.noahwallet.BuildConfig
import org.unifiedpush.android.connector.UnifiedPush

/** The connector itself reads RN's 3.1.2 SQLite (same database version) and migrates legacy preferences in place, in the background since 3.3. */
class UnifiedPushRegistration(context: Context, variant: String = BuildConfig.APP_VARIANT) {
    private val context = context.applicationContext
    val instance = "noah-$variant"
    private val prefs = context.getSharedPreferences("noah_unified_push", Context.MODE_PRIVATE)

    fun endpoint(): String? = endpoint(prefs, instance)

    fun saveEndpoint(value: String) {
        check(prefs.edit().putString("endpoint_$instance", value).putString("endpoint", value).commit())
    }

    fun clearEndpoint() {
        check(prefs.edit().remove("endpoint_$instance").remove("endpoint").commit())
    }

    data class Distributor(val id: String, val name: String, val saved: Boolean, val connected: Boolean)
    fun distributors(): List<Distributor> {
        val saved = UnifiedPush.getSavedDistributor(context)
        val connected = UnifiedPush.getAckDistributor(context)
        return UnifiedPush.getDistributors(context).map { id ->
            val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(id, 0)).toString() }.getOrDefault(id)
            Distributor(id, name, id == saved, id == connected)
        }
    }
    fun select(id: String) { UnifiedPush.saveDistributor(context, id) }

    fun register(): String? {
        val distributors = UnifiedPush.getDistributors(context)
        val saved = UnifiedPush.getSavedDistributor(context)
        val selected = saved ?: distributors.firstOrNull { it.contains("ntfy", ignoreCase = true) }
            ?: distributors.firstOrNull()
        if (selected != null) {
            UnifiedPush.saveDistributor(context, selected)
            UnifiedPush.register(context, instance, "Noah registering for UnifiedPush ($instance)", null)
        }
        return endpoint()
    }

    companion object {
        fun endpoint(prefs: SharedPreferences, instance: String): String? =
            prefs.getString("endpoint_$instance", prefs.getString("endpoint", null))?.takeIf { it.isNotBlank() }
    }
}
