package com.example.elfanmobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.elfanmobile.theme.ELFANMobileTheme
import com.example.elfanmobile.ui.MainScreen
import com.example.elfanmobile.ui.PermissionRationaleDialog
import com.example.elfanmobile.ui.SettingsScreen
import com.example.elfanmobile.viewmodel.VoiceViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ELFANMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ElfanApp(activity = this)
                }
            }
        }
    }
}

/**
 * Root composable — handles navigation between MainScreen and SettingsScreen,
 * as well as microphone permission.
 */
@Composable
fun ElfanApp(activity: MainActivity) {
    val viewModel: VoiceViewModel = viewModel(
        factory = VoiceViewModel.Factory(activity.applicationContext)
    )

    var showSettings by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Microphone permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            showPermissionDialog = true
        }
    }

    if (showSettings) {
        SettingsScreen(
            viewModel = viewModel,
            onNavigateBack = { showSettings = false }
        )
    } else {
        MainScreen(
            viewModel = viewModel,
            onNavigateToSettings = { showSettings = true },
            onRequestMicPermission = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )
    }

    // Permission rationale dialog
    if (showPermissionDialog) {
        PermissionRationaleDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                showPermissionDialog = false
                // Open Android app settings so user can grant permission
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
                activity.startActivity(intent)
            }
        )
    }
}
