package com.noahwallet.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.send.*
import com.noahwallet.services.launch.LaunchState
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** OS deep links (deep-links.md): the scheme filter, the launch gate, Send's in-progress block and the apply-once rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PaymentLinksTest {
    /** Receives links as MainActivity does: the launch intent unless restored, and every new intent. */
    class Host : ComponentActivity() {
        val links: PaymentLinks by viewModels()
        override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); links.receive(intent, savedInstanceState) }
        override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); links.receive(intent) }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))
    private fun send(saved: SendAttempt? = null) = runBlocking {
        FakeSendWallet().let { wallet -> controller(wallet, TestJournal().apply { attempt = saved?.copy(walletIdentity = wallet.owner) }, scope) }
            .also { it.reloadForWalletChange() }
    }
    /** NoahApp's delivery: what reached the scan route. */
    private class Delivery { val scanned = mutableListOf<String>() }
    private fun PaymentLinks.deliver(send: SendController, into: Delivery) = deliver(send, mutableIntStateOf(0)) { into.scanned += it }
    /** LaunchGateView drops on ignore and hands an open link to NoahApp. */
    private fun PaymentLinks.gate(step: LinkStep, send: SendController, into: Delivery) {
        if (step == LinkStep.ignore) drop()
        if (step == LinkStep.open) deliver(send, into)
    }
    private val wallet = LaunchState.existingWallet

    @Test fun everySchemeAndItsUppercaseReachTheScanRouteVerbatim() {
        val ready = send()
        // Today's parser vectors, lnurlp:// included (HTTPS, so the regtest stack can't serve one live).
        val vectors = SharedFixtures.load("send-destination-native").map { it.input.jsonObject }
            .filter { it.getValue("network").jsonPrimitive.content == "regtest" }.map { it.getValue("destination").jsonPrimitive.content }
            .filter { it == it.trim() && it.substringBefore(':').lowercase() in PaymentLinks.schemes }.distinct()
        assertTrue(vectors.map { it.substringBefore(':') }.toSet().containsAll(listOf("bitcoin", "lightning", "lnurlp", "LNURLP")))
        val lnurl = "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS"
        val uppercase = mapOf("BITCOIN:BCRT1Q5MQ29KAQ9KQ660M3EEPQY8PKV7JHEFD69KLC22?amount=0.0001" to "bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22?amount=0.0001",
            "LIGHTNING:$lnurl" to "lightning:${lnurl.lowercase()}", "LNURLP://example.com/pay" to "lnurlp://example.com/pay")
        for (link in vectors + uppercase.keys) {
            val links = PaymentLinks(); val delivery = Delivery()
            links.receive(view(link))
            links.deliver(ready, delivery)
            assertEquals(link, listOf(link), delivery.scanned)
            assertNull(link, links.pending)
        }
        // Send parses the uppercase forms as it parses a scanned lowercase code.
        for ((upper, lower) in uppercase) {
            val expected = SendDestination.parse(lower, "regtest").destinationType
            assertNotNull(lower, expected)
            assertEquals(upper, expected, SendDestination.parse(upper, "regtest").destinationType)
        }
        // Withdraw, auth and every other scheme or action stay out.
        val links = PaymentLinks()
        for (other in listOf("lnurlw://example.com/w", "keyauth://example.com/k", "lnurlc://example.com/c", "https://example.com/pay", "com.noahwallet.regtest://send"))
            links.receive(view(other))
        links.receive(Intent(Intent.ACTION_SEND, Uri.parse("bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22")))
        assertNull(links.pending)
    }

    @Test fun aLinkIsHeldWhileLockedAndAppliedAfterUnlock() {
        val link = "lightning:lnurlp://example.com/pay"
        val links = PaymentLinks(); val delivery = Delivery()
        links.receive(view(link))
        // Loading, a gate check that hasn't finished (even over a gate it may clear), then App lock over the wallet's tabs.
        val ready = send()
        for (step in listOf(linkStep(LaunchRoute.loading, false, false, null, false, false), linkStep(LaunchRoute.message, false, false, null, false, false),
            linkStep(LaunchRoute.message, true, false, wallet, false, false), linkStep(LaunchRoute.push, true, true, wallet, false, false),
            linkStep(LaunchRoute.app, true, true, wallet, false, false), linkStep(LaunchRoute.app, true, false, wallet, false, false))) {
            assertEquals(LinkStep.wait, step)
            links.gate(step, ready, delivery)
        }
        assertEquals(link, links.pending)
        links.gate(linkStep(LaunchRoute.app, false, false, wallet, false, false), ready, delivery)
        assertEquals(listOf(link), delivery.scanned)
    }

    @Test fun aLinkIsIgnoredWithNoWalletOrBehindAGate() {
        // Decided when it arrives, App lock or not.
        for (locked in listOf(false, true)) {
            val ignored = listOf(
                linkStep(LaunchRoute.onboarding, locked, false, LaunchState.onboarding, false, false),
                linkStep(LaunchRoute.onboarding, locked, false, LaunchState.seedWithoutWalletData, false, false),
                linkStep(LaunchRoute.push, locked, false, wallet, false, false),
                linkStep(LaunchRoute.battery, locked, false, wallet, false, false),
                linkStep(LaunchRoute.customServer, locked, false, wallet, false, false),
                linkStep(LaunchRoute.app, locked, false, wallet, false, suspended = true),
                linkStep(LaunchRoute.message, locked, false, wallet, openFailed = true, false),
            ) + listOf(LaunchState.walletDataWithoutSeed, LaunchState.secureStorageUnavailable, LaunchState.restoreIncomplete,
                LaunchState.invalidSeed, LaunchState.seedConflict).map { linkStep(LaunchRoute.message, locked, false, it, false, false) }
            assertEquals("locked $locked", List(ignored.size) { LinkStep.ignore }, ignored)
        }
        // A link behind the notifications gate with App lock up; the user grants notifications in Settings, returns
        // (the gate re-checks), the gate is gone and they unlock: the link was dropped when it came, so nothing opens.
        val links = PaymentLinks(); val delivery = Delivery(); val ready = send()
        links.receive(view("bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"))
        for (step in listOf(linkStep(LaunchRoute.push, true, false, wallet, false, false), linkStep(LaunchRoute.push, true, true, wallet, false, false),
            linkStep(LaunchRoute.app, true, false, wallet, false, false), linkStep(LaunchRoute.app, false, false, wallet, false, false)))
            links.gate(step, ready, delivery)
        assertNull(links.pending)
        assertEquals(emptyList<String>(), delivery.scanned)
    }

    @Test fun anUnfinishedNotificationsCheckHoldsTheLinkUntilItDecides() {
        // A physical device re-checks notifications on every resume; until it finishes, NoahApp never gets the link.
        val ready = send()
        for (gate in listOf(LaunchRoute.push, LaunchRoute.app)) {
            val links = PaymentLinks(); val delivery = Delivery()
            links.receive(view("bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"))
            for (route in listOf(LaunchRoute.loading, LaunchRoute.app, LaunchRoute.push)) {
                assertEquals(LinkStep.wait, linkStep(route, false, true, wallet, false, false))
                links.gate(linkStep(route, false, true, wallet, false, false), ready, delivery)
            }
            assertEquals(emptyList<String>(), delivery.scanned)
            links.gate(linkStep(gate, false, false, wallet, false, false), ready, delivery)
            assertEquals(if (gate == LaunchRoute.app) 1 else 0, delivery.scanned.size)
            assertNull(links.pending)
        }
    }

    @Test fun aPaymentInFlightBlocksTheLink() {
        val link = "bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22?amount=0.0001"
        val links = PaymentLinks(); val delivery = Delivery()
        links.receive(view(link))
        // Send hasn't read its saved payment yet: the link waits rather than guessing.
        val unread = send().apply { invalidateForWalletChange() }
        assertTrue(unread.loading)
        links.deliver(unread, delivery)
        assertEquals(link, links.pending)
        val inFlight = send(SendAttempt(payment = arkPayment))
        assertTrue(inFlight.locked && !inFlight.loading)
        links.deliver(inFlight, delivery)
        assertTrue(links.blocked)
        assertEquals(emptyList<String>(), delivery.scanned)
        assertNull("a blocked link isn't applied later", links.pending)
        assertEquals("Finish the current payment before opening another payment request.", PaymentLinks.blockedMessage)
        // A journal Send couldn't read is a lock with its message, not loading: it blocks too.
        val unreadable = SendController(SendController.Dependencies(read = { error("disk") }, write = {}, reservation = { it(FakeSendWallet()) }), scope)
        runBlocking { unreadable.reloadForWalletChange() }
        assertTrue(unreadable.locked && !unreadable.loading)
        links.blocked = false; links.receive(view(link))
        links.deliver(unreadable, delivery)
        assertTrue(links.blocked)
        assertEquals(emptyList<String>(), delivery.scanned)
    }

    @Test fun aLinkIsAppliedOnceAcrossRecreationAndRestore() {
        val cold = "lightning:lnurlp://example.com/cold"
        val warm = "bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
        val ready = send(); val delivery = Delivery()
        val activity = Robolectric.buildActivity(Host::class.java, view(cold)).setup()
        activity.get().links.deliver(ready, delivery)
        assertEquals(listOf(cold), delivery.scanned)
        // A rotation or fold re-creates the activity with the same intent: nothing to apply again.
        activity.recreate()
        assertNull(activity.get().links.pending)
        // A warm link survives a rotation before it's applied (held in memory), then goes once.
        activity.newIntent(view(warm))
        activity.recreate()
        assertEquals(warm, activity.get().links.pending)
        activity.get().links.deliver(ready, delivery)
        activity.recreate()
        activity.get().links.deliver(ready, delivery)
        assertEquals(listOf(cold, warm), delivery.scanned)
        // Process death: the system re-delivers the first intent with the saved state, and memory is gone.
        val saved = Bundle().also { activity.saveInstanceState(it) }
        val restored = Robolectric.buildActivity(Host::class.java, view(cold)).setup(saved)
        assertNull(restored.get().links.pending)
        // Recents relaunches a finished task with its first intent and no saved state.
        val recents = Robolectric.buildActivity(Host::class.java, view(cold).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).setup()
        assertNull(recents.get().links.pending)
        // A link opened after the restore still arrives.
        restored.newIntent(view(warm))
        assertEquals(warm, restored.get().links.pending)
    }
}
