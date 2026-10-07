package com.example.elfanmobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.elfanmobile.viewmodel.VoiceViewModel

/**
 * MainScreen — the primary voice command UI.
 *
 * Layout:
 *  • Header (ELFAN + status indicator)
 *  • Record button (dynamic: Mulai Bicara / Mendengarkan / Mengirim)
 *  • Result card (Transkripsi / Device / Action)
 *  • Debug card (when debug mode enabled)
 *  • Raspberry Pi address label
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: VoiceViewModel,
    onNavigateToSettings: () -> Unit,
    onRequestMicPermission: () -> Unit
) {
    val state by viewModel.recordingState.collectAsState()
    val duration by viewModel.recordingDuration.collectAsState()
    val baseUrl by viewModel.raspberryPiUrl.collectAsState(initial = "")
    val debugMode by viewModel.debugMode.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ELFAN",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Voice Command",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Status indicator ─────────────────────────────────────────────
            StatusIndicator(state = state)

            Spacer(modifier = Modifier.height(32.dp))

            // ── Record button ────────────────────────────────────────────────
            RecordButton(
                state = state,
                onStartRecording = {
                    if (viewModel.hasMicrophonePermission()) {
                        viewModel.startRecording()
                    } else {
                        onRequestMicPermission()
                    }
                },
                onStopRecording = { viewModel.stopRecording() },
                onReset = { viewModel.reset() }
            )

            // ── Duration label ───────────────────────────────────────────────
            if (state is VoiceViewModel.RecordingState.Recording ||
                (state is VoiceViewModel.RecordingState.Uploading && duration > 0f)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "⏱ Durasi rekaman: ${"%.1f".format(duration)} detik",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Result card ──────────────────────────────────────────────────
            ResultCard(state = state)

            Spacer(modifier = Modifier.height(16.dp))

            // ── Debug card ───────────────────────────────────────────────────
            if (debugMode) {
                DebugCard(state = state, duration = duration)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Raspberry Pi address ─────────────────────────────────────────
            Text(
                text = "Raspberry Pi\n${baseUrl.removePrefix("http://").removePrefix("https://")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status Indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusIndicator(state: VoiceViewModel.RecordingState) {
    val (dotColor, label) = when (state) {
        is VoiceViewModel.RecordingState.Idle -> Pair(Color(0xFF4CAF50), "● SIAP")
        is VoiceViewModel.RecordingState.Recording -> Pair(Color(0xFFF44336), "🎙 MENDENGARKAN...")
        is VoiceViewModel.RecordingState.Uploading -> Pair(Color(0xFFFF9800), "⏳ MENGIRIM KE ELFAN...")
        is VoiceViewModel.RecordingState.Success -> Pair(Color(0xFF4CAF50), "✓ SELESAI")
        is VoiceViewModel.RecordingState.Error -> Pair(Color(0xFFF44336), "❌ ERROR")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state is VoiceViewModel.RecordingState.Recording) 1.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_scale"
    )

    val animatedColor by animateColorAsState(
        targetValue = dotColor,
        animationSpec = tween(300),
        label = "status_color"
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .scale(scale)
            .background(animatedColor, CircleShape)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = animatedColor,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Record Button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecordButton(
    state: VoiceViewModel.RecordingState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onReset: () -> Unit
) {
    when (state) {
        is VoiceViewModel.RecordingState.Idle,
        is VoiceViewModel.RecordingState.Success,
        is VoiceViewModel.RecordingState.Error -> {
            Button(
                onClick = {
                    if (state is VoiceViewModel.RecordingState.Success ||
                        state is VoiceViewModel.RecordingState.Error
                    ) {
                        onReset()
                    } else {
                        onStartRecording()
                    }
                },
                modifier = Modifier
                    .size(180.dp)
                    .border(
                        3.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        CircleShape
                    ),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state is VoiceViewModel.RecordingState.Success ||
                            state is VoiceViewModel.RecordingState.Error
                        ) "COBA LAGI" else "MULAI\nBICARA",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 14.sp
                    )
                }
            }
        }

        is VoiceViewModel.RecordingState.Recording -> {
            Button(
                onClick = onStopRecording,
                modifier = Modifier
                    .size(180.dp)
                    .border(3.dp, Color(0xFFF44336).copy(alpha = 0.4f), CircleShape),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFEBEE)
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color(0xFFF44336)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SELESAI",
                        textAlign = TextAlign.Center,
                        color = Color(0xFFF44336),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 14.sp
                    )
                }
            }
        }

        is VoiceViewModel.RecordingState.Uploading -> {
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 6.dp
                )
                Text(
                    text = "Mengirim...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(state: VoiceViewModel.RecordingState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            when (state) {
                is VoiceViewModel.RecordingState.Idle -> {
                    ResultRow(label = "Status", value = "Siap menerima perintah")
                    ResultRow(label = "Transkripsi", value = "-")
                    ResultRow(label = "Device", value = "-")
                    ResultRow(label = "Action", value = "-")
                }

                is VoiceViewModel.RecordingState.Recording -> {
                    ResultRow(label = "Status", value = "🎙 Mendengarkan...")
                    ResultRow(label = "Transkripsi", value = "...")
                    ResultRow(label = "Device", value = "-")
                    ResultRow(label = "Action", value = "-")
                }

                is VoiceViewModel.RecordingState.Uploading -> {
                    ResultRow(label = "Status", value = "⏳ Mengirim ke ELFAN...")
                    ResultRow(label = "Transkripsi", value = "Menunggu Whisper...")
                    ResultRow(label = "Device", value = "-")
                    ResultRow(label = "Action", value = "-")
                }

                is VoiceViewModel.RecordingState.Success -> {
                    val r = state.result
                    val statusText = if (r.success) "✓ Command berhasil" else "⚠ Command gagal"
                    ResultRow(label = "Status", value = statusText, highlight = r.success)
                    ResultRow(label = "Transkripsi", value = r.transcription)
                    ResultRow(label = "Device", value = r.device)
                    ResultRow(label = "Action", value = r.action)
                }

                is VoiceViewModel.RecordingState.Error -> {
                    ResultRow(label = "Status", value = state.message, isError = true)
                    ResultRow(label = "Transkripsi", value = "-")
                    ResultRow(label = "Device", value = "-")
                    ResultRow(label = "Action", value = "-")
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    isError: Boolean = false
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight || isError) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                highlight -> Color(0xFF4CAF50)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Debug Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DebugCard(state: VoiceViewModel.RecordingState, duration: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DEBUG",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            val debugText = buildString {
                append("Audio: elfan_record.wav\n")
                append("Duration: ${"%.1f".format(duration)} sec\n")
                when (state) {
                    is VoiceViewModel.RecordingState.Success -> {
                        val r = state.result
                        append("Upload: SUCCESS\n")
                        append("HTTP: 200\n")
                        append("STT: ${r.transcription}\n")
                        append("Device: ${r.rawResponse.command?.device ?: "-"}\n")
                        append("Action: ${r.action}\n")
                        append("Protocol: ${r.protocol}\n")
                        append("Result: ${if (r.success) "SUCCESS" else "FAILED"}")
                    }
                    is VoiceViewModel.RecordingState.Error -> {
                        append("Upload: FAILED\n")
                        append("Error: ${state.message}")
                    }
                    is VoiceViewModel.RecordingState.Uploading -> {
                        append("Upload: IN PROGRESS...")
                    }
                    is VoiceViewModel.RecordingState.Recording -> {
                        append("Recording: IN PROGRESS...")
                    }
                    else -> append("Status: IDLE")
                }
            }

            Text(
                text = debugText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                lineHeight = 18.sp
            )
        }
    }
}
