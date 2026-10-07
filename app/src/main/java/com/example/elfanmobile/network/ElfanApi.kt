package com.example.elfanmobile.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * ElfanApi
 *
 * Handles HTTP communication with the ELFAN Raspberry Pi backend.
 *
 * Endpoints:
 *   POST /voice   — upload WAV file, get command result
 *   GET  /        — health check (best-effort)
 */
class ElfanApi {

    private val client = ApiClient.okHttpClient

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Upload a WAV file to the /voice endpoint.
     *
     * @param baseUrl e.g. "http://192.168.20.126:5001"
     * @param wavFile the WAV file to send
     * @return [VoiceResponse] on success
     * @throws IOException on network errors
     * @throws Exception on HTTP errors or parse failures
     */
    @Throws(IOException::class, Exception::class)
    fun postVoice(baseUrl: String, wavFile: File): VoiceResponse {
        val url = normalizeBaseUrl(baseUrl) + "/voice"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "audio",
                filename = wavFile.name,
                body = wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: $responseBody")
        }

        return json.decodeFromString<VoiceResponse>(responseBody)
    }

    /**
     * Best-effort health check. Tries GET /.
     * Returns true if server responds (any HTTP code), false if unreachable.
     */
    fun testConnection(baseUrl: String): ConnectionTestResult {
        return try {
            val url = normalizeBaseUrl(baseUrl) + "/"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.close()
            ConnectionTestResult.Success(response.code)
        } catch (e: IOException) {
            ConnectionTestResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** Ensure base URL has no trailing slash */
    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trimEnd('/')
    }

    sealed class ConnectionTestResult {
        data class Success(val httpCode: Int) : ConnectionTestResult()
        data class Failure(val error: String) : ConnectionTestResult()
    }
}
