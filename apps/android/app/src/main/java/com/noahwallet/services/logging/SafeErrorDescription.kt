package com.noahwallet.services.logging

import io.github.niteshbalusu11.bark.BarkError

/** Matches iOS: SDK text goes through redaction; other providers expose only their error type. */
internal object SafeErrorDescription {
    fun of(error: Throwable): String = if (error is BarkError) LogFile.redact(error.message) else LogFile.redact(error.javaClass.simpleName)
}
