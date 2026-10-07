package com.example.elfanmobile.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level response from POST /voice
 */
@Serializable
data class VoiceResponse(
    val success: Boolean = false,
    val text: String? = null,
    val command: CommandResult? = null,
    val result: DeviceResult? = null,
    val error: String? = null
)

/**
 * Command interpretation result from CommandEngine
 */
@Serializable
data class CommandResult(
    val success: Boolean = false,
    val device: String? = null,
    val action: String? = null,
    val text: String? = null
)

/**
 * Device execution result from DeviceRouter
 */
@Serializable
data class DeviceResult(
    val success: Boolean = false,
    val protocol: String? = null,
    val device: String? = null,
    val code: String? = null,
    val state: Boolean? = null
)

/**
 * Simplified UI-facing result after parsing VoiceResponse
 */
data class ElfanResult(
    val transcription: String,
    val device: String,
    val action: String,
    val protocol: String,
    val success: Boolean,
    val rawResponse: VoiceResponse
)
