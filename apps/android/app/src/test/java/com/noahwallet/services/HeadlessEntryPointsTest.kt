package com.noahwallet.services

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Wiring contracts complement StoredWalletOpenerTest's real admission/seed-write checks.
 * Android framework entry classes cannot run in the plain JVM test process. */
class HeadlessEntryPointsTest {
    private fun source(path: String) = File("src/main/java/com/noahwallet/$path.kt").readText()
    private fun walletGate() {
        val app = source("services/AppServices")
        assertTrue(app.contains("if (!foreground) requireBackgroundWallet()"))
        assertTrue(app.indexOf("if (!foreground) requireBackgroundWallet()") < app.indexOf("if (foreground && recoverRestore(walletMutationHeld)"))
        assertTrue(app.contains("else walletOpener.ensureOpen()"))
        assertTrue(app.contains("private suspend fun requireBackgroundWallet() {\n        settings.requireHealthy()"))
        assertTrue(source("services/storage/StoredWalletOpener").contains("walletState()?.isInitialized == false"))
        assertTrue(source("services/push/PushRuntime").contains("ensureWalletOpen = services::ensureWalletOpen"))
    }
    @Test fun headlessDeletionRecoveryCannotDeleteASeed() {
        val code = source("services/AppServices")
        val gate = "if (!foreground) requireBackgroundWallet()"
        assertTrue(code.contains("RestoreTransaction(location).settingsPending"))
        assertTrue(code.contains(gate))
        assertTrue(code.contains("location.restorePending ||"))
        assertTrue(source("services/background/BackgroundWalletAdmission").contains("!restorePending && !deletionPending"))
        assertTrue(code.indexOf(gate) < code.indexOf("if (foreground && recoverRestore(walletMutationHeld)"))
    }
    @Test fun firebaseServiceRoutesThroughTheGatedWorker() {
        assertTrue(source("services/push/NoahFirebaseMessagingService").contains("PushWork.enqueue")); walletGate()
    }
    @Test fun unifiedPushServiceRoutesThroughTheGatedWorker() {
        assertTrue(source("services/push/NoahUnifiedPushService").contains("PushWork.enqueue")); walletGate()
    }
    @Test fun pushWorkerUsesTheGatedRuntime() {
        assertTrue(source("services/push/PushWork").contains(".jobs.handle(PushPayload.decode(payload))")); walletGate()
    }
    @Test fun registrationWorkerRequiresOnboardingWithoutOpeningAWallet() {
        assertTrue(source("services/push/PushWork").contains(".register()"))
        assertTrue(source("services/push/PushRuntime").contains("onboarded = services::isOnboarded"))
        assertTrue(source("services/push/PushRegistration").contains("if (!onboarded()"))
    }
    @Test fun recurringRuntimeUsesTheSharedGate() {
        val code = source("recurring/RecurringRuntime")
        assertTrue(code.contains("ensureOpen = { services.ensureWalletOpen(walletMutationHeld = true) }"))
        assertTrue(code.contains("withWalletMutation = { services.lifecycle.withWalletMutation(it) }")); walletGate()
    }
    @Test fun timeReceiverRoutesThroughTheRecurringRuntime() {
        assertTrue(source("recurring/RecurringTimeReceiver").contains("RecurringRuntime.shared(context).timeChanged()")); walletGate()
    }
    @Test fun recurringForegroundRunnerRequiresOnboarding() {
        assertTrue(source("recurring/RecurringRuntime").contains("isOnboarded = services::isOnboarded"))
        assertTrue(source("recurring/RecurringForegroundRunner").contains("isOnboarded()")); walletGate()
    }
    @Test fun recurringReminderWorkerRequiresOnboardingAndDoesNotOpenWallets() {
        val code = source("recurring/RecurringNotifications")
        assertTrue(code.contains("LaunchState.existingWallet"))
        assertTrue(code.contains(".executor.reminderSchedule("))
        assertTrue(source("recurring/RecurringRuntime").contains("wallet?.isInitialized != false"))
        assertFalse(code.contains("ensureWalletOpen")); assertFalse(code.contains("ensureOpen"))
    }
    /** Every widget writer takes its ticket before its first wallet read, so a stalled read can't win over a newer one. */
    @Test fun everyWidgetWriterTakesItsTicketBeforeReadingTheWallet() {
        for ((path, read) in listOf("services/push/PushRuntime" to "handles.summary()", "services/background/PeriodicRuntime" to "services.session.summary()",
            "recurring/RecurringRuntime" to "services.session.summary()", "ui/AppModel" to "session.summary()")) {
            val code = source(path)
            val ticket = code.indexOf("WidgetDataWriter.ticket()")
            assertTrue(path, ticket >= 0)
            assertTrue("$path reads before its ticket", ticket < code.indexOf(read))
        }
    }

    @Test fun everyWidgetRendersCachedDataWithoutOpeningWallets() {
        val code = source("widgets/NoahWidgetProvider")
        assertTrue(code.contains("WidgetSnapshot.read(prefs)"))
        // Everything the provider draws with reads the snapshot alone.
        for (path in listOf("NoahWidgetProvider", "WidgetContent", "WidgetViews", "WidgetSea", "WidgetType", "WidgetSnapshot")) {
            val drawn = source("widgets/$path")
            assertFalse(path, drawn.contains("AppServices")); assertFalse(path, drawn.contains("WalletSession"))
            assertFalse(path, drawn.contains("SettingsStorage"))
        }
        // Android 7 (API 24–25) has no java.time and the app doesn't desugar it: the widget and its writer use java.util.
        for (path in listOf("widgets/NoahWidgetProvider", "widgets/WidgetContent", "widgets/WidgetViews", "widgets/WidgetSea", "widgets/WidgetType",
            "widgets/WidgetSnapshot", "widgets/WidgetWave", "services/push/WidgetDataWriter")) {
            val code = source(path).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse(path, Regex("""import java\.time|java\.time\.\w""").containsMatchIn(code))
        }
        for (path in listOf("NoahWidgetMainnetProvider", "NoahWidgetSignetProvider", "NoahWidgetRegtestProvider")) {
            assertTrue(path, source("widgets/$path").contains("NoahWidgetProvider()"))
        }
    }
}
