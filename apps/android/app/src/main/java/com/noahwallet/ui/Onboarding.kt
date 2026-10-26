package com.noahwallet.ui

import com.noahwallet.ui.WalletAlertDialog

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.togetherWith

/**
 * The redesigned onboarding: Welcome → Creating → Name → Email → Notifications (→ battery) → Done,
 * Welcome → Restore → Restoring, or (a stored phrase without wallet data) Recovery phrase found → Restoring.
 * One tree canvas sits behind every step; wallet, server and push logic stay in AppModel and the existing services.
 */
@Composable
fun Onboarding(model: AppModel) {
    val presentation = androidx.lifecycle.viewmodel.compose.viewModel<com.noahwallet.ui.onboarding.OnboardingPresentation>()
    presentation.arrive(model.step)
    var history by rememberSaveable { mutableStateOf(listOf(model.step)) }
    // Read by the transition when the step changes; not state, so setting it never recomposes.
    val direction = remember { object { var back = false } }
    LaunchedEffect(model.step) { history = OnboardingNavigation.visit(history, model.step); direction.back = false }
    fun goBack() {
        val previous = OnboardingNavigation.back(OnboardingNavigation.visit(history, model.step), model.busy, model.restore.canLeave)
        if (previous.last() == model.step) return
        history = previous; direction.back = true
        model.step = previous.last()
    }
    // Predictive back: never back out of creation, restore or Done (OnboardingNavigation's boundaries).
    BackHandler(enabled = model.step != "welcome") { goBack() }
    // One shield and clipboard from Restore or Recovery phrase found through Restoring and Ready: FLAG_SECURE and
    // clipboard clearing never lapse between steps, and end only when Home (or a launch gate) replaces onboarding.
    val activity = LocalContext.current as android.app.Activity
    val restoreClipboard = rememberRestoreClipboard(activity)
    if (model.step in setOf("restore", "storedSeed", "restoring")) RestoreShield(activity, model.walletLock, restoreClipboard) {}
    com.noahwallet.ui.onboarding.OnboardingFrame(presentation.scene) {
        // UnifiedPush setup keeps the conveyor beside it in split panes; one column has no room for it.
        val distributorAlone = presentation.notificationStage == com.noahwallet.ui.onboarding.NotificationStage.distributor &&
            com.noahwallet.ui.onboarding.LocalPose.current == com.noahwallet.ui.onboarding.OnboardingPose.Compact
        val scene = when (model.step) {
            "welcome" -> com.noahwallet.ui.onboarding.SceneMode.welcome
            "creating" -> com.noahwallet.ui.onboarding.SceneMode.create
            "restoring" -> com.noahwallet.ui.onboarding.SceneMode.restore
            "notifications" -> if (distributorAlone) com.noahwallet.ui.onboarding.SceneMode.none else com.noahwallet.ui.onboarding.SceneMode.conveyor
            else -> com.noahwallet.ui.onboarding.SceneMode.none
        }
        SideEffect { presentation.scene.show(scene) }
        val reduceMotion = com.noahwallet.ui.onboarding.LocalReduceMotion.current
        androidx.compose.animation.AnimatedContent(model.step, transitionSpec = {
            // A failed stored-phrase restore travels back to its screen, as on iOS.
            onboardingTransition(targetState, direction.back || targetState == "storedSeed", reduceMotion)
        }, label = "onboarding") { step ->
            when (step) {
                "welcome" -> com.noahwallet.ui.onboarding.WelcomeStep(model, presentation)
                "restore" -> RestoreWalletScreen(model, restoreClipboard)
                "storedSeed" -> RestoreWalletScreen(model, restoreClipboard, useStoredSeed = true)
                "creating" -> com.noahwallet.ui.onboarding.CreatingStep(model, presentation)
                "restoring" -> com.noahwallet.ui.onboarding.RestoringStep(model, presentation)
                "name" -> com.noahwallet.ui.onboarding.NameStep(model, presentation)
                "email" -> com.noahwallet.ui.onboarding.EmailStep(model, presentation)
                "notifications" -> com.noahwallet.ui.onboarding.NotificationsStep(model, presentation)
                "battery" -> com.noahwallet.ui.launch.BatteryOptimizationScreen(model, onBack = { goBack() }) { model.step = "done" }
                "done" -> com.noahwallet.ui.onboarding.DoneStep(model, presentation)
            }
        }
    }
}

/**
 * Steps slide in from the trailing edge (back: the leading edge) with a fade, on the snappy spring. Steps with
 * the tree only fade, so the shared canvas never slides; Reduce Motion fades everything in 180 ms.
 */
private fun onboardingTransition(target: String, back: Boolean, reduceMotion: Boolean): androidx.compose.animation.ContentTransform {
    val fade = androidx.compose.animation.core.tween<Float>(com.noahwallet.ui.design.NoahTokens.Motion.REDUCE_MOTION_FADE_MS)
    if (reduceMotion || target in setOf("welcome", "creating", "restoring", "notifications"))
        return androidx.compose.animation.fadeIn(if (reduceMotion) fade else com.noahwallet.ui.design.NoahTokens.Motion.snappy()) togetherWith
            androidx.compose.animation.fadeOut(if (reduceMotion) fade else com.noahwallet.ui.design.NoahTokens.Motion.snappy())
    val spring = com.noahwallet.ui.design.NoahTokens.Motion.snappy<androidx.compose.ui.unit.IntOffset>()
    val fadeSpring = com.noahwallet.ui.design.NoahTokens.Motion.snappy<Float>()
    return (androidx.compose.animation.slideInHorizontally(spring) { if (back) -it / 10 else it / 9 } + androidx.compose.animation.fadeIn(fadeSpring)) togetherWith
        (androidx.compose.animation.slideOutHorizontally(spring) { if (back) it / 9 else -it / 12 } + androidx.compose.animation.fadeOut(fadeSpring))
}
