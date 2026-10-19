package com.noahwallet.services.server

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** AppServices owns one provider. Callers join a login even if the initiating UI is cancelled. */
class AuthTokenProvider(
    private val readToken: suspend () -> String?,
    private val saveToken: suspend (String) -> Unit,
    private val clearToken: suspend () -> Unit,
    private val readMnemonic: suspend () -> String,
    private val login: suspend (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cached: String? = null
    private var inFlight: Deferred<String>? = null

    suspend fun token(forceRefresh: Boolean = false, rejectedToken: String? = null): String {
        val attempt = mutex.withLock {
            inFlight?.let { return@withLock it }
            val current = cached ?: readToken()
            val fresh = current != null && runCatching { !JWT.needsRefresh(current, now()) }.getOrDefault(false)
            // A delayed 401 for an older token must not invalidate a completed refresh.
            if (fresh && (!forceRefresh || (rejectedToken != null && current != rejectedToken))) {
                cached = current
                return@withLock CompletableDeferred(current)
            }
            cached = null
            clearToken()
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = login(readMnemonic())
                    saveToken(result)
                    mutex.withLock { cached = result }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw ServerFailure("Server authentication failed")
                } finally {
                    mutex.withLock { inFlight = null }
                }
            }.also { inFlight = it; it.start() }
        }
        return attempt.await()
    }
}
