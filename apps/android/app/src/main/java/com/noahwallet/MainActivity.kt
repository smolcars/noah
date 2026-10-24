package com.noahwallet

import android.os.Bundle
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import com.noahwallet.ui.loadAppModel
import com.noahwallet.ui.AppModel
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.noahwallet.ui.LaunchGateView
import com.noahwallet.ui.NoahTheme

class MainActivity : FragmentActivity() {
    private var credentialRequestCode: Int? = null
    private var model: AppModel? = null
    private val links: com.noahwallet.ui.PaymentLinks by viewModels()
    private var invalidatedBeforeReady = false
    private fun invalidateLock() {
        model?.walletLock?.background() ?: run { invalidatedBeforeReady = true }
    }
    private val backgroundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Credential confirmation stops our Activity too. Home/Recents/screen-off still invalidate it.
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                invalidateLock()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (Build.VERSION.SDK_INT < 29 && intent.action == "android.app.action.CONFIRM_DEVICE_CREDENTIAL") {
            credentialRequestCode = requestCode
            model?.walletLock?.credentialStarted()
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (credentialRequestCode == requestCode) {
            credentialRequestCode = null
            model?.walletLock?.credentialReturned(resultCode == RESULT_OK)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        // An exported singleTask relaunch can dismiss credential UI without a background broadcast.
        invalidateLock()
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("recurring", false)) model?.recurringNotification = true
        links.receive(intent)
    }

    override fun onStart() {
        com.noahwallet.services.background.PeriodicExecution.foreground(true)
        super.onStart()
    }

    override fun onStop() {
        com.noahwallet.services.background.PeriodicExecution.foreground(false)
        if (!isChangingConfigurations) com.noahwallet.onchain.ExitFunding.shared.backgrounded()
        if (model == null && !isChangingConfigurations) invalidatedBeforeReady = true
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT < 29) unregisterReceiver(backgroundReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        model?.refreshLocale()
        model?.walletLock?.resumed()
        super.onResume()
        model?.let { current -> lifecycleScope.launch(Dispatchers.IO) {
            com.noahwallet.services.background.PeriodicExecution.awaitIdle()
            current.services.reconcileBackupTriggers(foreground = true)
            if (current.services.isOnboarded()) com.noahwallet.recurring.RecurringRuntime.shared(this@MainActivity).foreground()
        } }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        super.onCreate(savedInstanceState)
        links.receive(intent, savedInstanceState)
        if (Build.VERSION.SDK_INT < 29) ContextCompat.registerReceiver(this, backgroundReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) },
            ContextCompat.RECEIVER_EXPORTED)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val current = loadAppModel(this@MainActivity)
            model = current
            if (!invalidatedBeforeReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) current.walletLock.resumed()
            else current.walletLock.background()
            if (intent.getBooleanExtra("recurring", false)) current.recurringNotification = true
            current.walletLock.applySecure(window)
            val recurring = withContext(Dispatchers.IO) { com.noahwallet.recurring.RecurringRuntime.shared(this@MainActivity) }
            lifecycle.addObserver(recurring.syncForegroundAdmission)
            lifecycle.addObserver(recurring.foregroundRunner { current.refresh() })
            setContent { com.noahwallet.localization.LocalizedContent(current.localeIdentifier) { NoahTheme { Box(Modifier.semantics { testTagsAsResourceId = true }) { LaunchGateView(current) } } } }
        }
    }
}
