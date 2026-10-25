package com.noahwallet.ui.onboarding

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Optional email: entry, then a six-digit code, then verified. Server answers land in the presentation
 * ViewModel, so a fold or rotation never sends or verifies twice. "Already verified" skips the code.
 */
@Composable
internal fun EmailStep(model: AppModel, presentation: OnboardingPresentation) {
    val haptics = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val play = remember { presentation.firstShow("email") }
    val stage = presentation.emailStage
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(Unit) {
        val state = model.server.state
        if (presentation.email.isEmpty() && state.isEmailVerified == true) state.emailAddress?.let { presentation.email = it }
    }
    fun verified() {
        presentation.verifiedAt = SystemClock.elapsedRealtime(); presentation.emailStage = EmailStage.verified
        presentation.otp = presentation.otp.accepted()
        if (presentation.felt.add("email-verified")) haptics.success()
    }
    fun send(resend: Boolean = false) {
        val email = presentation.email
        if (!EmailRules.isValid(email) || model.busy) return
        haptics.light()
        model.action("Failed to send verification code. Please try again.", useServerMessage = true) {
            if (model.server.sendEmail(email)) { verified(); return@action }
            presentation.otp = OtpEntry(); presentation.codeSentAt = SystemClock.elapsedRealtime()
            if (!resend) { presentation.emailStage = EmailStage.code; haptics.success() }
        }
    }
    fun verify(code: String) {
        model.action("Invalid or expired verification code. Please try again.", useServerMessage = true) {
            try { model.server.verifyEmail(code, presentation.email) }
            catch (failure: Exception) {
                if (!wrongCode(failure)) { presentation.otp = presentation.otp.copy(verifying = false).cleared(); throw failure }
                presentation.otp = presentation.otp.rejected(); haptics.error()
                delay(650)
                presentation.otp = presentation.otp.cleared()
                return@action
            }
            focusManager.clearFocus()
            verified()
        }
    }
    LaunchedEffect(stage) {
        if (stage != EmailStage.verified) return@LaunchedEffect
        delay((1500 - (SystemClock.elapsedRealtime() - presentation.verifiedAt)).coerceAtLeast(0))
        model.step = "notifications"
    }
    BackHandler(enabled = stage == EmailStage.code) { if (!model.busy) presentation.emailStage = EmailStage.entry }
    val transition: AnimatedContentTransitionScope<EmailStage>.() -> ContentTransform = {
        val forward = targetState.ordinal > initialState.ordinal
        if (reduceMotion) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
        else (slideInVertically(NoahTokens.Motion.snappy()) { if (forward) it / 4 else -it / 4 } + fadeIn(NoahTokens.Motion.snappy())) togetherWith
            (slideOutVertically(NoahTokens.Motion.snappy()) { if (forward) -it / 4 else it / 4 } + fadeOut(NoahTokens.Motion.snappy()))
    }
    FormLayout(trailingTop = {
        AnimatedVisibility(stage != EmailStage.verified, enter = fadeIn(), exit = fadeOut()) {
            TextAction(tr("Skip"), Modifier.testTag("onboarding-email-skip"), muted = true, enabled = !model.busy) { haptics.light(); focusManager.clearFocus(); model.step = "notifications" }
        }
    }, headline = { large ->
        AnimatedContent(stage == EmailStage.entry, transitionSpec = {
            if (reduceMotion) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            else fadeIn(NoahTokens.Motion.snappy()) togetherWith fadeOut(NoahTokens.Motion.snappy())
        }, label = "email-headline") { entry ->
            if (entry) Headline(tr("Add your email"), tr("Only for urgent alerts about your wallet."), large, modifier = Modifier.entrance(0, play))
            else CodeHeadline(presentation, large, stage == EmailStage.verified) { presentation.emailStage = EmailStage.entry }
        }
    }, form = {
        AnimatedContent(stage, transitionSpec = transition, label = "email-form") { shown ->
            when (shown) {
                EmailStage.entry -> EmailEntry(presentation, play, enabled = !model.busy) { send() }
                else -> CodeEntry(model, presentation, onTyped = { input ->
                    val (next, verifyNow) = presentation.otp.input(input, busy = model.busy)
                    presentation.otp = next
                    if (verifyNow) verify(next.code)
                }, resend = { send(resend = true) })
            }
        }
    }, actions = {
        if (stage == EmailStage.entry) SendButton(enabled = EmailRules.isValid(presentation.email), sending = model.busy) { send() }
    })
}

