package com.noahwallet.ui.settings

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.BuildConfig
import com.noahwallet.localization.tr
import com.noahwallet.services.server.LightningIdentity
import com.noahwallet.services.server.Nostr
import com.noahwallet.services.server.ServerFailure
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.DetailRow
import com.noahwallet.ui.activity.ValueKind
import com.noahwallet.ui.activity.short
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.*
import com.noahwallet.ui.send.SendPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The server's profile as last read; it isn't observable, so pages re-read it after each change and on resume. */
@Composable
private fun rememberServerState(model: AppModel): MutableState<com.noahwallet.services.storage.PersistedServerState> {
    val state = remember { mutableStateOf(model.server.state) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { state.value = model.server.state } }
    return state
}

/**
 * Profile (prototype `SCR.profile`): your initial, name and address, then Name (a sheet), Lightning address, Email,
 * Nostr key, Wallet key (copy) and My code.
 */
@Composable
internal fun ProfilePage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    var server by rememberServerState(model)
    var key by remember { mutableStateOf("") }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by remember { mutableStateOf(profileName(model)) }
    val failed = tr("Couldn't refresh your profile. Please try again.")
    LaunchedEffect(Unit) {
        try {
            key = withContext(Dispatchers.IO) { com.noahwallet.services.server.ServerClient.loginKey(BuildConfig.APP_VARIANT,
                model.mnemonic().joinToString(" ")) }
            model.server.refreshProfile(); server = model.server.state
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { toasts.show(failed) }
    }
    val address = server.lightningAddress?.takeIf { it.isNotEmpty() }
    SettingsFrame(tr("Profile"), nav.back, hero = true) {
        Column(Modifier.padding(top = 6.dp)) {
            SettingsAvatar(84.dp, you = true, initial = address?.take(1) ?: name?.take(1), icon = SettingsIcons.person)
            Text(name ?: address?.substringBefore('@') ?: tr("Profile"), Modifier.padding(top = 16.dp), style = SettingsType.hero, color = palette.foreground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            address?.let { Text(it, Modifier.padding(top = 2.dp), style = HomeType.line, color = palette.muted) }
        }
        ProfileRows(server, name, key, nav) { naming = true }
        FinePrint(tr("Your email is only for urgent alerts about your wallet."))
    }
    if (naming) NameSheet(model, name.orEmpty(), { name = profileName(model) }) { naming = false }
}

/** Your name, address, email, keys and My code, each opening where it's changed (the wallet key copies). */
@Composable
private fun ProfileRows(server: com.noahwallet.services.storage.PersistedServerState, name: String?, key: String, nav: SettingsNav, rename: () -> Unit) {
    val toasts = LocalSettingsToasts.current
    val address = server.lightningAddress?.takeIf { it.isNotEmpty() }
    val npub = Nostr.npub(server.nostrPubkey)
    val email = server.emailAddress
    Column(Modifier.padding(top = 22.dp)) {
        DetailRow(tr("Name"), name ?: tr("Add"), toasts = toasts, onClick = rename)
        DetailRow(tr("Lightning address"), address ?: tr("Add"), toasts = toasts) { nav.open(SettingsDestination.Lightning) }
        val verified = server.isEmailVerified == true && email != null
        DetailRow(tr("Email"), if (verified) email else if (email != null) tr("Not verified") else tr("Add"),
            ok = verified, dot = !verified && email != null, toasts = toasts) { nav.open(SettingsDestination.Email) }
        if (npub.isNotEmpty()) DetailRow(tr("Nostr key"), short(npub, 9, 4), ValueKind.mono, toasts = toasts) { nav.open(SettingsDestination.Lightning) }
        if (key.isNotEmpty()) DetailRow(tr("Wallet key"), short(key, 8, 4), ValueKind.mono, copy = key, toasts = toasts)
        DetailRow(tr("My code"), "", glyph = SettingsIcons.qr, toasts = toasts, last = true) { nav.myCode() }
    }
}

/** Name: shown to people you pay (max 80). */
@Composable
private fun NameSheet(model: AppModel, current: String, saved: () -> Unit, dismiss: () -> Unit) {
    val toasts = LocalSettingsToasts.current
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf(current) }
    val done = tr("Saved")
    SettingsSheet(dismiss, tr("Name"), tr("Shown to people you pay.")) { close ->
        SettingsField(name, { name = limitedProfileName(it) }, tr("Name"), Modifier.testTag("profile-name-input"), enabled = !model.busy,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Row(Modifier.fillMaxWidth().padding(top = 22.dp)) {
            SendPill(tr("Save"), Modifier.weight(1f).testTag("profile-name-save"), enabled = !model.busy, loading = model.busy) {
                model.action("Couldn't save your name.") {
                    model.server.saveDisplayName(name); saved(); haptics.success(); toasts.show(done); close()
                }
            }
        }
    }
}

/**
 * Lightning address (prototype `SCR.lnaddr`): onboarding's name field with live availability and suggestions, then the
 * optional Nostr key. Pasting an nsec says so instead of saving. No Skip here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LightningAddressPage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val server by rememberServerState(model)
    val domain = LightningIdentity.domain(BuildConfig.APP_VARIANT)
    val own = server.lightningAddress?.substringBefore('@')
    var name by rememberSaveable { mutableStateOf(own.orEmpty()) }
    var npub by rememberSaveable { mutableStateOf(Nostr.npub(server.nostrPubkey)) }
    val availabilityState = remember { mutableStateOf(NameAvailability.typing(name)) }
    var availability by availabilityState
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        error = null
        checkName(model, name, own, availabilityState, haptics)
    }
    val nsec = npub.trim().startsWith("nsec", ignoreCase = true)
    val unchanged = LightningIdentity.unchanged(name, npub, server.lightningAddress, server.nostrPubkey, domain)
    val saved = tr("Saved")
    fun save() {
        if (unchanged || nsec) return
        if (!availability.canContinue || model.busy) return
        try { LightningIdentity.payload(name, npub) } catch (failure: IllegalArgumentException) { error = failure.message; return }
        focusManager.clearFocus()
        nav.save(model, "Failed to update Lightning address. Please try again.", useServerMessage = true) {
            if (!saveIdentity(model, name, npub)) { availability = NameAvailability.taken; haptics.warning(); return@save }
            haptics.success(); toasts.show(saved); nav.pop()
        }
    }
    SettingsFrame(tr("Lightning address"), nav.back, line = tr("How people pay you."), foot = {
        FootPill(tr("Save"), Modifier.testTag("lightning-save"), enabled = !unchanged && !nsec && availability.canContinue, loading = model.busy) { save() }
    }) {
        NameField(name, { name = it }, domain, !model.busy, focus, tr("Lightning address"), { save() },
            Modifier.padding(top = 30.dp).testTag("lightning-username-input"))
        NameStatus(if (nsec) tr("Never paste a private key. Use your npub.") else error?.let { tr(it) }, availability, name, own) { name = it }
        SettingsField(npub, { npub = it.trim() }, "npub1…", Modifier.testTag("lightning-npub-input"), label = tr("Nostr key (optional)"),
            failed = nsec, enabled = !model.busy, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        FinePrint(tr("Adds a NIP-05 identity to the same address. Never paste an nsec."))
    }
}

/** Saves the address and key; false when the server says someone else just took the name. */
private suspend fun saveIdentity(model: AppModel, name: String, npub: String): Boolean {
    try { model.server.saveIdentity(name, npub) }
    catch (failure: ServerFailure) {
        if (!NameRules.takenByServer(failure)) throw failure
        return false
    }
    return true
}

/** Whether [name] is free: at once while typing or when it's yours, else asked after a half-second pause. */
private suspend fun checkName(model: AppModel, name: String, own: String?, availability: MutableState<NameAvailability>,
    haptics: com.noahwallet.ui.onboarding.Haptics) {
    val typing = NameAvailability.typing(name)
    if (typing != NameAvailability.checking) { availability.value = typing; return }
    if (name == own) { availability.value = NameAvailability.available; return }
    availability.value = NameAvailability.checking
    delay(500)
    val answer = NameAvailability.answered(name, lookup(model, name), own)
    availability.value = answer
    if (answer == NameAvailability.taken) haptics.warning() else if (answer == NameAvailability.available) haptics.selection()
}

/** Under the name: what's wrong with it, else whether it's free (with free names like it when it's taken). */
@Composable
private fun NameStatus(problem: String?, availability: NameAvailability, name: String, own: String?, pick: (String) -> Unit) {
    if (problem != null) NameProblem(problem)
    else AvailabilityRow(availability, Modifier.padding(top = 14.dp), mine = name == own && name.isNotEmpty())
    if (availability == NameAvailability.taken) Suggestions(name, pick)
}

@Composable
private fun NameProblem(text: String) {
    val palette = LocalPalette.current
    Row(Modifier.padding(top = 14.dp).heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(palette.failure, CircleShape))
        Text(text, style = HomeType.line, color = palette.failure)
    }
}

