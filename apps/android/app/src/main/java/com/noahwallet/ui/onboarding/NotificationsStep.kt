package com.noahwallet.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.localization.tr
import com.noahwallet.services.push.PushWork
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.launch.PlatformAccess
import com.noahwallet.ui.launch.PlatformGateRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Keep Noah awake", or frozen and grey as "Notifications are required". Shared with the launch gate. */
@Composable
internal fun NotificationsContent(clock: SceneClock, off: Boolean, busy: Boolean = false, onPrimary: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(off) {
        clock.frozenAt = if (off) clock.frozenAt ?: clock.time(System.nanoTime()) else null
    }
    SceneLayout(clock, SceneMode.conveyor, below = {
        if (LocalPose.current == OnboardingPose.Compact) Spacer(Modifier.height(NoahTokens.Space.xxl))
        AnimatedContent(off, transitionSpec = {
            fadeIn(if (reduceMotion) tween(180) else NoahTokens.Motion.snappy()) togetherWith fadeOut(if (reduceMotion) tween(180) else NoahTokens.Motion.snappy())
        }, label = "notifications") { required ->
            if (required) Headline(tr("Notifications are required"), tr("Without them, Noah can’t receive payments or renew your bitcoin before it expires."),
                centered = LocalPose.current == OnboardingPose.Compact, modifier = Modifier.fillMaxWidth())
            else Headline(tr("Keep Noah awake"), tr("Notifications let Noah receive payments and renew your bitcoin before it expires."),
                centered = LocalPose.current == OnboardingPose.Compact, modifier = Modifier.fillMaxWidth())
        }
    }, bottom = {
        Spacer(Modifier.height(NoahTokens.Space.screen))
        PillButton(if (off) tr("Open Settings") else tr("Turn on notifications"), Modifier.fillMaxWidth().testTag(if (off) "notifications-open-settings" else "notifications-enable"),
            loading = busy, onClick = onPrimary)
    })
}

/**
 * Required, no skip. Grant (or return from Settings with notifications on) continues; without Google Play
 * Services the existing UnifiedPush distributor setup must finish before Done, then battery if needed.
 */
@Composable
internal fun NotificationsStep(model: AppModel, presentation: OnboardingPresentation) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val stage = presentation.notificationStage
    fun granted() = PlatformAccess.permission(context) == PlatformGateRules.Permission.granted
    fun next() { model.step = PlatformGateRules.afterNotifications(PlatformAccess.batteryEnabled(context)) }
    fun allowed() {
        if (presentation.notificationStage in setOf(NotificationStage.granted, NotificationStage.distributor)) return
        haptics.success()
        scope.launch(Dispatchers.IO) { PushWork.register(context) }
        presentation.notificationStage = NotificationStage.granted
    }
    fun required() { haptics.warning(); presentation.notificationStage = NotificationStage.off }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok || granted()) allowed() else required()
    }
    LaunchedEffect(Unit) {
        // Asked before and still off: only Settings can turn notifications on.
        if (stage == NotificationStage.ask && !granted() && (PlatformAccess.notificationsAsked(context) || Build.VERSION.SDK_INT < 33))
            presentation.notificationStage = NotificationStage.off
    }
    LaunchedEffect(stage) {
        if (stage != NotificationStage.granted) return@LaunchedEffect
        delay(280)
        if (withContext(Dispatchers.IO) { PlatformAccess.unifiedPush(context) }) presentation.notificationStage = NotificationStage.distributor
        else next()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(stage, lifecycle) {
        // Back from Settings: continue as soon as notifications are on.
        if (stage == NotificationStage.off) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { if (granted()) allowed() }
    }
    if (stage == NotificationStage.distributor) {
        // Today's distributor setup, kept clear of any fold: one column, or the trailing pane beside the conveyor.
        LaunchedEffect(Unit) { presentation.scene.frozenAt = null }
        when (val pose = LocalPose.current) {
            OnboardingPose.Compact -> com.noahwallet.ui.launch.UnifiedPushScreen(fromOnboarding = true) { next() }
            else -> SplitPanes(pose, picture = {
                Box(Modifier.fillMaxSize().padding(NoahTokens.Space.screen).sceneSlot(presentation.scene, SceneMode.conveyor, fill = true))
            }) { com.noahwallet.ui.launch.UnifiedPushScreen(fromOnboarding = true) { next() } }
        }
        return
    }
    NotificationsContent(presentation.scene, off = stage == NotificationStage.off) {
        when {
            stage == NotificationStage.off -> PlatformAccess.openNotificationSettings(context)
            granted() -> allowed()
            Build.VERSION.SDK_INT >= 33 -> { haptics.light(); PlatformAccess.markNotificationsAsked(context); permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else -> required()
        }
    }
}
