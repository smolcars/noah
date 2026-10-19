package com.noahwallet.widgets

import com.noahwallet.R

class NoahWidgetSignetProvider : NoahWidgetProvider() {
    override val appGroup: String = "com.noahwallet.signet"
    override val variantName: String = "SIGNET"
    override val layoutResId: Int = R.layout.widget_noah
    override val badgeBackgroundResId: Int = R.drawable.badge_background_orange
}
