package com.example.servermobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.servermobile.ServerManager
import com.example.servermobile.ui.theme.CardBackground
import com.example.servermobile.ui.theme.TextPrimary
import com.example.servermobile.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun ConnectionsScreenContent(serverManager: ServerManager) {
    val clients by serverManager.connectedClients.collectAsState()
    val bandwidth by serverManager.bandwidth.collectAsState()
    val settings by serverManager.settings.collectAsState()

    LaunchedEffect(serverManager) {
        while (true) {
            delay(1000L)
            serverManager.refreshBandwidth()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Conexões e tráfego", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Upload atual: ${formatTransferRate(bandwidth.uploadBytesPerSecond)}", color = TextPrimary)
                Text("Enviado nesta sessão: ${formatBytes(bandwidth.totalUploadedBytes)}", color = TextSecondary)
                Text(
                    text = if (settings.uploadLimitKbps == 0) {
                        "Limite: sem restrição"
                    } else {
                        "Limite: ${settings.uploadLimitKbps} KB/s"
                    },
                    color = TextSecondary
                )
            }
        }
        Text("Clientes identificados (${clients.size})", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        if (clients.isEmpty()) {
            Text("Nenhum dispositivo acessou o host nesta execução.", color = TextSecondary)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clients, key = { it.address }) { client ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(client.address, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (client.activeRequests > 0) {
                                    "${client.activeRequests} solicitações ativas"
                                } else {
                                    "Sem transferência ativa"
                                },
                                color = TextSecondary
                            )
                            Text("Último acesso: ${formatLastSeen(client.lastSeenAt)}", color = TextSecondary)
                        }
                    }
                }
            }
        }
        Text(
            "Os clientes aparecem após fazerem uma solicitação HTTP; o endereço IP é usado para identificá-los.",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

private fun formatLastSeen(timestamp: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

private fun formatTransferRate(bytesPerSecond: Long): String =
    String.format(java.util.Locale.getDefault(), "%.1f KB/s", bytesPerSecond / 1024.0)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
