package com.example.servermobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.servermobile.ui.theme.*

@Composable
fun NetworkLinkCard(
    isLocalServerRunning: Boolean,
    isLocalServerTransitioning: Boolean,
    isGlobalServerRunning: Boolean,
    ipAddress: String,
    port: Int,
    onToggleLocal: (Boolean) -> Unit,
    onToggleGlobal: (Boolean) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = "Network", tint = AccentColor)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Rede Local", color = TextSecondary, fontSize = 14.sp)
                val linkText = when {
                    isLocalServerTransitioning -> "Alterando estado..."
                    isLocalServerRunning -> "http://$ipAddress:$port"
                    else -> "Servidor parado"
                }
                Text(
                    text = linkText,
                    color = if (isLocalServerRunning) AccentColor else TextSecondary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable(enabled = isLocalServerRunning && !isLocalServerTransitioning) {
                        uriHandler.openUri("http://$ipAddress:$port")
                    }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = isLocalServerRunning,
                onCheckedChange = onToggleLocal,
                enabled = !isLocalServerTransitioning,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = PrimaryGreen,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = DarkBackground
                )
            )
        }
        
        // Linha divisória
        androidx.compose.material3.Divider(color = TextSecondary.copy(alpha = 0.2f))
        
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = "Global", tint = AccentColor)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Servidor P2P (WebRTC)", color = TextSecondary, fontSize = 14.sp)
                Text(
                    text = if (isGlobalServerRunning) "Aguardando oferta manual" else "Servidor parado",
                    color = if (isGlobalServerRunning) AccentColor else TextSecondary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                if (isGlobalServerRunning) {
                    Text(
                        text = "Sem servidor de sinalização; troque oferta e resposta manualmente.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = isGlobalServerRunning,
                onCheckedChange = onToggleGlobal,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = PrimaryGreen,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = DarkBackground
                )
            )
        }
    }
}
