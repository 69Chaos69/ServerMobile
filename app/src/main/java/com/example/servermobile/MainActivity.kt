package com.example.servermobile

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import com.example.servermobile.AppLogger as Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.servermobile.ui.components.MainScreen
import com.example.servermobile.ui.theme.ServerAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainActivity : ComponentActivity() {

    private val serverManager by lazy { ServerRuntime.serverManager(applicationContext) }
    private var webRTCManagerState = mutableStateOf(ServerRuntime.currentWebRTCManager())
    
    // UI State
    private var isLocalServerRunningState = mutableStateOf(ServerRuntime.isLocalServerRunning.value)
    private var isLocalServerTransitioningState = mutableStateOf(false)
    private var isGlobalServerRunningState = mutableStateOf(ServerRuntime.isGlobalServerRunning.value)
    private var ipAddressState = mutableStateOf("--")
    private var serverKeyState = mutableStateOf("")

    private enum class PendingStartAction { LOCAL, GLOBAL }
    private var pendingStartAction: PendingStartAction? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Sem permissão de notificações; o servidor ainda será iniciado.",
                Toast.LENGTH_LONG
            ).show()
        }
        runPendingStartAction()
    }

    private val sharedFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingStartAction = null
        } else {
            lifecycleScope.launch {
                val selected = withContext(Dispatchers.IO) {
                    serverManager.useFolderAsShared(uri)
                }
                if (selected) {
                    requestNotificationPermissionOrStart()
                } else {
                    pendingStartAction = null
                    Toast.makeText(
                        this@MainActivity,
                        "Selecione uma pasta com permissões de leitura e gravação para iniciar o servidor.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.init(this)
        pendingStartAction = savedInstanceState?.getString(STATE_PENDING_START)
            ?.let { runCatching { PendingStartAction.valueOf(it) }.getOrNull() }
        serverKeyState.value = ServerRuntime.serverKey(this)
        if (isLocalServerRunningState.value || isGlobalServerRunningState.value) {
            ipAddressState.value = getLocalIpAddress()
        }
        setContent {
            ServerAppTheme {
                val activeConnections by serverManager.activeConnections.collectAsState()
                val serverStartTime by serverManager.serverStartTime.collectAsState()
                val logs by serverManager.logs.collectAsState()
                val devices by serverManager.devices.collectAsState()

                MainScreen(
                    isLocalServerRunning = isLocalServerRunningState.value,
                    isLocalServerTransitioning = isLocalServerTransitioningState.value,
                    isGlobalServerRunning = isGlobalServerRunningState.value,
                    ipAddress = ipAddressState.value,
                    serverStartTime = serverStartTime,
                    activeConnections = activeConnections,
                    logs = logs,
                    devices = devices,
                    serverManager = serverManager,
                    serverKey = serverKeyState.value,
                    onRegenerateKey = {
                        serverKeyState.value = ServerRuntime.regenerateServerKey(this)
                    },
                    onToggleLocal = { toggleLocalServer() },
                    onToggleGlobal = { toggleGlobalServer() },
                    webRTCManager = webRTCManagerState.value
                )
            }
        }
    }

    private fun toggleLocalServer() {
        if (isLocalServerTransitioningState.value) return
        if (isLocalServerRunningState.value) {
            stopLocalServer()
        } else {
            requestServerStart(PendingStartAction.LOCAL)
        }
    }

    private fun toggleGlobalServer() {
        if (isGlobalServerRunningState.value) {
            stopGlobalServer()
        } else {
            requestServerStart(PendingStartAction.GLOBAL)
        }
    }

    private fun requestServerStart(action: PendingStartAction) {
        pendingStartAction = action
        if (serverManager.hasSharedFolder()) {
            requestNotificationPermissionOrStart()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Escolher pasta compartilhada")
                .setMessage(
                    "O conteúdo da pasta escolhida poderá ser listado e baixado por dispositivos que " +
                        "se conectarem aos servidores iniciados. O servidor HTTP também permite enviar " +
                        "arquivos para essa pasta. O app terá acesso somente à pasta autorizada por você."
                )
                .setNegativeButton("Cancelar") { _, _ -> pendingStartAction = null }
                .setPositiveButton("Continuar") { _, _ -> sharedFolderPickerLauncher.launch(null) }
                .show()
        }
    }

    private fun requestNotificationPermissionOrStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runPendingStartAction()
        }
    }

    private fun runPendingStartAction() {
        val action = pendingStartAction
        pendingStartAction = null
        when (action) {
            PendingStartAction.LOCAL -> startLocalServer()
            PendingStartAction.GLOBAL -> startGlobalServer()
            null -> Unit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStartAction?.let { outState.putString(STATE_PENDING_START, it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun startLocalServer() {
        if (isLocalServerTransitioningState.value || isLocalServerRunningState.value) return
        lifecycleScope.launch {
            isLocalServerTransitioningState.value = true
            try {
                ServerService.startService(this@MainActivity)
                withContext(Dispatchers.IO) {
                    serverManager.startServer()
                    ServerRuntime.isLocalServerRunning.value = serverManager.isRunning
                }
                isLocalServerRunningState.value = true
                ipAddressState.value = getLocalIpAddress()
            } catch (e: Exception) {
                if (!isGlobalServerRunningState.value) {
                    ServerService.stopService(this@MainActivity)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Erro ao iniciar o servidor local: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isLocalServerTransitioningState.value = false
            }
        }
    }

    private fun stopLocalServer() {
        if (isLocalServerTransitioningState.value || !isLocalServerRunningState.value) return
        lifecycleScope.launch {
            isLocalServerTransitioningState.value = true
            try {
                withContext(Dispatchers.IO) {
                    serverManager.stopServer()
                    ServerRuntime.isLocalServerRunning.value = serverManager.isRunning
                }
                isLocalServerRunningState.value = false
                if (!isGlobalServerRunningState.value) {
                    ipAddressState.value = "--"
                    ServerService.stopService(this@MainActivity)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Erro ao parar o servidor local: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isLocalServerTransitioningState.value = false
            }
        }
    }

    private fun startGlobalServer() {
        try {
            webRTCManagerState.value = ServerRuntime.startWebRTCServer(
                this,
                serverManager,
                serverKeyState.value
            )
            
            ServerService.startService(this)
            isGlobalServerRunningState.value = true
            ServerRuntime.isGlobalServerRunning.value = true
            ipAddressState.value = getLocalIpAddress()
            serverManager.addLog("P2P: servidor WebRTC iniciado. Aguardando oferta manual.")
        } catch (e: Throwable) {
            ServerRuntime.stopWebRTCServer()
            webRTCManagerState.value = null
            if (!isLocalServerRunningState.value) {
                ServerService.stopService(this)
            }
            Log.e("MainActivity", "Erro P2P", e)
            Toast.makeText(this, "Erro ao iniciar o servidor P2P: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopGlobalServer() {
        ServerRuntime.stopWebRTCServer()
        webRTCManagerState.value = null
        isGlobalServerRunningState.value = false
        if (!isLocalServerRunningState.value) {
            ipAddressState.value = "--"
            ServerService.stopService(this)
        }
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    companion object {
        private const val STATE_PENDING_START = "pending_server_start"
    }

}
