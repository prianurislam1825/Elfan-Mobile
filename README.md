# ELFAN Mobile v0.1

**Voice Controller untuk ELFAN Command Engine di Raspberry Pi**

---

## Arsitektur

```
ANDROID PHONE
    │
    │ Native AudioRecord (PCM 16-bit, 16 kHz, Mono)
    ▼
ELFAN MOBILE APK
    │
    │ HTTP POST /voice  (multipart/form-data, field: audio)
    ▼
RASPBERRY PI (Flask)
    │
    ▼
Whisper.cpp → CommandEngine → DeviceRouter
    │
    ├── Tuya  → BARDI LIGHT ON/OFF
    └── MQTT  → (future)
```

---

## 1. Cara Build APK

### Prasyarat

- Android Studio **Hedgehog** atau lebih baru
- **JDK 17**
- **Android SDK** (Android 13 / API 33 minimum)
- Gradle akan auto-download semua dependency

### Langkah Build

```bash
# Clone / buka folder project
cd ElfanMobile

# Build debug APK
./gradlew assembleDebug
```

> **Windows PowerShell:**
> ```powershell
> .\gradlew assembleDebug
> ```

### Output APK

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. Cara Install APK

### Via ADB

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Via Transfer Manual

1. Copy `app-debug.apk` ke HP (USB / Google Drive / WhatsApp)
2. Di HP → buka file manager
3. Tap `app-debug.apk`
4. Izinkan "Install from unknown sources" jika diminta
5. Tap **Install**

---

## 3. Menjalankan Backend di Raspberry Pi

Pastikan `voice_api.py` berjalan di Raspberry Pi:

```bash
# SSH ke Raspberry Pi
ssh pi@192.168.20.126

# Aktifkan virtual environment
cd ~/cv-engine
source venv/bin/activate

# Jalankan Flask API
python voice_api.py
```

Verifikasi berjalan:

```bash
# Di Raspberry Pi sendiri:
curl -X POST -F "audio=@/home/alwustho123/elfan-bardi.wav" http://127.0.0.1:5001/voice
```

Expected response:

```json
{
  "success": true,
  "text": "...",
  "command": { ... },
  "result": { ... }
}
```

---

## 4. Menentukan IP Raspberry Pi

Cek IP Raspberry Pi:

```bash
# Di terminal Raspberry Pi:
hostname -I

# Atau:
ip addr show | grep "inet "
```

Contoh output:
```
192.168.20.126
```

Pastikan HP dan Raspberry Pi berada di **jaringan WiFi yang sama**.

---

## 5. Mengisi Raspberry Pi URL di Aplikasi

1. Buka aplikasi **ELFAN**
2. Tap ikon ⚙️ (Settings) di pojok kanan atas
3. Di field **Raspberry Pi Address**, masukkan:

```
http://192.168.20.126:5001
```

> Ganti `192.168.20.126` dengan IP aktual Raspberry Pi Anda.

4. Tap tombol ✓ atau tekan Done di keyboard
5. Tap **TEST CONNECTION** untuk memverifikasi

---

## 6. Cara Testing Command

### Test: Nyalakan Lampu

1. Pastikan Raspberry Pi berjalan (`voice_api.py` aktif)
2. Pastikan HP dan Pi di WiFi yang sama
3. Buka ELFAN → tap **MULAI BICARA**
4. Ucapkan: *"Oke Elfan, nyalakan lampu BARDI"*
5. Tap **SELESAI**
6. Tunggu response dari Whisper → BARDI LIGHT menyala

### Test: Matikan Lampu

Ulangi langkah di atas dengan ucapan:
*"Oke Elfan, matikan lampu BARDI"*

### Hasil yang Diharapkan

```
Transkripsi : Oke elfen, nyalakan lampu berdi.
Device      : BARDI LIGHT
Action      : ON
Status      : ✓ Command berhasil
```

---

## 7. Troubleshooting Microphone

### Izin Ditolak

Jika muncul dialog "Izin Mikrofon Diperlukan":

1. Tap **BUKA PENGATURAN**
2. Tap **Izin** → **Mikrofon** → Pilih **Izinkan**
3. Kembali ke ELFAN

### Tidak Bisa Rekam

- Pastikan tidak ada aplikasi lain yang sedang menggunakan mikrofon
- Restart ELFAN
- Cek: **Pengaturan** → **Privasi** → **Mikrofon** → Pastikan ELFAN ada di daftar

---

## 8. Troubleshooting Network

### ❌ "Raspberry Pi tidak dapat dihubungi"

**Checklist:**
- [ ] HP dan Raspberry Pi di WiFi yang sama
- [ ] IP Raspberry Pi sudah benar di Settings
- [ ] `voice_api.py` sudah berjalan (`python voice_api.py`)
- [ ] Tidak ada firewall yang memblokir port 5001

**Test dari PC:**
```bash
curl http://192.168.20.126:5001/
```

### ⏱ "Server tidak merespons (timeout)"

- Whisper.cpp membutuhkan waktu untuk proses (normal 3–15 detik)
- Jika selalu timeout: cek RAM Raspberry Pi tidak penuh
- Coba rekam audio lebih pendek (< 5 detik)

### HTTP Error

- Cek log `voice_api.py` di Raspberry Pi untuk detail error
- Pastikan virtual environment aktif

---

## 9. Struktur Project

```
ElfanMobile/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/example/elfanmobile/
│       ├── MainActivity.kt          ← Entry point, permission handling
│       │
│       ├── audio/
│       │   └── WavRecorder.kt       ← Native AudioRecord → WAV PCM 16kHz
│       │
│       ├── network/
│       │   ├── ApiClient.kt         ← OkHttp singleton
│       │   ├── ApiModels.kt         ← Data classes (VoiceResponse, etc.)
│       │   └── ElfanApi.kt          ← POST /voice, GET / health check
│       │
│       ├── repository/
│       │   ├── VoiceRepository.kt   ← Mediates ViewModel ↔ API
│       │   └── SettingsRepository.kt← DataStore (URL, debug mode)
│       │
│       ├── viewmodel/
│       │   └── VoiceViewModel.kt    ← Recording state machine
│       │
│       └── ui/
│           ├── MainScreen.kt        ← Main voice command UI
│           ├── SettingsScreen.kt    ← URL config + debug toggle
│           └── PermissionRationaleDialog.kt
```

---

## 10. Format Audio

| Parameter   | Nilai      |
|-------------|------------|
| Format      | WAV        |
| Sample Rate | 16.000 Hz  |
| Channels    | Mono (1)   |
| Bit Depth   | 16-bit PCM |
| File name   | `elfan_record.wav` |

Format ini kompatibel dengan **Whisper.cpp** yang berjalan di Raspberry Pi.

---

## 11. Keamanan

- **TIDAK** ada credentials Tuya di aplikasi Android
- Android hanya menyimpan: **Raspberry Pi URL**
- Semua credential (Tuya, MQTT) tetap di Raspberry Pi
- Komunikasi menggunakan HTTP lokal (jaringan internal)

---

## 12. Roadmap (Future)

- [ ] Wake word "Oke Elfan" (fase berikutnya)
- [ ] Audio playback preview
- [ ] History command
- [ ] Multi-device support
- [ ] Always-listening mode (background service)
