package com.noahwallet.ui.launch

import com.noahwallet.services.launch.LaunchState

/** RN Navigators: only resolved DENIED blocks; undetermined and emulators do not. */
object PlatformGateRules {
    fun launchResolved(state: LaunchState?) = state == LaunchState.onboarding || state == LaunchState.existingWallet
    enum class Permission { checking, undetermined, granted, denied }
    enum class Gate { push, battery }
    fun gate(initialized: Boolean, physical: Boolean, permission: Permission, batteryEnabled: Boolean, batterySeen: Boolean): Gate? = when {
        !initialized -> null
        physical && permission == Permission.denied -> Gate.push
        !batterySeen && batteryEnabled -> Gate.battery
        else -> null
    }
    /** UnifiedPush setup happens inside the Notifications step; battery stays Android's own step. */
    fun afterNotifications(batteryEnabled: Boolean) = if (batteryEnabled) "battery" else "done"
}