@Composable
private fun CodeHeadline(presentation: OnboardingPresentation, large: Boolean, verified: Boolean, edit: () -> Unit) {
    val palette = LocalPalette.current
    Column {
        AnimatedContent(verified, transitionSpec = { (fadeIn() + slideInVertically { it / 4 }) togetherWith fadeOut() }, label = "verified-title") { done ->
            Text(if (done) tr("Email verified") else tr("Check your email"), Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = if (large) NoahTokens.Typography.handle else NoahTokens.Typography.title, color = palette.foreground)
        }
        if (!verified) FlowRow(Modifier.padding(top = NoahTokens.Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(tr("Enter the code we sent to {email}", presentation.email), style = NoahTokens.Typography.body, color = palette.muted)
            TextButton(edit, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 32.dp)) {
                Text(tr("Edit"), style = NoahTokens.Typography.body.copy(fontWeight = FontWeight.SemiBold), color = palette.foreground)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmailEntry(presentation: OnboardingPresentation, play: Boolean, enabled: Boolean, send: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val email = presentation.email
    val valid = EmailRules.isValid(email)
    val check = remember { Animatable(if (valid) 1f else 0f) }
    LaunchedEffect(valid) {
        if (valid) { if (check.value < 1f) { haptics.selection(); check.animateTo(1f, NoahTokens.Motion.snappy()) } } else check.snapTo(0f)
    }
    LaunchedEffect(Unit) { if (play) { delay(380); runCatching { focus.requestFocus() } } else runCatching { focus.requestFocus() } }
    Column {
        Row(Modifier.padding(top = formGap(36.dp)).entrance(2, play), verticalAlignment = Alignment.CenterVertically) {
            val label = tr("Add your email")
            BasicTextField(email, { presentation.email = EmailRules.normalize(it) },
                Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label; contentType = ContentType.EmailAddress }.testTag("email-address-input"),
                enabled = enabled, textStyle = NoahTokens.Typography.field.copy(color = palette.foreground),
                cursorBrush = SolidColor(palette.orange), singleLine = true,
                keyboardOptions = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner ->
                    Box {
                        if (email.isEmpty()) Text(tr("you@example.com"), Modifier.clearAndSetSemantics { },
                            style = NoahTokens.Typography.field, color = palette.muted.copy(alpha = .45f))
                        inner()
                    }
                })
            Box(Modifier.padding(start = NoahTokens.Space.m - 2.dp).alpha(if (check.value > 0f) 1f else 0f)) { EmailCheckIcon(palette.orange, check.value) }
        }
        Spacer(Modifier.height(NoahTokens.Space.m))
        Underline(focused)
        val hint = EmailRules.hint(email)
        FlowRow(Modifier.padding(top = 14.dp).heightIn(min = 36.dp), horizontalArrangement = Arrangement.spacedBy(NoahTokens.Space.s),
            verticalArrangement = Arrangement.spacedBy(NoahTokens.Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
            when (hint) {
                is EmailRules.Hint.Typo -> {
                    Text(tr("Did you mean"), style = NoahTokens.Typography.sub, color = palette.muted)
                    Chip(hint.suggestion) { haptics.selection(); presentation.email = hint.suggestion }
                }
                is EmailRules.Hint.Completions -> hint.emails.forEachIndexed { index, suggestion ->
                    key(suggestion) { Chip("@" + suggestion.substringAfter('@'), Modifier.entrance(index, true, rise = 6f)) {
                        haptics.selection(); presentation.email = suggestion
                    } }
                }
                null -> Unit
            }
        }
    }
}

/** "Send code" morphs into a spinner while sending. */
@Composable
internal fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width by animateDpAsState(if (sending) NoahTokens.Size.button else maxWidth, NoahTokens.Motion.snappy(), label = "send")
        PillButton(tr("Send code"), Modifier.width(width).testTag("onboarding-email-send"), enabled = enabled, loading = sending, onClick = onClick)
    }
}

@Composable
internal fun CodeEntry(model: AppModel, presentation: OnboardingPresentation, onTyped: (String) -> Unit, resend: () -> Unit) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val otp = presentation.otp
    val verified = presentation.emailStage == EmailStage.verified
    val focus = remember { FocusRequester() }
    val shake = rememberShake()
    LaunchedEffect(otp.failed) { if (otp.failed && !reduceMotion) shake.shake(listOf(-10f, 9f, -5f), 400) }
    LaunchedEffect(verified) { if (!verified) runCatching { focus.requestFocus() } }
    // Boxes fold into the check ring once verified; measured from verification so recreation doesn't replay it.
    val sinceVerified = if (verified) SystemClock.elapsedRealtime() - presentation.verifiedAt else 0L
    val fold = remember(verified) { Animatable(if (verified) (sinceVerified / 400f).coerceIn(0f, 1f) else 0f) }
    val ring = remember(verified) { Animatable(if (verified) ((sinceVerified - 180) / 700f).coerceIn(0f, 1f) else 0f) }
    val tick = remember(verified) { Animatable(if (verified) ((sinceVerified - 620) / 300f).coerceIn(0f, 1f) else 0f) }
    LaunchedEffect(verified) {
        if (!verified) return@LaunchedEffect
        if (reduceMotion) { fold.snapTo(1f); ring.snapTo(1f); tick.snapTo(1f); return@LaunchedEffect }
        kotlinx.coroutines.coroutineScope {
            launch { fold.animateTo(1f, NoahTokens.Motion.snappy()) }
            launch { delay((180 - sinceVerified).coerceAtLeast(0)); ring.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            launch { delay((620 - sinceVerified).coerceAtLeast(0)); tick.animateTo(1f, NoahTokens.Motion.snappy()) }
        }
    }
    Column {
        val label = tr("Verification code")
        var rowWidth by remember { mutableIntStateOf(0) }
        BasicTextField(otp.code, onTyped, Modifier.padding(top = formGap(34.dp)).fillMaxWidth().focusRequester(focus)
            .graphicsLayer { translationX = shake.value * density }
            .semantics { contentDescription = label; contentType = ContentType.SmsOtpCode }.testTag("verification-code-input"),
            // Read-only while a request runs keeps the keyboard up but takes no digits.
            enabled = !verified, readOnly = model.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            singleLine = true, cursorBrush = SolidColor(palette.orange.copy(alpha = 0f)),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().height(NoahTokens.Size.otpBox).onSizeChanged { rowWidth = it.width }, contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(NoahTokens.Space.s)) {
                        repeat(OtpEntry.length) { index ->
                            OtpBox(otp.code.getOrNull(index)?.toString().orEmpty(), active = index == otp.activeIndex, failed = otp.failed,
                                ok = otp.verifying || verified, fold = fold.value, index = index, rowWidth = rowWidth, modifier = Modifier.weight(1f))
                        }
                    }
                    if (verified) VerifiedRing(ring.value, tick.value)
                    Box(Modifier.size(1.dp).alpha(0f)) { inner() }
                }
            })
        Text(if (otp.failed) tr("That code didn’t work. Try again.") else "", Modifier.padding(top = 18.dp).heightIn(min = 20.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive }, style = NoahTokens.Typography.sub, color = palette.failure)
        if (!verified) ResendLine(presentation, enabled = !model.busy, resend)
    }
}