/** Names like [taken] that may be free, each a tap to [pick]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(taken: String, pick: (String) -> Unit) {
    val haptics = rememberHaptics()
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NameRules.suggestions(taken).forEach { suggestion -> Chip(suggestion) { haptics.selection(); pick(suggestion) } }
    }
}

/**
 * Email (prototype `SCR.email`): onboarding's flow — your current address, the new one, six code boxes with autofill and
 * resend, the shake on a wrong code, and the verified ring. "Already verified" skips the code.
 */
@Composable
internal fun EmailPage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val presentation = androidx.lifecycle.viewmodel.compose.viewModel<OnboardingPresentation>(key = "settings-email")
    // A new visit starts empty; recreation keeps what was typed and the code step.
    rememberSaveable { presentation.reset(); true }
    var server by rememberServerState(model)
    val stage = presentation.emailStage
    val email = EmailVerification(model, presentation, haptics, focusManager, beforeSend = { model.server.ensureRegistered() }) {
        server = model.server.state
    }
    BackHandler(enabled = stage == EmailStage.code) { if (!model.busy) presentation.emailStage = EmailStage.entry }
    val line = when (stage) {
        EmailStage.entry -> tr("Only for urgent alerts about your wallet.")
        EmailStage.code -> tr("Enter the code we sent to {email}", presentation.email)
        EmailStage.verified -> tr("Email verified")
    }
    SettingsFrame(tr("Email"), nav.back, line = line, foot = when (stage) {
        EmailStage.entry -> ({ FootPill(tr("Send code"), Modifier.testTag("email-send"), enabled = EmailRules.isValid(presentation.email),
            loading = model.busy) { email.send() } })
        EmailStage.verified -> ({ FootPill(tr("Done")) { nav.pop() } })
        EmailStage.code -> null
    }) {
        if (stage == EmailStage.entry) {
            server.emailAddress?.let { CurrentEmail(it, server.isEmailVerified == true) }
            EmailEntry(presentation, play = false, enabled = !model.busy) { email.send() }
            FinePrint(if (server.isEmailVerified == true) tr("Your current email stays until the new one is verified.")
            else tr("Optional. We only write when your bitcoin needs you."))
        } else {
            CodeEntry(model, presentation, onTyped = email::typed, resend = { email.send(resend = true) })
            if (stage == EmailStage.code) SettingsLink(tr("Change email")) { presentation.emailStage = EmailStage.entry }
        }
    }
}

/** The small orange check beside a verified email (prototype `.okc`). */
@Composable
private fun VerifiedMark() {
    val palette = LocalPalette.current
    androidx.compose.foundation.layout.Box(Modifier.size(16.dp).background(palette.orange, CircleShape), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(SettingsIcons.check), null, Modifier.size(11.dp),
            tint = androidx.compose.ui.graphics.Color.White)
    }
}

/** The address on file, and whether it's verified. */
@Composable
private fun CurrentEmail(current: String, verified: Boolean) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(current, style = SettingsType.current, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (verified) VerifiedMark() else androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(palette.orange, CircleShape))
            Text(if (verified) tr("Verified") else tr("Not verified"), style = HomeType.line, color = palette.muted)
        }
    }
}
