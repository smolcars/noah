package com.noahwallet.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.PayRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/** Send's recent recipients on the launcher (voice-assistants.md): the To screen's rule, four at most, each opening Send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherShortcutsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()
    private fun row(to: String, minutesAgo: Long, direction: String = "outgoing", status: String = "successful") =
        HistoryTransaction("$to-$minutesAgo", "Lnurl", 1_000, HistoryRules.isoDate(Date(now - minutesAgo * 60_000)), direction,
            destination = to, movementStatus = status)
    private fun shown() = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }

    @Test fun recentRecipientsBecomeShortcutsNewestFirst() {
        val history = HistoryRules.unified(listOf(
            row("sam@noahwallet.com", 1), row("Alice@Example.com", 2), row("sam@noahwallet.com", 3),
            row("bob@example.com", 4, status = "failed"), row("carol@example.com", 5, direction = "incoming"),
            row("bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22", 6), row("dave@example.com", 7), row("erin@example.com", 8)))
        LauncherShortcuts.update(context, history, "noahwallet.com", "regtest")
        val shortcuts = shown()
        assertEquals(listOf("sam@noahwallet.com", "Alice@Example.com", "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22", "dave@example.com"),
            shortcuts.map { PayRoute.of(it.intent, "regtest")?.recipient })
        assertEquals(listOf("sam", "alice", "bcrt…lc22", "dave"), shortcuts.map { it.shortLabel.toString() })
        val intent = shortcuts.first().intent
        assertEquals("com.noahwallet.MainActivity", intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags)
        assertEquals(PayRoute("sam@noahwallet.com").uri("regtest"), intent.data)

        // A newer recipient pushes the oldest out; a deleted wallet takes them all.
        LauncherShortcuts.update(context, HistoryRules.unified(history + row("frank@example.com", 0)), "noahwallet.com", "regtest")
        assertEquals(listOf("frank@example.com", "sam@noahwallet.com", "Alice@Example.com", "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"),
            shown().map { PayRoute.of(it.intent, "regtest")?.recipient })
        LauncherShortcuts.clear(context)
        assertEquals(emptyList<String>(), shown().map { it.id })
    }
}
