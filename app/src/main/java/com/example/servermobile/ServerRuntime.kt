package com.example.servermobile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object ServerRuntime {
    private var manager: ServerManager? = null
    private var webRTCManager: WebRTCP2PManager? = null

    val isLocalServerRunning = MutableStateFlow(false)
    val isGlobalServerRunning = MutableStateFlow(false)

    @Synchronized
    fun serverManager(context: Context): ServerManager {
        return manager ?: ServerManager(context.applicationContext).also { manager = it }
    }

    @Synchronized
    fun currentWebRTCManager(): WebRTCP2PManager? = webRTCManager

    @Synchronized
    fun startWebRTCServer(
        context: Context,
        serverManager: ServerManager,
        serverKey: String
    ): WebRTCP2PManager {
        webRTCManager?.close()
        return WebRTCP2PManager(context.applicationContext, serverManager, serverKey).also {
            webRTCManager = it
        }
    }

    @Synchronized
    fun stopWebRTCServer() {
        webRTCManager?.close()
        webRTCManager = null
        isGlobalServerRunning.value = false
    }

    fun serverKey(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            "server_mobile_runtime",
            Context.MODE_PRIVATE
        )
        return preferences.getString("server_key", null) ?: generateRandomKey().also {
            preferences.edit().putString("server_key", it).apply()
        }
    }

    fun regenerateServerKey(context: Context): String =
        generateRandomKey().also {
            context.applicationContext.getSharedPreferences(
                "server_mobile_runtime",
                Context.MODE_PRIVATE
            ).edit().putString("server_key", it).apply()
        }

    private fun generateRandomKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
