package com.noahwallet

import android.os.Bundle
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.noahwallet.ui.AppModel
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.noahwallet.ui.LaunchGateView
import com.noahwallet.ui.NoahTheme

class MainActivity : FragmentActivity() {
    private var credentialRequestCode: Int? = null
    private val model by lazy { ViewModelProvider(this)[AppModel::class.java] }
    private val backgroundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Credential confirmation stops our Activity too. Home/Recents/screen-off still invalidate it.
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                model.walletLock.background()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (Build.VERSION.SDK_INT < 29 && intent.action == "android.app.action.CONFIRM_DEVICE_CREDENTIAL") {
            credentialRequestCode = requestCode
            model.walletLock.credentialStarted()
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (credentialRequestCode == requestCode) {
            credentialRequestCode = null
            model.walletLock.credentialReturned(resultCode == RESULT_OK)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        // An exported singleTask relaunch can dismiss credential UI without a background broadcast.
        model.walletLock.background()
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("recurring", false)) model.recurringNotification = true
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT < 29) unregisterReceiver(backgroundReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        model.walletLock.resumed()
        super.onResume()
        com.noahwallet.services.AppServices.shared(this).reconcileBackupTriggers(foreground = true)
        if (model.services.isOnboarded()) com.noahwallet.recurring.RecurringRuntime.shared(this).foreground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("recurring", false)) model.recurringNotification = true
        if (model.walletLock.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT < 29) ContextCompat.registerReceiver(this, backgroundReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) },
            ContextCompat.RECEIVER_EXPORTED)
        enableEdgeToEdge()
        setContent {
            NoahTheme {
                LaunchGateView()
            }
        }
    }
}
