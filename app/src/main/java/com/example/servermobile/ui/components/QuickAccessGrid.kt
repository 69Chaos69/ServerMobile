package com.example.servermobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.servermobile.ServerManager
import com.example.servermobile.ui.theme.*

@Composable
fun QuickAccessGrid(
    logs: List<String>,
    devices: Set<String>,
    serverManager: ServerManager,
    isServerActive: Boolean
) {
    val context = LocalContext.current
    var showLogsDialog by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    var showFilesDialog by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }

    Column {
        Text("Acesso Rápido", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            QuickAccessButton("Arquivos", Icons.Filled.Folder) {
                showFilesDialog = true
            }
            QuickAccessButton("APIs", Icons.Filled.Code) {
                showApiDialog = true
            }
            QuickAccessButton("Logs", Icons.Filled.List) {
                showLogsDialog = true
            }
            QuickAccessButton("Disposit.", Icons.Filled.PhoneAndroid) {
                showDevicesDialog = true
            }
        }
    }

    if (showFilesDialog) {
        FilesManagerDialog(
            serverManager = serverManager,
            isServerActive = isServerActive,
            onDismiss = { showFilesDialog = false }
        )
    }

    if (showApiDialog) {
        ApiManagerDialog(serverManager = serverManager, onDismiss = { showApiDialog = false })
    }

    if (showLogsDialog) {
        DataDialog(
            title = "Logs do Servidor",
            dataList = logs.ifEmpty { listOf("Nenhum log disponível.") },
            onDismiss = { showLogsDialog = false }
        )
    }

    if (showDevicesDialog) {
        DataDialog(
            title = "Dispositivos Conectados",
            dataList = devices.toList().ifEmpty { listOf("Nenhum dispositivo conectou ainda.") },
            onDismiss = { showDevicesDialog = false }
        )
    }
}

@Composable
fun DataDialog(title: String, dataList: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(dataList) { item ->
                    Text(item, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = AccentColor)
            }
        },
        containerColor = CardBackground
    )
}

@Composable
fun QuickAccessButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = AccentColor)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}
