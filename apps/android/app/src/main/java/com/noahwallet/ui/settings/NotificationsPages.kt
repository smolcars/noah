package com.noahwallet.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.DetailRow
import com.noahwallet.ui.activity.ValueKind
import com.noahwallet.ui.activity.short
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.launch.PlatformAccess
import com.noahwallet.ui.launch.PlatformGateRules
import com.noahwallet.ui.launch.rememberUnifiedPushSetup
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics

/**
 * Notifications (prototype `SCR.notif`): whether Noah may notify you (Open Settings when off), "Claim payments in the
 * background" (today's mailbox switch), Battery (Android's optimisation as the value; it opens the system setting) and,
 * only without Google Play, UnifiedPush. The launch push gate is unchanged. [unifiedPush] is decided by the caller.
 */
@Composable
internal fun NotificationsPage(model: AppModel, nav: SettingsNav, live: SettingsLive,
    unifiedPush: Boolean = PlatformAccess.unifiedPush(LocalContext.current)) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    var server by remember { mutableStateOf(model.server.state) }
    val on = live.permission == PlatformGateRules.Permission.granted
    val known = live.permission != PlatformGateRules.Permission.checking
    val battery = SettingsRules.battery(live.batteryOptimized)
    val noBattery = tr("Couldn't open battery settings")
    val distributor = if (unifiedPush) rememberUnifiedPushSetup() else null
    SettingsFrame(tr("Notifications"), nav.back, foot = if (known && !on) ({
        FootPill(tr("Open Settings"), Modifier.testTag("notifications-open-settings")) { PlatformAccess.openNotificationSettings(context) }
    }) else null) {
        if (known) NotificationsStatus(on)
        SettingsGroup(rows = listOfNotNull(
            { d -> SettingsRow(tr("Claim payments in the background"), SettingsIcons.bolt, sub = tr("Lets Noah wake up for Lightning"), divider = d,
                modifier = Modifier.testTag("notifications-mailbox"),
                end = RowEnd.Toggle(server.isMailboxAuthorizationEnabled != false, !model.busy && !model.isWalletSuspended) {
                    model.action("Couldn't update mailbox notifications.") { model.server.setMailboxEnabled(it); server = model.server.state }
                }) },
            { d -> SettingsRow(tr("Battery"), SettingsIcons.battery,
                value = if (battery == SettingsRules.Battery.unrestricted) tr("Unrestricted") else tr("Optimized"),
                divider = d, modifier = Modifier.testTag("notifications-battery")) { if (!PlatformAccess.openBattery(context)) toasts.show(noBattery) } },
            if (distributor != null) ({ d -> UnifiedPushRow(distributor, d) { nav.open(SettingsDestination.UnifiedPush) } }) else null,
            { d -> SettingsRow(tr("System settings"), SettingsIcons.gear, end = RowEnd.External,
                divider = d) { PlatformAccess.openNotificationSettings(context) } },
        ))
        if (known && !on) FinePrint(tr("Turn them on in Settings. Noah checks again when you come back."))
    }
}

/**
 * UnifiedPush (prototype `SCR.unifiedpush`), only without Google Play: the installed distributors as a radio list, this
 * app's endpoint with copy, and Register. None installed: how to get one.
 */
@Composable
internal fun UnifiedPushPage(nav: SettingsNav) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val setup = rememberUnifiedPushSetup()
    val registered = tr("Registered")
    val none = !setup.loading && setup.distributors.isEmpty()
    SettingsFrame(tr("UnifiedPush"), nav.back, line = tr("No Google Play here, so another app delivers notifications."), foot = {
        if (none) FootPill(tr("Check again"), outlined = true) { setup.reload() }
        else FootPill(if (setup.endpoint != null) tr("Register again") else tr("Register"), Modifier.testTag("unifiedpush-register"),
            enabled = !setup.loading && setup.registration != null, loading = setup.registering) {
            setup.register { ok -> if (ok) { haptics.success(); toasts.show(registered) } }
        }
    }) {
        if (none) Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SettingsAvatar(56.dp, icon = SettingsIcons.push)
            Text(tr("No distributor found"), Modifier.padding(top = 26.dp, bottom = 6.dp), style = HomeType.listTitle, color = palette.foreground)
            Text(tr("Install ntfy from F-Droid, then come back."), style = HomeType.line, color = palette.muted, textAlign = TextAlign.Center)
            return@SettingsFrame
        }
        SettingsGroup(label = tr("Deliver with"), rows = setup.distributors.map { distributor -> { d ->
            RadioRow(distributor.name, setup.selected == distributor.id, detail = distributor.id, divider = d,
                modifier = Modifier.testTag("unifiedpush-${distributor.id}")) { haptics.selection(); setup.select(distributor.id) }
        } })
        Column(Modifier.padding(top = 22.dp)) {
            val endpoint = setup.endpoint
            if (endpoint != null) DetailRow(tr("Endpoint"), short(endpoint, 20, 4), ValueKind.mono, copy = endpoint, toasts = toasts, last = true)
            else DetailRow(tr("Endpoint"), tr("Not registered"), toasts = toasts, last = true)
        }
        if (setup.failed) FinePrint(tr("Couldn't register with UnifiedPush. Please try again."), failed = true)
    }
}

@Composable
private fun NotificationsStatus(on: Boolean) {
    val palette = LocalPalette.current
    if (on) StatusHeader(tr("On"), tr("Noah can receive and renew while closed.")) {
        StatusMark(MarkKind.idle, SettingsIcons.bell, palette.foreground, ring = palette.orange)
    }
    else StatusHeader(tr("Off"), tr("Noah can’t receive payments or renew your bitcoin."), failed = true) {
        StatusMark(MarkKind.failed, SettingsIcons.bellOff, palette.failure)
    }
}

/** UnifiedPush's distributor once registered, or that it isn't set up. */
@Composable
private fun UnifiedPushRow(distributor: com.noahwallet.ui.launch.UnifiedPushSetup, divider: Boolean, open: () -> Unit) {
    val name = distributor.distributors.firstOrNull { it.id == distributor.selected }?.name
    SettingsRow(tr("UnifiedPush"), SettingsIcons.push, value = if (distributor.endpoint != null) name ?: tr("Registered") else tr("Not set up"),
        dot = if (distributor.endpoint == null && !distributor.loading) RowDot.failure else null, divider = divider,
        modifier = Modifier.testTag("notifications-unifiedpush"), onClick = open)
}
