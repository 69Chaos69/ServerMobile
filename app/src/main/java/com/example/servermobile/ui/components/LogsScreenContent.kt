package com.example.servermobile.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.servermobile.AppLogger
import com.example.servermobile.ui.theme.CardBackground
import com.example.servermobile.ui.theme.TextPrimary
import com.example.servermobile.ui.theme.TextSecondary
import java.io.File

@Composable
fun LogsScreenContent(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val logs by AppLogger.logsFlow.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll para o final quando novos logs chegam
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Logs do Sistema",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Button(
                onClick = {
                    val logFilePath = AppLogger.getLogFilePath()
                    if (logFilePath != null) {
                        val file = File(logFilePath)
                        if (file.exists()) {
                            try {
                                val uri: Uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Exportar Logs"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Erro ao exportar logs: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Arquivo de log não encontrado", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CardBackground)
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Exportar", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Exportar")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CardBackground, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "Nenhum log registrado ainda.",
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { log ->
                        Text(
                            text = log.trimEnd(),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                log.contains("ERROR/") -> androidx.compose.ui.graphics.Color(0xFFFF5252)
                                log.contains("WARN/") -> androidx.compose.ui.graphics.Color(0xFFFFD740)
                                else -> TextSecondary
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        Divider(color = TextSecondary.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }
}
