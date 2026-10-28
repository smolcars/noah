package com.noahwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.LaunchGateView
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.loadAppModel
import kotlinx.coroutines.*

// Below API 29 this broadcast is the Home/Recents signal; the receiver is only registered there.
@Suppress("DEPRECATION")
private val closeSystemDialogs = Intent.ACTION_CLOSE_SYSTEM_DIALOGS

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
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == closeSystemDialogs) {
                invalidateLock()
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(closeSystemDialogs) },
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
            setContent {
                com.noahwallet.localization.LocalizedContent(current.localeIdentifier) {
                    NoahTheme { Box(Modifier.semantics { testTagsAsResourceId = true }) { LaunchGateView(current) } }
                }
            }
        }
    }
}
