package com.noahwallet.services.backup

import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Blocking network IO belongs to a child job: cancellation cancels the call, then joins file writes. */
object BoundedDownload {
    class TooLarge : Exception("""Backup download exceeds limit""")
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun fetch(request: Request, destination: File, limit: Long, http: OkHttpClient = client) {
        require(limit >= 0)
        val call = http.newCall(request)
        run(call) { response ->
            check(response.isSuccessful) { "Backup download failed: ${response.code}" }
            val body = response.body
            if (body.contentLength() > limit) throw TooLarge()
            check(destination.createNewFile()) { "Download destination exists" }
            destination.outputStream().use { output ->
                body.byteStream().use { input -> copyBounded(input, output, limit) }
            }
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        var received = 0L
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > limit - received) throw TooLarge()
            received += count
            output.write(buffer, 0, count)
        }
    }

    internal suspend fun <T> run(call: Call, body: (Response) -> T): T = coroutineScope {
        val job = async(Dispatchers.IO) { call.execute().use(body) }
        try { job.await() }
        finally { call.cancel(); withContext(NonCancellable) { job.join() } }
    }
}
