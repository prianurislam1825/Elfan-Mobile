package com.example.elfanmobile.repository

import android.util.Log
import com.example.elfanmobile.network.ElfanApi
import com.example.elfanmobile.network.ElfanResult
import com.example.elfanmobile.network.VoiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "VoiceRepository"

/**
 * VoiceRepository
 *
 * Mediates between the ViewModel and the ElfanApi.
 * Converts raw [VoiceResponse] into a UI-ready [ElfanResult].
 */
class VoiceRepository(private val api: ElfanApi = ElfanApi()) {

    /**
     * Upload a WAV file to the ELFAN voice endpoint.
     *
     * @param baseUrl Raspberry Pi base URL
     * @param wavFile WAV file to send
     * @return [Result] wrapping [ElfanResult] on success or an exception on failure
     */
    suspend fun sendVoiceCommand(baseUrl: String, wavFile: File): Result<ElfanResult> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending voice command to $baseUrl/voice with file: ${wavFile.name}")
                val response = api.postVoice(baseUrl, wavFile)
                Log.d(TAG, "Response: $response")
                Result.success(mapToElfanResult(response))
            } catch (e: IOException) {
                Log.e(TAG, "Network error: ${e.message}", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Test connection to the Raspberry Pi server.
     *
     * @param baseUrl Raspberry Pi base URL
     * @return [ElfanApi.ConnectionTestResult]
     */
    suspend fun testConnection(baseUrl: String): ElfanApi.ConnectionTestResult =
        withContext(Dispatchers.IO) {
            api.testConnection(baseUrl)
        }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun mapToElfanResult(response: VoiceResponse): ElfanResult {
        val transcription = response.text
            ?: response.command?.text
            ?: "(no transcription)"

        val device = response.command?.device
            ?.replace("_", " ")
            ?.uppercase()
            ?: "-"

        val action = response.command?.action?.uppercase() ?: "-"
        val protocol = response.result?.protocol?.uppercase() ?: "-"
        val success = response.success && (response.command?.success == true)

        return ElfanResult(
            transcription = transcription,
            device = device,
            action = action,
            protocol = protocol,
            success = success,
            rawResponse = response
        )
    }
}
