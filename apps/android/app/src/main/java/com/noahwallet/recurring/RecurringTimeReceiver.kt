package com.noahwallet.recurring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecurringTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) RecurringRuntime.shared(context).timeChanged()
    }
}
