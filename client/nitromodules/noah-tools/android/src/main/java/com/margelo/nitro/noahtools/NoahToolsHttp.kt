package com.margelo.nitro.noahtools

import android.util.Log
import com.margelo.nitro.core.Promise
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object NoahToolsHttp {
    private const val TAG = "com.noah.app"

    // OkHttp client for background requests
    private val backgroundHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun performNativePost(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutSeconds: Double
    ): Promise<HttpResponse> {
        return Promise.async {
            try {
                Log.d(TAG, "Starting background POST request to: $url")

                // Create request body
                val mediaType = "application/json".toMediaType()
                val requestBody = body.toRequestBody(mediaType)

                // Build request with headers
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)

                // Add headers
                headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                val request = requestBuilder.build()

                // Create a client with custom timeout for this specific request
                val client = backgroundHttpClient.newBuilder()
                    .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .build()

                // Execute the request and properly close the response
                client.newCall(request).execute().use { response ->
                    // Extract response data
                    val responseBody = response.body?.string() ?: ""
                    val responseHeaders = mutableMapOf<String, String>()

                    response.headers.forEach { pair ->
                        responseHeaders[pair.first] = pair.second
                    }

                    Log.d(TAG, "Background request completed with status: ${response.code}")

                    return@async HttpResponse(
                        status = response.code.toDouble(),
                        body = responseBody,
                        headers = responseHeaders
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background request failed", e)
                throw Exception("Background request failed: ${e.message}", e)
            }
        }
    }

    fun performNativeGet(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Double
    ): Promise<HttpResponse> {
        return Promise.async {
            try {
                Log.d(TAG, "Starting background GET request to: $url")

                // Build request with headers
                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()

                // Add headers
                headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                val request = requestBuilder.build()

                // Create a client with custom timeout for this specific request
                val client = backgroundHttpClient.newBuilder()
                    .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .build()

                // Execute the request and properly close the response
                client.newCall(request).execute().use { response ->
                    // Extract response data
                    val responseBody = response.body?.string() ?: ""
                    val responseHeaders = mutableMapOf<String, String>()

                    response.headers.forEach { pair ->
                        responseHeaders[pair.first] = pair.second
                    }

                    Log.d(TAG, "Background request completed with status: ${response.code}")

                    return@async HttpResponse(
                        status = response.code.toDouble(),
                        body = responseBody,
                        headers = responseHeaders
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background request failed", e)
                throw Exception("Background request failed: ${e.message}", e)
            }
        }
    }

    fun performUploadFile(
        url: String,
        path: String,
        headers: Map<String, String>,
        timeoutSeconds: Double
    ): Promise<Unit> {
        return Promise.async {
            val file = File(path)
            if (!file.isFile) {
                throw IllegalArgumentException("Upload file does not exist")
            }
            val requestBuilder = Request.Builder()
                .url(url)
                .put(file.asRequestBody("application/octet-stream".toMediaType()))
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            val client = backgroundHttpClient.newBuilder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Backup upload failed with status ${response.code}")
                }
            }
        }
    }

    fun performDownloadFile(
        url: String,
        path: String,
        headers: Map<String, String>,
        timeoutSeconds: Double
    ): Promise<Unit> {
        return Promise.async {
            val destination = File(path)
            if (destination.exists()) {
                throw IllegalArgumentException("Download destination already exists")
            }
            val requestBuilder = Request.Builder().url(url).get()
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            val client = backgroundHttpClient.newBuilder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Backup download failed with status ${response.code}")
                    }
                    val body = response.body
                        ?: throw Exception("Backup download returned an empty response")
                    FileOutputStream(destination).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }
    }
}
