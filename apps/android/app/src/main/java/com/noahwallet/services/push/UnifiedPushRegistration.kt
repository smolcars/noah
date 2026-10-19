package com.noahwallet.services.push

import android.content.Context
import android.content.SharedPreferences
import com.noahwallet.BuildConfig
import org.unifiedpush.android.connector.UnifiedPush

/** Connector 3.1.2 itself reads/migrates its RN SQLite and legacy preferences in place. */
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
