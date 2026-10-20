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
import com.noahwallet.api.generated.models.AppVersionInfo
import com.noahwallet.ui.home.*
import com.noahwallet.ui.launch.*

/** Debug-only public-copy previews. Never reads or displays a seed or wallet balance. */
class ParityPreviewActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val model = loadAppModel(this@ParityPreviewActivity)
        setContent { NoahTheme { Surface { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            var result by remember { mutableStateOf<String?>(null) }
            when (intent.getStringExtra("screen")) {
                "push" -> PushNotificationsRequiredScreen(false, { result = "Requested" }, { result = "Rechecked" })
                "battery" -> BatteryOptimizationScreen(model) { result = "Continued" }
                "unified-push" -> UnifiedPushScreen(true) { result = "Continued" }
                "rounds" -> PendingRoundsSheet(listOf(io.github.niteshbalusu11.bark.PendingRoundStatus(7u,
                    io.github.niteshbalusu11.bark.RoundOutcome.PENDING, "a".repeat(64), listOf("b".repeat(64)), "Example round error", false, false))) { finish() }
                "status" -> NoahForm("Status banners") {
                    UpdateWarningBanner(AppVersionInfo("1.0.0", true), "0.2.0-regtest", "regtest")
                    StatusBanner(HomeRules.Banner("Backing up wallet", "Running in background"))
                    StatusBanner(HomeRules.Banner("Backup failed", "An unknown error occurred while backing up.", failed = true, retry = true), false) { result = "Retried" }
                    StatusBanner(HomeRules.Banner("Backup completed", "Last backup 12:00 PM"))
                    StatusBanner(HomeRules.Banner("Boarding completed", "20,000 sats available in Ark"))
                }
                else -> QRHubScreen(if (intent.getStringExtra("screen") == "qr-missing") null else "satoshi@example.test", "Satoshi",
                    onBack = { finish() }, onProfile = { result = "Profile opened" }, onScan = { result = "Payment request routed" })
            }
            result?.let { text -> AlertDialog(onDismissRequest = { result = null }, title = { Text(text) },
                confirmButton = { TextButton({ result = null }) { Text("OK") } }) }
        } } } }
        }
    }
}
