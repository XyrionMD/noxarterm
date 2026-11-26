// TinyAudio.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4

import com.fct.tc4.ui.misc.Global
import java.io.File

/**
 * TinyAudio – receives PCM audio from the Linux-side PipeWire module
 * over a Unix domain socket and plays it through AAudio.
 *
 * Uses NDK for all Unix-socket operations and AAudio playback.
 *
 * Usage:
 *   TinyAudio.start()   // connect & play (retries for 60s)
 *   TinyAudio.stop()    // stop & disconnect
 */
object TinyAudio {

    @Volatile
    private var running = false

    private var workerThread: Thread? = null

    /* ---------- native methods ---------- */
    @JvmStatic
    private external fun nativeStart(socketPath: String): Boolean

    @JvmStatic
    private external fun nativeStop()

    /* ---------- public API ---------- */

    /**
     * Start audio reception.
     *
     * Connects to [cacheDir]/tmp/.tiny.audio and begins receiving
     * PCM at 48000 Hz stereo via AAudio.
     *
     * Auto-reconnects while running so audio recovers if the container-side
     * audio server disconnects/restarts.
     */
    @Synchronized
    fun start() {
        if (running) return
        running = true

        val socketPath = File(Global.appContext.filesDir, "tmp/.tiny.audio").absolutePath

        workerThread = Thread({
            while (running) {
                val connected = try {
                    nativeStart(socketPath)
                } catch (_: Exception) {
                    false
                }
                // nativeStart blocks while connected and returns true on disconnect;
                // it returns false if the initial connection failed.
                if (running) {
                    try {
                        Thread.sleep(if (connected) 1000 else 2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "TinyAudio-worker").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stop audio reception. Closes the socket and AAudio stream.
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        nativeStop()
        workerThread?.interrupt()
        workerThread = null
    }

    /* ---------- JNI load ---------- */
    init {
        System.loadLibrary("tiny_audio_jni")
    }
}
