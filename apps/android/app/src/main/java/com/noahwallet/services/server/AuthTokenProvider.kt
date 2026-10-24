package com.noahwallet.services.server

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AppServices owns one provider. Callers join a login even if the initiating UI is cancelled.
 * A stored token is only reused if it was issued to the current wallet (its `sub` is the login
 * key of the stored seed), so a new, restored or replaced wallet never acts as the old one.
 */
class AuthTokenProvider(
    private val readToken: suspend () -> String?,
    private val saveToken: suspend (String) -> Unit,
    private val clearToken: suspend () -> Unit,
    private val readMnemonic: suspend () -> String,
    private val login: suspend (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Login public key for a mnemonic (the token's `sub`); null skips the check (tests). */
    private val loginKey: (String) -> String? = { null },
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cached: String? = null
    private var inFlight: Deferred<String>? = null
    private var loginKeyCache: Pair<String, String?>? = null

    suspend fun token(forceRefresh: Boolean = false, rejectedToken: String? = null): String {
        val attempt = mutex.withLock {
            inFlight?.let { return@withLock it }
            val mnemonic = readMnemonic()
            val expectedSubject = loginKeyFor(mnemonic)
            val current = cached ?: readToken()
            val ours = current != null && (expectedSubject == null || JWT.subject(current) == expectedSubject)
            val fresh = ours && runCatching { !JWT.needsRefresh(current!!, now()) }.getOrDefault(false)
            // A delayed 401 for an older token must not invalidate a completed refresh.
            if (fresh && (!forceRefresh || (rejectedToken != null && current != rejectedToken))) {
                cached = current
                return@withLock CompletableDeferred(current)
            }
            cached = null
            clearToken()
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = login(mnemonic)
                    saveToken(result)
                    mutex.withLock { cached = result }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val offline = failure is java.io.IOException ||
                        (failure is ServerFailure && failure.kind == ServerFailure.Kind.connectivity)
                    throw ServerFailure("""Server authentication failed""", kind =
                        if (offline) ServerFailure.Kind.connectivity else ServerFailure.Kind.request)
                } finally {
                    mutex.withLock { inFlight = null }
                }
            }.also { inFlight = it; it.start() }
        }
        return attempt.await()
    }

    suspend fun clear() {
        // Deletion has already gated opens and removed the seed. Drain an older login before erase.
        mutex.withLock { inFlight }?.let { runCatching { it.await() } }
        mutex.withLock { cached = null; loginKeyCache = null; clearToken() }
    }

    /** Derived once per seed (by digest, so the phrase itself isn't kept as a key). */
    private fun loginKeyFor(mnemonic: String): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(mnemonic.toByteArray())
            .joinToString("") { "%02x".format(it) }
        loginKeyCache?.let { (cachedDigest, key) -> if (cachedDigest == digest) return key }
        return loginKey(mnemonic).also { loginKeyCache = digest to it }
    }
}
