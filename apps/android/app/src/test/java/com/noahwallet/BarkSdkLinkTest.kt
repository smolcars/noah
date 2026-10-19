package com.noahwallet

import io.github.niteshbalusu11.bark.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class BarkSdkLinkTest {
    @Test
    fun releaseAarExposesTheSdk() {
        // Class linkage only: Android JNI cannot run in a host JVM unit test.
        assertEquals("io.github.niteshbalusu11.bark.Config", Config::class.java.name)
    }
}
