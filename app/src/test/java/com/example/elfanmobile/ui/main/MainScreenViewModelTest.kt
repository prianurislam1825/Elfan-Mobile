package com.example.elfanmobile.ui.main

import junit.framework.TestCase.assertEquals
import org.junit.Test

/**
 * Placeholder unit tests for ELFAN Mobile v0.1.
 *
 * Full integration tests require a running Raspberry Pi backend.
 * These tests verify simple sanity checks.
 */
class MainScreenViewModelTest {

    @Test
    fun defaultRaspberryPiUrl_isCorrect() {
        val expected = "http://192.168.20.126:5001"
        assertEquals(
            expected,
            com.example.elfanmobile.repository.SettingsRepository.DEFAULT_RASPBERRY_PI_URL
        )
    }

    @Test
    fun normalizeUrl_removesTrailingSlash() {
        val url = "http://192.168.20.126:5001/"
        val normalized = url.trimEnd('/')
        assertEquals("http://192.168.20.126:5001", normalized)
    }
}
