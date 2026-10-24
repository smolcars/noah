package com.noahwallet.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeToastsTest {
    @Test fun hidingAmountsRetextsTheBoardingToastInPlace() {
        val toasts = HomeToasts()
        toasts.show("₿ 20,000 available in Ark", 3_200, tag = "boarding")
        val shown = checkNotNull(toasts.current)
        toasts.retext("boarding", "•••• available in Ark")
        val masked = checkNotNull(toasts.current)
        assertEquals("•••• available in Ark", masked.text)
        // The same toast: HomeToast keys its run on the id, so it keeps its remaining time.
        assertEquals(shown.id, masked.id)
        assertEquals(shown.millis, masked.millis)
        toasts.retext("another", "Other text")
        assertEquals(masked, toasts.current)
        // A later toast is never overwritten by the boarding one's retext.
        toasts.show("Copied")
        toasts.retext("boarding", "₿ 20,000 available in Ark")
        assertEquals("Copied", checkNotNull(toasts.current).text)
    }
}
