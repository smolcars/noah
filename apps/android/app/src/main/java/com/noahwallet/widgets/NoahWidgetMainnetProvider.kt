package com.noahwallet.widgets

import com.noahwallet.R

class NoahWidgetMainnetProvider : NoahWidgetProvider() {
    override val appGroup: String = "com.noahwallet.mainnet"
    override val variantName: String? = null
    override val layoutResId: Int = R.layout.widget_noah
}
