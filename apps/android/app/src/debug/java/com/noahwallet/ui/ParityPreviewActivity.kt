package com.noahwallet.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.home.*
import com.noahwallet.ui.launch.*

/** Debug-only public-copy previews. Never reads or displays a seed or wallet balance. */
class ParityPreviewActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Activity from public fixtures on the real clock (motion checks): --es screen activity --es state busy|everyday|empty|loading|error
        if (intent.getStringExtra("screen") == "activity") {
            setContent { NoahTheme { com.noahwallet.ui.activity.ActivityPreviewHost(com.noahwallet.ui.activity.ActivityFixtures.ui(intent.getStringExtra("state") ?: "busy"),
                keepBar = intent.getBooleanExtra("keepBar", false)) } }
            return
        }
        lifecycleScope.launch {
            val model = loadAppModel(this@ParityPreviewActivity)
        setContent { NoahTheme { Surface { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            var result by remember { mutableStateOf<String?>(null) }
            when (intent.getStringExtra("screen")) {
                "push" -> PushNotificationsRequiredScreen(false) { result = "Requested" }
                "battery" -> com.noahwallet.ui.onboarding.OnboardingFrame(null) { BatteryOptimizationScreen(model) { result = "Continued" } }
                "unified-push" -> UnifiedPushScreen(true) { result = "Continued" }
                "rounds" -> PendingRoundsSheet(listOf(io.github.niteshbalusu11.bark.PendingRoundStatus(7u,
                    io.github.niteshbalusu11.bark.RoundOutcome.PENDING, "a".repeat(64), listOf("b".repeat(64)), "Example round error", false, false))) { finish() }
                "status" -> com.noahwallet.ui.onboarding.OnboardingFrame(null) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NudgeCard(HomeIcons.update, "Update available", "Get the latest fixes", "Update", null, onAction = { result = "Updated" }, onDismiss = {})
                    NudgeCard(HomeIcons.backupFailed, "Backup failed", "An unknown error occurred while backing up.", "Retry", null, onAction = { result = "Retried" }, onDismiss = {})
                    NudgeCard(HomeIcons.backup, "Back up your wallet", "Save your recovery phrase", "Back up", null, onAction = { result = "Backup opened" }, onDismiss = {})
                    NudgeCard(HomeIcons.email, "Add your email", "For urgent wallet alerts", "Add", "Dismiss emergency email prompt", onAction = { result = "Email opened" }, onDismiss = { result = "Dismissed" })
                    StatusLine("Backing up wallet", failed = false, dot = true, onClick = {})
                } }
                else -> QRHubScreen(if (intent.getStringExtra("screen") == "qr-missing") null else "satoshi@example.test", "Satoshi",
                    onBack = { finish() }, onProfile = { result = "Profile opened" }, onScan = { result = "Payment request routed" },
                    startScanning = intent.getStringExtra("screen") == "qr-scan")
            }
            result?.let { text -> AlertDialog(onDismissRequest = { result = null }, title = { Text(text) },
                confirmButton = { TextButton({ result = null }) { Text("OK") } }) }
        } } } }
        }
    }
}
