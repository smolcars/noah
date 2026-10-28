package com.noahwallet.ui.onboarding

import com.noahwallet.services.server.ServerFailure
import java.util.Locale

/** Lightning names: lowercase `[a-z0-9_.-]`, as the server accepts. */
internal object NameRules {
    const val minLength = 3
    const val maxLength = 24

    fun normalize(input: String): String =
        input.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it in "_.-" }.take(maxLength)

    fun suggestions(name: String): List<String> = listOf("${name}21", "$name.btc", "its$name").filter { it.length <= maxLength }

    /** `update_lightning_identity` is the authority; its taken answer has no code, only this message. */
    fun takenByServer(failure: Exception) = failure is ServerFailure && failure.kind == ServerFailure.Kind.request &&
        failure.serverMessage.orEmpty().contains("already taken", ignoreCase = true)
}

/** The public LNURL lookup is only a hint: 200 means taken, any other answer means free, no answer is unknown. */
internal enum class NameLookup { found, notFound, failed }

internal enum class NameAvailability {
    empty, tooShort, checking, available, taken, unknown;

    val canContinue get() = this == available || this == unknown

    companion object {
        /** State while typing, before the debounced lookup answers. */
        fun typing(name: String): NameAvailability = when {
            name.isEmpty() -> empty
            name.length < NameRules.minLength -> tooShort
            else -> checking
        }

        /** [own] is the name the server already assigned to this wallet, which the lookup also finds. */
        fun answered(name: String, lookup: NameLookup, own: String?): NameAvailability = when {
            typing(name) != checking -> typing(name)
            name == own -> available
            lookup == NameLookup.found -> taken
            lookup == NameLookup.notFound -> available
            else -> unknown
        }
    }
}

/** Email entry: validity, domain completion and common typo fixes, as in the prototype. */
internal object EmailRules {
    val domains = listOf("gmail.com", "icloud.com", "outlook.com", "proton.me", "yahoo.com", "hotmail.com")
    val typos = mapOf("gmial.com" to "gmail.com", "gmal.com" to "gmail.com", "gamil.com" to "gmail.com", "gmail.co" to "gmail.com",
        "gnail.com" to "gmail.com", "iclod.com" to "icloud.com", "icloud.co" to "icloud.com", "hotmial.com" to "hotmail.com",
        "outlok.com" to "outlook.com", "yaho.com" to "yahoo.com")
    private val pattern = Regex("^[^\\s@]+@[^\\s@]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE)

    fun normalize(input: String) = input.filterNot(Char::isWhitespace).lowercase(Locale.ROOT)

    fun isValid(email: String) = pattern.matches(email) && typos[email.substringAfter('@')] == null

    sealed interface Hint {
        data class Typo(val suggestion: String) : Hint
        data class Completions(val emails: List<String>) : Hint
    }

    fun hint(email: String): Hint? {
        val at = email.indexOf('@')
        if (at <= 0) return null
        val user = email.substring(0, at)
        val domain = email.substring(at + 1)
        typos[domain]?.let { return Hint.Typo("$user@$it") }
        if (pattern.matches(email)) return null
        val completions = domains.filter { it.startsWith(domain) && it != domain }.take(3).map { "$user@$it" }
        return if (completions.isEmpty()) null else Hint.Completions(completions)
    }
}

/** The six-digit code: fill, verify once at six digits, wrong code, verified. */
internal data class OtpEntry(val code: String = "", val verifying: Boolean = false, val failed: Boolean = false, val verified: Boolean = false) {
    val complete get() = code.length == length
    /** The box with the orange caret; none while verifying or once verified. */
    val activeIndex get() = if (verifying || verified) -1 else code.length.coerceAtMost(length - 1)

    /** Typing during verification is ignored; a new digit clears a failed attempt. */
    fun typed(input: String): OtpEntry {
        if (verifying || verified) return this
        val digits = input.filter { it in '0'..'9' }.take(length)
        return copy(code = digits, failed = if (digits != code) false else failed)
    }
    fun verifyStarted() = copy(verifying = true, failed = false)

    /**
     * Typing while a request runs (sending, verifying, or the pause after a wrong code) is ignored, so a code
     * only starts verifying when a request really starts. Returns the new entry and whether to verify it now.
     */
    fun input(text: String, busy: Boolean): Pair<OtpEntry, Boolean> {
        if (busy) return this to false
        val next = typed(text)
        return if (next != this && next.complete) next.verifyStarted() to true else next to false
    }
    fun rejected() = copy(verifying = false, failed = true)
    /** After the shake, the boxes clear for another try. */
    fun cleared() = copy(code = "")
    fun accepted() = copy(verifying = false, verified = true, failed = false)

    companion object { const val length = 6 }
}

/** "Resend code in 0:30", then "Didn’t get it? Resend". */
internal object ResendTimer {
    const val seconds = 30
    fun remaining(sentAtMillis: Long, nowMillis: Long): Int =
        (seconds - ((nowMillis - sentAtMillis).coerceAtLeast(0) / 1000).toInt()).coerceIn(0, seconds)
    fun clock(remaining: Int) = "0:%02d".format(Locale.ROOT, remaining)
}

/** The restored balance while it counts up: exact at the end, with no Float rounding of large amounts. */
internal fun countUpSats(total: Long, progress: Float): Long =
    if (progress >= 1f) total else kotlin.math.round(total.toDouble() * progress.coerceAtLeast(0f)).toLong().coerceIn(0L, total)

/** A wrong or expired code gets the shake; connectivity and server failures keep today's alert. */
internal fun wrongCode(failure: Exception) = failure is ServerFailure && failure.kind == ServerFailure.Kind.request &&
    (failure.status == null || (failure.status in 400..499 && failure.status != 429))
