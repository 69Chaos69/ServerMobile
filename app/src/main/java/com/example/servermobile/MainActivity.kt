package com.example.servermobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import com.example.servermobile.AppLogger as Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.servermobile.ui.components.MainScreen
import com.example.servermobile.ui.theme.ServerAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainActivity : ComponentActivity() {

    private val serverManager by lazy { ServerManager(applicationContext) }
    private var webRTCManagerState = mutableStateOf<WebRTCP2PManager?>(null)
    
    // UI State
    private var isLocalServerRunningState = mutableStateOf(false)
    private var isLocalServerTransitioningState = mutableStateOf(false)
    private var isGlobalServerRunningState = mutableStateOf(false)
    private var ipAddressState = mutableStateOf("--")
    private var serverKeyState = mutableStateOf(generateRandomKey())

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private var pendingAction: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.init(this)
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
                    onRegenerateKey = { serverKeyState.value = generateRandomKey() },
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
            if (checkPermission()) {
                startLocalServer()
            } else {
                pendingAction = { startLocalServer() }
                requestPermission()
            }
        }
    }

    private fun toggleGlobalServer() {
        if (isGlobalServerRunningState.value) {
            stopGlobalServer()
        } else {
            if (checkPermission()) {
                startGlobalServer()
            } else {
                pendingAction = { startGlobalServer() }
                requestPermission()
            }
        }
    }

    private fun checkPermission(): Boolean {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val result = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val result1 = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            result == PackageManager.PERMISSION_GRANTED && result1 == PackageManager.PERMISSION_GRANTED
        }

        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return storagePermission && notificationPermission
    }

    private fun requestPermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivityForResult(intent, PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivityForResult(intent, PERMISSION_REQUEST_CODE)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    pendingAction?.invoke()
                    pendingAction = null
                } else {
                    Toast.makeText(this, "Permissão negada", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingAction?.invoke()
                pendingAction = null
            } else {
                Toast.makeText(this, "Permissão negada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startLocalServer() {
        if (isLocalServerTransitioningState.value || isLocalServerRunningState.value) return
        lifecycleScope.launch {
            isLocalServerTransitioningState.value = true
            try {
                withContext(Dispatchers.IO) { serverManager.startServer() }
                isLocalServerRunningState.value = true
                ipAddressState.value = getLocalIpAddress()
                ServerService.startService(this@MainActivity)
            } catch (e: Exception) {
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
                withContext(Dispatchers.IO) { serverManager.stopServer() }
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
            webRTCManagerState.value?.close()
            webRTCManagerState.value = WebRTCP2PManager(
                applicationContext,
                serverManager,
                serverKeyState.value
            )
            
            isGlobalServerRunningState.value = true
            ipAddressState.value = getLocalIpAddress()
            ServerService.startService(this)
            serverManager.addLog("P2P: servidor WebRTC iniciado. Aguardando oferta manual.")
        } catch (e: Throwable) {
            Log.e("MainActivity", "Erro P2P", e)
            Toast.makeText(this, "Erro ao iniciar o servidor P2P: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopGlobalServer() {
        webRTCManagerState.value?.close()
        webRTCManagerState.value = null

        isGlobalServerRunningState.value = false
        if (!isLocalServerRunningState.value) {
            ipAddressState.value = "--"
            ServerService.stopService(this)
        }
    }

    override fun onDestroy() {
        webRTCManagerState.value?.close()
        webRTCManagerState.value = null
        super.onDestroy()
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

    private fun generateRandomKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
