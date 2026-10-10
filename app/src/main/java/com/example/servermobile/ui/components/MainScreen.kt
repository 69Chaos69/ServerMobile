package com.example.servermobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.example.servermobile.ServerManager
import com.example.servermobile.WebRTCP2PManager
import com.example.servermobile.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isLocalServerRunning: Boolean,
    isLocalServerTransitioning: Boolean,
    isGlobalServerRunning: Boolean,
    ipAddress: String,
    serverStartTime: Long,
    activeConnections: Int,
    logs: List<String>,
    devices: Set<String>,
    serverManager: ServerManager,
    serverKey: String,
    onRegenerateKey: () -> Unit,
    onToggleLocal: (Boolean) -> Unit,
    onToggleGlobal: (Boolean) -> Unit,
    webRTCManager: WebRTCP2PManager?
) {
    val anyServerRunning = isLocalServerRunning || isGlobalServerRunning
    val settings by serverManager.settings.collectAsState()
    val runningPort by serverManager.runningPort.collectAsState()
    // Tick to update uptime
    var currentTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(anyServerRunning) {
        while (anyServerRunning) {
            delay(1000L)
            currentTick = System.currentTimeMillis()
        }
    }

    val uptimeSeconds = if (anyServerRunning && serverStartTime > 0L) {
        (currentTick - serverStartTime) / 1000L
    } else {
        0L
    }

    var currentTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = { BottomNavBar(currentTab = currentTab, onTabSelected = { currentTab = it }) },
        containerColor = DarkBackground
    ) { paddingValues ->
        if (currentTab == 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Server Mobile",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            ServerStatusCard(anyServerRunning, ipAddress, uptimeSeconds, activeConnections)
            
            ServerCredentialsCard(
                serverKey = serverKey,
                onRegenerateKey = onRegenerateKey,
                isServerRunning = isGlobalServerRunning,
                webRTCManager = webRTCManager
            )

            NetworkLinkCard(
                isLocalServerRunning = isLocalServerRunning,
                isLocalServerTransitioning = isLocalServerTransitioning,
                isGlobalServerRunning = isGlobalServerRunning,
                ipAddress = ipAddress,
                port = if (isLocalServerRunning && runningPort > 0) runningPort else settings.port,
                onToggleLocal = onToggleLocal,
                onToggleGlobal = onToggleGlobal
            )
            
            QuickAccessGrid(logs = logs, devices = devices, serverManager = serverManager)
        }
        } else if (currentTab == 1) {
            ClientScreenContent(
                paddingValues = paddingValues,
                isGlobalServerRunning = isGlobalServerRunning,
                serverManager = serverManager
            )
        } else if (currentTab == 2) {
            LogsScreenContent(paddingValues = paddingValues)
        } else if (currentTab == 3) {
            ConnectionsScreenContent(serverManager)
        } else {
            SettingsScreenContent(serverManager, isLocalServerRunning)
        }
    }
}