@Composable
private fun OtpBox(digit: String, active: Boolean, failed: Boolean, ok: Boolean, fold: Float, index: Int, rowWidth: Int, modifier: Modifier) {
    val palette = LocalPalette.current
    val pop = remember { Animatable(1f) }
    LaunchedEffect(digit) { if (digit.isNotEmpty()) { pop.snapTo(.7f); pop.animateTo(1f, NoahTokens.Motion.bouncy()) } }
    val color = when { failed -> palette.failure; active || ok -> palette.orange; else -> palette.hairline }
    val width = if (active || ok) 2.dp else 1.5.dp
    Box(modifier.fillMaxHeight().graphicsLayer {
        // Fold toward the centre of the row, shrinking and fading.
        val boxWidth = size.width
        val spacing = (rowWidth - boxWidth * OtpEntry.length) / (OtpEntry.length - 1)
        val centre = rowWidth / 2f - (index * (boxWidth + spacing) + boxWidth / 2)
        translationX = centre * fold
        val s = 1 - .65f * fold
        scaleX = s * pop.value; scaleY = s * pop.value
        alpha = (1 - fold) * (.3f + .7f * ((pop.value - .7f) / .3f).coerceIn(0f, 1f))
    }.border(width, color, RoundedCornerShape(NoahTokens.Radius.field)), contentAlignment = Alignment.Center) {
        if (digit.isNotEmpty()) Text(digit, style = NoahTokens.Typography.code, color = if (failed) palette.failure else palette.foreground)
        else if (active) Caret()
    }
}

