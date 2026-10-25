package com.noahwallet.ui.send

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.send.*
import com.noahwallet.services.wallet.ChainStatus
import com.noahwallet.ui.NoahTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A confirmed on-chain send names its block once, in the block row's phase line (SendMotionTest), not again under "Sent ₿X". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SendConfirmedOnceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theResultLineLeavesTheBlockToTheBlockRow() {
        val controller = SendController(SendController.Dependencies(read = { null }, write = {}, reservation = { it(FakeSendWallet()) }))
        var confirmedAt by mutableStateOf<Long?>(null)
        compose.setContent {
            NoahTheme {
                val receipt = SendReceipt(5_000, txid = "ab".repeat(32),
                    chainStatus = confirmedAt?.let { ChainStatus(ChainStatus.Kind.confirmed, it) } ?: ChainStatus(ChainStatus.Kind.inMempool))
                ResultArea(SendEnding.chainSent, controller, null, receipt, 5_000, SendWho("bcrt1q…"), confirmedAt,
                    SendEnv(BitcoinAmountUnit.entries.first(), Locale.US, "regtest", null, tip = 921_346), SendActions())
            }
        }
        compose.onNodeWithTag("send-result-line").assertTextEquals("It confirms in about 20 minutes. Activity shows when.")
        confirmedAt = 921_346
        compose.onNodeWithTag("send-result-line").assertDoesNotExist()
    }
}
