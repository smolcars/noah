package com.noahwallet.ui.settings

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException

internal class LogSnapshot {
    var lines by mutableStateOf<List<String>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var updatedAt by mutableStateOf<Long?>(null); private set

    suspend fun load(read: suspend () -> List<String>, now: () -> Long = System::currentTimeMillis) {
        loading = true; error = null
        try { lines = read(); updatedAt = now() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Failed to fetch logs." }
        finally { loading = false }
    }
}
