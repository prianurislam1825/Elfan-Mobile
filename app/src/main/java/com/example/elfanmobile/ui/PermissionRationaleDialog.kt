package com.example.elfanmobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Dialog shown when microphone permission is permanently denied.
 * Guides the user to open app Settings to grant the permission manually.
 */
@Composable
fun PermissionRationaleDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = null,
        title = {
            Text("🎙 Izin Mikrofon Diperlukan")
        },
        text = {
            Text(
                "ELFAN membutuhkan akses mikrofon untuk merekam perintah suara.\n\n" +
                "Buka Pengaturan aplikasi dan aktifkan izin Mikrofon."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("BUKA PENGATURAN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("BATAL")
            }
        }
    )
}
