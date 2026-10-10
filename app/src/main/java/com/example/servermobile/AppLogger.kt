package com.example.servermobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"
    private var logFile: File? = null

    private val _logsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val logsFlow: kotlinx.coroutines.flow.StateFlow<List<String>> = _logsFlow
    private const val MAX_UI_LOGS = 200

    fun init(context: Context) {
        try {
            // Salva no diretório externo privado do app.
            val dir = context.getExternalFilesDir(null)
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }
            logFile = File(dir, "app_logs.txt")
            if (logFile?.exists() == true && logFile!!.length() > 5 * 1024 * 1024) {
                // Se o log for maior que 5MB, exclui e cria de novo
                logFile?.delete()
            }
            logFile?.createNewFile()
            i("System", "=== App Logger Iniciado ===")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar o AppLogger", e)
        }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendLog("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendLog("INFO", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        appendLog("WARN", tag, message + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        appendLog("ERROR", tag, message + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    @Synchronized
    private fun appendLog(level: String, tag: String, message: String) {
        if (logFile == null) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "$timestamp $level/$tag: $message\n"
        
        // Atualiza a UI
        val currentLogs = _logsFlow.value.toMutableList()
        currentLogs.add(logLine)
        if (currentLogs.size > MAX_UI_LOGS) {
            currentLogs.removeAt(0)
        }
        _logsFlow.value = currentLogs

        try {
            FileOutputStream(logFile, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.append(logLine)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao escrever no arquivo de log", e)
        }
    }
}
