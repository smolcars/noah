package com.noahwallet.widgets

import com.noahwallet.R

class NoahWidgetRegtestProvider : NoahWidgetProvider() {
    override val appGroup: String = "com.noahwallet.regtest"
    override val variantName: String = "REGTEST"
    override val layoutResId: Int = R.layout.widget_noah
}
