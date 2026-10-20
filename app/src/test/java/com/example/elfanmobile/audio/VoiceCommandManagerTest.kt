package com.example.elfanmobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests untuk State Machine asisten dan spesifikasi konstanta OnnxWakeWordDetector.
 */
class VoiceCommandManagerTest {

    @Test
    fun assistantState_transitionsCorrectly() {
        // 1. Initial state should be STANDBY
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.STANDBY)
        assertEquals(VoiceCommandManager.AssistantState.STANDBY, VoiceCommandManager.state.value)

        // 2. Wake word detected: "Oke Elfan"
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.WAKE_DETECTED)
        assertEquals(VoiceCommandManager.AssistantState.WAKE_DETECTED, VoiceCommandManager.state.value)

        // 3. Speaking prompt: "Ada yang bisa saya bantu?"
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.SPEAKING_PROMPT)
        assertEquals(VoiceCommandManager.AssistantState.SPEAKING_PROMPT, VoiceCommandManager.state.value)

        // 4. Listening command: User speaks command (AudioRecord + VAD)
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.LISTENING_COMMAND)
        assertEquals(VoiceCommandManager.AssistantState.LISTENING_COMMAND, VoiceCommandManager.state.value)

        // 5. Processing command: HTTP POST to Raspberry Pi backend
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.PROCESSING_COMMAND)
        assertEquals(VoiceCommandManager.AssistantState.PROCESSING_COMMAND, VoiceCommandManager.state.value)

        // 6. Speaking response: TTS speaks feedback result
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.SPEAKING_RESPONSE)
        assertEquals(VoiceCommandManager.AssistantState.SPEAKING_RESPONSE, VoiceCommandManager.state.value)

        // 7. Recovering: Resource cleanup & cooldown before re-enabling detector
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.RECOVERING)
        assertEquals(VoiceCommandManager.AssistantState.RECOVERING, VoiceCommandManager.state.value)

        // Automatically back to STANDBY
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.STANDBY)
        assertEquals(VoiceCommandManager.AssistantState.STANDBY, VoiceCommandManager.state.value)
    }

    @Test
    fun onnxWakeWordDetector_constantsMatchOpenWakeWordSpec() {
        // Sesuai openWakeWord: 1280 sampel per 80ms chunk pada 16 kHz
        assertEquals(1280, OnnxWakeWordDetector.CHUNK_SAMPLES)
        assertEquals(16000, OnnxWakeWordDetector.SAMPLE_RATE)

        // Preprocessing mel-spectrogram: 5 frames per 1280 chunk, 32 bins
        assertEquals(5, OnnxWakeWordDetector.MEL_FRAMES_PER_CHUNK)
        assertEquals(32, OnnxWakeWordDetector.MEL_FREQ_BINS)

        // Embedding: window 76 frames, step 8 frames, output 96-dim
        assertEquals(76, OnnxWakeWordDetector.MEL_WINDOW_SIZE)
        assertEquals(8, OnnxWakeWordDetector.MEL_STEP_SIZE)
        assertEquals(96, OnnxWakeWordDetector.EMBEDDING_DIM)

        // Classifier input: 16 temporal embeddings (16 * 96 = 1536 input size)
        assertEquals(16, OnnxWakeWordDetector.EMBEDDING_WINDOW_SIZE)

        // Anti-false-trigger parameters:
        // Energy gate RMS >= 250
        assertEquals(250, OnnxWakeWordDetector.MIN_CHUNK_RMS)
        // Multi-frame patience >= 2
        assertEquals(2, OnnxWakeWordDetector.REQUIRED_CONSECUTIVE_HITS)
        // Calibrated threshold = 0.85f
        assertEquals(0.85f, OnnxWakeWordDetector.DEFAULT_THRESHOLD, 0.001f)
    }
}
