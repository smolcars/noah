package com.noahwallet.ui.onboarding

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel

internal enum class EmailStage { entry, code, verified }
internal enum class NotificationStage { ask, off, granted, distributor }

/**
 * Onboarding presentation state that must survive folding, rotation and activity recreation:
 * the scene clock, typed name, email, code digits, resend countdown and step timelines. Wallet
 * and server work still runs in AppModel; results land here so a pose change never repeats it.
 * Recovery words never live here (RecoveryPhrasePresentation keeps them under the secret rules).
 */
internal class OnboardingPresentation : ViewModel() {
    val scene = SceneClock()
    private val played = mutableSetOf<String>()
    private var visit = 0
    private var current: String? = null

    /** Called for every composition of [step]; only a real navigation starts a new visit, recreation keeps it. */
    fun arrive(step: String) {
        if (step == current) return
        current = step; visit++
        if (step == "welcome") reset()
        // Coming back to Notifications after granting asks again rather than skipping ahead.
        if (step == "notifications" && notificationStage == NotificationStage.granted) notificationStage = NotificationStage.ask
        // Back at Email after verifying: show the address again rather than bouncing forward.
        if (step == "email" && emailStage == EmailStage.verified) { emailStage = EmailStage.entry; otp = OtpEntry() }
    }
    /** Entrances play once per visit, so recreation never replays them. */
    fun firstShow(step: String) = played.add("$step#$visit")

    var betaOpen by mutableStateOf(false)
    var betaError by mutableStateOf<String?>(null)
    var languageOpen by mutableStateOf(false)

    /** Haptics fire only when a moment is reached live, never again after recreation. */
    val felt = mutableSetOf<String>()

    var name by mutableStateOf("")
    var availability by mutableStateOf(NameAvailability.empty)
    /** The name the last lookup answered for, so recreation doesn't check it again. */
    var checkedName: String? = null
    /** Shown on Done when a name was saved; skipped names show "Your wallet is ready." */
    var savedAddress by mutableStateOf<String?>(null)

    var email by mutableStateOf("")
    var emailStage by mutableStateOf(EmailStage.entry)
    var otp by mutableStateOf(OtpEntry())
    var codeSentAt by mutableLongStateOf(0L)
    var verifiedAt by mutableLongStateOf(0L)

    var notificationStage by mutableStateOf(NotificationStage.ask)
    var doneAt by mutableLongStateOf(0L)
    var restoreReadyAt by mutableLongStateOf(0L)

    /** A fresh Welcome starts a new onboarding (for example after deleting a wallet). */
    fun reset() {
        betaOpen = false; betaError = null; languageOpen = false; felt.clear()
        name = ""; availability = NameAvailability.empty; checkedName = null; savedAddress = null
        email = ""; emailStage = EmailStage.entry; otp = OtpEntry(); codeSentAt = 0L; verifiedAt = 0L
        notificationStage = NotificationStage.ask; doneAt = 0L; restoreReadyAt = 0L
    }
}
