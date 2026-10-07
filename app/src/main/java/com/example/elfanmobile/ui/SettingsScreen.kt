package com.example.elfanmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.elfanmobile.viewmodel.VoiceViewModel
import kotlinx.coroutines.launch

/**
 * SettingsScreen
 *
 * Allows the user to configure:
 *  - Raspberry Pi base URL
 *  - Debug mode toggle
 *  - Test Connection button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VoiceViewModel,
    onNavigateBack: () -> Unit
) {
    val currentUrl by viewModel.raspberryPiUrl.collectAsState(initial = "")
    val debugMode by viewModel.debugMode.collectAsState(initial = false)

    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var connectionTestMessage by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Pengaturan",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Raspberry Pi section ──────────────────────────────────────────
            SectionCard(title = "Raspberry Pi Server") {
                Text(
                    text = "Masukkan URL Raspberry Pi (tanpa trailing slash).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Raspberry Pi Address") },
                    placeholder = { Text("http://192.168.20.126:5001") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            viewModel.updateRaspberryPiUrl(urlInput)
                            scope.launch {
                                snackbarHostState.showSnackbar("URL disimpan: $urlInput")
                            }
                        }
                    ),
                    trailingIcon = {
                        if (urlInput != currentUrl) {
                            IconButton(onClick = {
                                focusManager.clearFocus()
                                viewModel.updateRaspberryPiUrl(urlInput)
                                scope.launch {
                                    snackbarHostState.showSnackbar("URL disimpan")
                                }
                            }) {
                                Icon(Icons.Default.Check, "Save")
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Test Connection button
                FilledTonalButton(
                    onClick = {
                        if (!isTesting) {
                            // Save first
                            viewModel.updateRaspberryPiUrl(urlInput)
                            isTesting = true
                            connectionTestMessage = "🔄 Menguji koneksi..."
                            viewModel.testConnection { result ->
                                connectionTestMessage = result
                                isTesting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTesting
                ) {
                    Text(
                        text = if (isTesting) "Menguji..." else "TEST CONNECTION",
                        letterSpacing = 1.sp
                    )
                }

                if (connectionTestMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = connectionTestMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            connectionTestMessage.startsWith("✓") -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            connectionTestMessage.startsWith("❌") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        }
                    )
                }
            }

            // ── Debug section ──────────────────────────────────────────────────
            SectionCard(title = "Developer") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Debug Mode",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Tampilkan informasi teknis",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = debugMode,
                        onCheckedChange = { viewModel.setDebugMode(it) }
                    )
                }
            }

            // ── About section ──────────────────────────────────────────────────
            SectionCard(title = "Tentang") {
                Text("ELFAN Mobile", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("Versi: 0.1", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Voice controller untuk ELFAN Command Engine.\nAudio direkam dengan AudioRecord (PCM 16-bit, 16 kHz, Mono) " +
                            "dan dikirim ke Whisper.cpp di Raspberry Pi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
