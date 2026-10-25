package com.noahwallet.services.background

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.recurring.RecurringRuntime
import com.noahwallet.services.AppServices
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.push.WidgetDataWriter
import com.noahwallet.services.wallet.WalletSession

internal class PeriodicRuntime(context: Context, services: AppServices) {
    private var summary: WalletSession.Summary? = null
    private var expiry: Long? = null
    val jobs = PeriodicJobs(PeriodicJobs.Dependencies(
        eligible = services::backgroundWalletEligible,
        recurringDue = { RecurringRuntime.shared(context).hasDuePayments() },
        ensureWalletOpen = { services.ensureWalletOpen() },
        sync = services.session::sync,
        recurring = { RecurringRuntime.shared(context).execute(it) },
        maintenance = services.session::maintenanceDelegated,
        backupEnabled = { services.registration.state.isBackupEnabled == true },
        backup = { services.backupCoordinator.flush(BackupCoordinator.Reason.startup, requireEnabled = true) },
        widgetSummary = { summary = services.session.summary() },
        widgetExpiry = { expiry = services.session.closestExpiryBlocks() },
        publishWidget = { summary?.let { WidgetDataWriter.write(context, it.balance, expiry, BuildConfig.APP_VARIANT) } },
        progressExits = { services.session.progressPendingExits { AppLog(context, "backgroundTasks").w("Background exit progress failed: $it") } },
    ))
}
