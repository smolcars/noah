package android.icu.text

/** JVM bridge for the device ICU API; use UTS #46 rather than JDK IDNA2003. */
class IDNA private constructor(options: Int) {
    private val delegate = com.ibm.icu.text.IDNA.getUTS46Instance(options)

    class Info {
        internal val delegate = com.ibm.icu.text.IDNA.Info()
        fun hasErrors() = delegate.hasErrors()
    }

    fun nameToUnicode(name: CharSequence, output: StringBuilder, info: Info): StringBuilder =
        delegate.nameToUnicode(name, output, info.delegate)

    companion object {
        const val NONTRANSITIONAL_TO_UNICODE = com.ibm.icu.text.IDNA.NONTRANSITIONAL_TO_UNICODE
        @JvmStatic fun getUTS46Instance(options: Int) = IDNA(options)
    }
}
