package com.fct.tc4.service

import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

object ShizukuShell {

    private val executor = Executors.newSingleThreadExecutor()

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Throwable) {
        }
    }

    fun exec(command: String, callback: (exitCode: Int, output: String) -> Unit) {
        executor.execute {
            try {
                val process = newProcess(arrayOf("sh", "-c", command))
                val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
                val code = process.waitFor()
                val out = buildString {
                    if (stdout.isNotEmpty()) append(stdout)
                    if (stderr.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(stderr)
                    }
                }
                callback(code, out)
            } catch (e: Throwable) {
                callback(-1, e.message ?: "Shizuku exec failed")
            }
        }
    }

    @Throws(Exception::class)
    private fun newProcess(cmd: Array<String>): Process {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m.invoke(null, cmd, null, null) as Process
        } catch (_: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ProcessBuilder(*cmd).redirectErrorStream(false).start()
            } else {
                Runtime.getRuntime().exec(cmd)
            }
        }
    }
}