/** The active box's blinking caret; read while drawing, so the row doesn't recompose each frame. */
@Composable
private fun Caret() {
    val palette = LocalPalette.current
    val blink = rememberInfiniteTransition(label = "caret")
    val caret = blink.animateFloat(1f, 0f, infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 1f at 499; 0f at 500 }), label = "blink")
    Box(Modifier.width(2.dp).height(26.dp).graphicsLayer { alpha = caret.value }.background(palette.orange, RoundedCornerShape(1.dp)))
}

@Composable
private fun VerifiedRing(ring: Float, tick: Float) {
    val palette = LocalPalette.current
    Canvas(Modifier.size(60.dp)) {
        val scale = size.width / 60f
        drawArc(palette.orange, -90f, 360f * ring, false, Offset(2f * scale, 2f * scale), Size(56f * scale, 56f * scale), style = Stroke(2.5f * scale))
        val check = Path().apply { moveTo(19f * scale, 31f * scale); lineTo(26.5f * scale, 38.5f * scale); lineTo(42f * scale, 23f * scale) }
        if (tick > 0f) drawPath(trimmed(check, tick), palette.foreground, style = Stroke(3f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun ResendLine(presentation: OnboardingPresentation, enabled: Boolean, resend: () -> Unit) {
    val palette = LocalPalette.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val remaining = ResendTimer.remaining(presentation.codeSentAt, now)
    LaunchedEffect(presentation.codeSentAt) {
        while (ResendTimer.remaining(presentation.codeSentAt, now) > 0) {
            delay(1000 - (SystemClock.elapsedRealtime() - presentation.codeSentAt) % 1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    Row(Modifier.padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget), verticalAlignment = Alignment.CenterVertically) {
        if (remaining > 0) Text(tr("Resend code in {time}", ResendTimer.clock(remaining)), style = NoahTokens.Typography.sub, color = palette.muted)
        else {
            Text(tr("Didn’t get it?"), style = NoahTokens.Typography.sub, color = palette.muted)
            TextButton(resend, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(tr("Resend"), style = NoahTokens.Typography.sub.copy(fontWeight = FontWeight.SemiBold), color = palette.foreground)
            }
        }
    }
}
