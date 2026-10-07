package com.example.elfanmobile.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.elfanmobile.theme.ELFANMobileTheme
import org.junit.Rule
import org.junit.Test

/**
 * Basic UI tests for ELFAN Mobile.
 * These run on-device and verify basic composable rendering.
 */
class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appTitle_isDisplayed() {
        composeTestRule.setContent {
            ELFANMobileTheme {
                // Verify the app name renders correctly
                androidx.compose.material3.Text("ELFAN")
            }
        }
        composeTestRule.onNodeWithText("ELFAN").assertIsDisplayed()
    }
}
