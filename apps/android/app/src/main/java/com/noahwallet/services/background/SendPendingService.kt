package com.noahwallet.services.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noahwallet.send.SendController
import com.noahwallet.send.SendPendingWatch
import com.noahwallet.send.SendRuntime
import com.noahwallet.services.AppServices
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a Lightning payment moving after Noah leaves the front (async-payments.md, "Android"): a short foreground
 * service, started at hold-to-send while Noah is visible. While it runs, bark's mailbox processor stays on and a send
 * bark is still paying is checked until it ends or [CAP_MILLIS] passes. Nothing here pays. Its notification says the
 * payment is on its way and, if Noah isn't in front when the service stops, how far it got.
 */
class SendPendingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, SendPendingNotifications.ID, SendPendingNotifications.sending(this), type)
        if (watch == null) {
            running = true
            val controller = SendRuntime.shared(this).controller
            val session = WalletSession.shared(this)
            watch = scope.launch {
                // In front, Send's own screen watches the payment.
                withTimeoutOrNull(CAP_MILLIS) {
                    SendPendingWatch.run(controller, driveEvery = 10, active = { !PeriodicExecution.isForeground }) { session.pendingSendHashes() }
                }
                finish(controller)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 14+ ends a short service itself after about three minutes; [CAP_MILLIS] normally gets there first. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) = finish(SendRuntime.shared(this).controller)

    private fun finish(controller: SendController) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // In front, Send shows the result itself.
        if (!PeriodicExecution.isForeground) SendPendingNotifications.finished(this, controller.state, controller.attempt?.pending != null)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        // The mailbox processor now follows the app's visibility again.
        AppServices.shared(this).stopMailboxProcessor()
        super.onDestroy()
    }

    companion object {
        /** `retry_for` and a minute: the server has stopped retrying by then, and a stuck payment can take hours. */
        const val CAP_MILLIS = 120_000L

        /** True from the first start until the service is destroyed. */
        @Volatile var running = false
            private set

        /** Call while Noah is visible, as a Lightning payment is submitted. A refused start only loses the window. */
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, SendPendingService::class.java)) }
            catch (_: IllegalStateException) { /* Not allowed to start now: the payment goes on without the window. */ }
        }
    }
}
