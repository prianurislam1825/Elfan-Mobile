package com.example.elfanmobile.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttpClient builder.
 *
 * Timeouts are generous because:
 *  - The Raspberry Pi may take a few seconds to run Whisper.cpp
 *  - Network latency on a local WiFi is low but Whisper can be slow
 */
object ApiClient {
    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC    = 60L  // Whisper can be slow
    private const val WRITE_TIMEOUT_SEC   = 30L

    val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }
}
