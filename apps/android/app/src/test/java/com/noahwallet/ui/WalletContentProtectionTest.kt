package com.noahwallet.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.view.View
import android.view.ViewGroup
import org.robolectric.shadows.ShadowDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalletContentProtectionTest {
    private fun texts(view: View): List<String> {
        val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?.invoke(view) as? SemanticsOwner
        fun collect(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::collect)
        return if (owner != null) collect(owner.rootSemanticsNode)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    }

    @Test fun aDialogCreatedAfterLockHidesPrivateContentAndRetainsItsDraft() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            var covered by mutableStateOf(true)
            var show by mutableStateOf(false)
            var mounts = 0
            var unmounts = 0
            var draft: String? = null
            controller.get().setContent {
                CompositionLocalProvider(LocalWalletPrivacy provides WalletPrivacy(covered, unlock = { covered = false })) {
                    if (show) WalletAlertDialog(onDismissRequest = {}, title = { Text("Private title") },
                        text = {
                            val value = remember { UUID.randomUUID().toString() }
                            SideEffect { draft = value }
                            DisposableEffect(Unit) { mounts++; onDispose { unmounts++ } }
                            Text("Private draft $value")
                        }, confirmButton = { Text("Private action") })
                }
            }
            fun frame() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
            frame(); show = true; frame()
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            val original = checkNotNull(draft)
            val hidden = texts(dialog.window!!.decorView)
            assertFalse(hidden.toString(), hidden.any { it.contains("Private") })
            assertTrue(hidden.toString(), hidden.contains("Unlock"))
            covered = false; frame()
            assertTrue(texts(dialog.window!!.decorView).any { it == "Private draft $original" })
            assertEquals(original, draft)
            assertEquals(1, mounts); assertEquals(0, unmounts)
            covered = true; frame()
            assertFalse(texts(dialog.window!!.decorView).any { it.contains("Private") })
        }
    }

    @Test fun lockingAndForegroundCheckingRetainNavigationAndDrafts() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            var locked by mutableStateOf(false)
            var checking by mutableStateOf(false)
            var mounts = 0
            var unmounts = 0
            var rendered: Pair<List<String>, String>? = null
            controller.get().setContent {
                Box(Modifier.walletContentProtection(locked || checking)) {
                    val navigation = remember { mutableStateListOf("Home", "Settings", "Profile") }
                    val draft = remember { UUID.randomUUID().toString() }
                    SideEffect { rendered = navigation.toList() to draft }
                    DisposableEffect(Unit) { mounts++; onDispose { unmounts++ } }
                }
            }
            fun frame() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
            frame()
            val original = checkNotNull(rendered)
            locked = true; frame()
            locked = false; checking = true; frame()
            checking = false; frame()
            assertEquals(original, rendered)
            assertEquals(1, mounts)
            assertEquals(0, unmounts)
        }
    }
}
