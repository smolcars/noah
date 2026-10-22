package com.noahwallet.ui.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.NetworkError
import com.noahwallet.api.generated.client.NetworkResult
import com.noahwallet.api.generated.client.WellKnownLnurlpClient
import com.noahwallet.localization.tr
import com.noahwallet.services.server.LightningIdentity
import com.noahwallet.services.server.Nostr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The public LNURL lookup only hints; `update_lightning_identity` decides. */
private suspend fun lookup(model: AppModel, name: String): NameLookup = withContext(Dispatchers.IO) {
    try {
        when (val result = WellKnownLnurlpClient(model.services.client).lnurlpRequest(name)) {
            is NetworkResult.Success -> NameLookup.found
            is NetworkResult.Failure -> (result.error as? NetworkError.Http)?.statusCode?.takeIf { it == 400 || it == 404 }
                ?.let { NameLookup.notFound } ?: NameLookup.failed
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { NameLookup.failed }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NameStep(model: AppModel, presentation: OnboardingPresentation) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val play = remember { presentation.firstShow("name") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val domain = LightningIdentity.domain(BuildConfig.APP_VARIANT)
    val name = presentation.name
    val availability = presentation.availability
    LaunchedEffect(Unit) { if (play) { delay(380); runCatching { focus.requestFocus() } } }
    LaunchedEffect(name) {
        val typing = NameAvailability.typing(name)
        if (typing != NameAvailability.checking) { presentation.availability = typing; return@LaunchedEffect }
        // Recreation re-runs this effect; keep an answer already given for the same name.
        if (presentation.checkedName == name && availability != NameAvailability.checking) return@LaunchedEffect
        presentation.availability = NameAvailability.checking
        delay(500)
        val own = model.server.state.lightningAddress?.substringBefore('@')
        val answer = NameAvailability.answered(name, lookup(model, name), own)
        presentation.checkedName = name
        presentation.availability = answer
        if (answer == NameAvailability.taken) haptics.warning() else if (answer == NameAvailability.available) haptics.selection()
    }
    fun finish(saved: String?) { presentation.savedAddress = saved; focusManager.clearFocus(); model.step = "email" }
    fun save() {
        if (!presentation.availability.canContinue || model.busy) return
        val chosen = presentation.name
        val state = model.server.state
        val npub = Nostr.npub(state.nostrPubkey)
        haptics.medium()
        if (LightningIdentity.unchanged(chosen, npub, state.lightningAddress, state.nostrPubkey, domain)) { finish(state.lightningAddress); return }
        try { LightningIdentity.payload(chosen, npub) } catch (failure: IllegalArgumentException) { model.error = failure.message; return }
        model.action("Failed to update Lightning address. Please try again.", useServerMessage = true) {
            try { model.server.saveIdentity(chosen, npub) }
            catch (failure: Exception) {
                if (!NameRules.takenByServer(failure)) throw failure
                presentation.checkedName = chosen; presentation.availability = NameAvailability.taken; haptics.warning()
                return@action
            }
            finish(model.server.state.lightningAddress)
        }
    }
    FormLayout(trailingTop = {
        TextAction(tr("Skip"), muted = true, enabled = !model.busy) { haptics.light(); finish(null) }
    }, headline = { large ->
        Headline(tr("Pick a name"), tr("This is how people pay you."), large, modifier = Modifier.entrance(0, play))
    }, form = {
        Column(Modifier.padding(top = NoahTokens.Space.xxxl).entrance(2, play)) {
            FlowRow(verticalArrangement = Arrangement.Bottom, itemVerticalAlignment = Alignment.Bottom) {
                val label = tr("Pick a name")
                BasicTextField(name, { presentation.name = NameRules.normalize(it) },
                    Modifier.width(IntrinsicSize.Min).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = label },
                    enabled = !model.busy,
                    textStyle = NoahTokens.Typography.handle.copy(color = palette.foreground),
                    cursorBrush = SolidColor(palette.orange), singleLine = true,
                    keyboardOptions = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) Text(tr("name"), style = NoahTokens.Typography.handle, color = palette.muted.copy(alpha = .4f),
                                modifier = Modifier.clearAndSetSemantics { })
                            inner()
                        }
                    })
                Text("@$domain", Modifier.padding(start = 3.dp, bottom = 6.dp),
                    style = NoahTokens.Typography.body.copy(fontWeight = FontWeight.Medium), color = palette.muted)
            }
            Spacer(Modifier.height(NoahTokens.Space.m))
            Underline(focused)
        }
        AvailabilityRow(availability, Modifier.padding(top = 14.dp).entrance(3, play))
        if (availability == NameAvailability.taken) FlowRow(Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(NoahTokens.Space.s), verticalArrangement = Arrangement.spacedBy(NoahTokens.Space.s)) {
            NameRules.suggestions(name).forEachIndexed { index, suggestion ->
                Chip(suggestion, Modifier.entrance(index, true, rise = 8f)) { haptics.selection(); presentation.name = suggestion }
            }
        }
    }, actions = {
        PillButton(tr("Continue"), Modifier.fillMaxWidth(), enabled = availability.canContinue, loading = model.busy) { save() }
    })
}

@Composable
private fun AvailabilityRow(state: NameAvailability, modifier: Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val pulse = rememberInfiniteTransition(label = "checking")
    val pulseAlpha by pulse.animateFloat(1f, .25f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "pulse")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state) { if (state == NameAvailability.available && !reduceMotion) { pop.snapTo(2f); pop.animateTo(1f, NoahTokens.Motion.bouncy()) } }
    val text = when (state) {
        NameAvailability.empty, NameAvailability.unknown -> tr("Letters and numbers")
        NameAvailability.tooShort -> tr("At least 3 characters")
        NameAvailability.checking -> tr("Checking")
        NameAvailability.available -> tr("Available")
        NameAvailability.taken -> tr("Taken")
    }
    Row(modifier.heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        val dot = Modifier.size(8.dp)
        when (state) {
            NameAvailability.checking -> Box(dot.alpha(if (reduceMotion) 1f else pulseAlpha).background(palette.orange, CircleShape))
            NameAvailability.available -> Box(dot.scale(pop.value).background(palette.orange, CircleShape))
            NameAvailability.taken -> Box(dot.border(1.5.dp, palette.muted, CircleShape))
            else -> Box(dot.background(palette.hairline, CircleShape))
        }
        Spacer(Modifier.width(NoahTokens.Space.s))
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = NoahTokens.Typography.sub,
            color = if (state == NameAvailability.available) palette.foreground else palette.muted)
    }
}

/** A hairline pill for suggestions and completions. */
@Composable
internal fun Chip(text: String, modifier: Modifier = Modifier, bold: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    // Material's minimum interactive size keeps the touch target at 48 dp around the 36 dp pill.
    OutlinedButton(onClick, modifier.height(NoahTokens.Size.chip), shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.hairline),
        contentPadding = PaddingValues(horizontal = 14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.foreground)) {
        Text(text, style = NoahTokens.Typography.sub.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, fontSize = 15.sp))
    }
}
