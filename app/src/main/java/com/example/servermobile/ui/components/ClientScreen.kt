package com.example.servermobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import com.example.servermobile.RemoteFile
import com.example.servermobile.ServerManager
import com.example.servermobile.TorrentDiscoveryManager
import com.example.servermobile.WebRTCP2PClientManager
import com.example.servermobile.ui.theme.CardBackground
import com.example.servermobile.ui.theme.DarkBackground
import com.example.servermobile.ui.theme.ErrorRed
import com.example.servermobile.ui.theme.PrimaryBlue
import com.example.servermobile.ui.theme.SuccessGreen
import com.example.servermobile.ui.theme.TextPrimary
import com.example.servermobile.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ClientScreenContent(
    paddingValues: PaddingValues,
    isGlobalServerRunning: Boolean,
    serverManager: ServerManager
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var clientManager by remember { mutableStateOf<WebRTCP2PClientManager?>(null) }
    var remoteServerKey by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isPreparingOffer by remember { mutableStateOf(false) }
    val torrentDiscoveryManager = remember(context) { TorrentDiscoveryManager(context) }
    val currentManager by rememberUpdatedState(clientManager)
    val connectionState by clientManager?.connectionState?.collectAsState()
        ?: remember { mutableStateOf("Desconectado") }
    val remoteFiles by clientManager?.remoteFiles?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val downloadStatus by clientManager?.downloadStatus?.collectAsState()
        ?: remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { currentManager?.disconnect() }
    }

    DisposableEffect(torrentDiscoveryManager) {
        onDispose { torrentDiscoveryManager.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Conexão P2P",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = if (isGlobalServerRunning) {
                "Este aparelho também está servindo arquivos. Para conectar outro aparelho, use a oferta gerada abaixo como cliente."
            } else {
                "Gere uma oferta, envie-a ao dono do servidor e cole aqui a resposta que ele devolver."
            },
            fontSize = 14.sp,
            color = TextSecondary
        )
        OutlinedTextField(
            value = remoteServerKey,
            onValueChange = { remoteServerKey = it },
            label = { Text("Chave do servidor remoto") },
            enabled = clientManager == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Button(
            onClick = {
                if (clientManager == null) {
                    if (remoteServerKey.isBlank()) {
                        error = "Informe a chave do servidor que deseja acessar."
                        return@Button
                    }
                    val manager = WebRTCP2PClientManager(context, remoteServerKey)
                    clientManager = manager
                    coroutineScope.launch {
                        isPreparingOffer = true
                        error = null
                        try {
                            offer = manager.createOffer()
                        } catch (e: Exception) {
                            error = e.message ?: "Não foi possível criar a oferta."
                            manager.disconnect()
                            clientManager = null
                        } finally {
                            isPreparingOffer = false
                        }
                    }
                } else {
                    clientManager?.disconnect()
                    clientManager = null
                    offer = ""
                    answer = ""
                    error = null
                }
            },
            enabled = !isPreparingOffer,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (clientManager == null) PrimaryBlue else ErrorRed
            )
        ) {
            Text(
                when {
                    isPreparingOffer -> "Preparando oferta..."
                    clientManager == null -> "Gerar oferta"
                    else -> "Cancelar conexão"
                }
            )
        }

        if (offer.isNotBlank()) {
            OutlinedTextField(
                value = offer,
                onValueChange = {},
                label = { Text("Oferta para enviar ao servidor") },
                readOnly = true,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Button(
                onClick = {
                    clipboardManager.setText(buildAnnotatedString { append(offer) })
                    Toast.makeText(context, "Oferta copiada", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copiar oferta")
            }
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Cole aqui a resposta do servidor") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Button(
                onClick = {
                    coroutineScope.launch {
                        error = null
                        try {
                            clientManager?.applyAnswer(answer)
                        } catch (e: Exception) {
                            error = e.message ?: "Não foi possível aplicar a resposta."
                        }
                    }
                },
                enabled = answer.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Conectar com a resposta")
            }
        }

        Text("Status: $connectionState", color = TextSecondary, fontSize = 14.sp)
        error?.let { Text(it, color = ErrorRed, fontSize = 14.sp) }
        if (downloadStatus.isNotBlank()) {
            Text(downloadStatus, color = TextSecondary, fontSize = 13.sp)
        }

        Text(
            text = "A sinalização é manual e a conexão consulta o servidor STUN do Google. A oferta pode revelar endereços de rede; envie-a somente à pessoa certa. Redes com NAT restritivo podem exigir TURN.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        if (remoteFiles.isNotEmpty()) {
            Text("Arquivos do servidor", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
            ) {
                items(remoteFiles) { file ->
                    RemoteFileItem(file = file) { clientManager?.requestDownload(file.name) }
                }
            }

        }

        TorrentDiscoveryPanel(
            discoveryManager = torrentDiscoveryManager,
            serverManager = serverManager
        )
    }
}

@Composable
fun RemoteFileItem(file: RemoteFile, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text("${file.size / 1024} KB", color = TextSecondary, fontSize = 14.sp)
            }
            if (!file.isDirectory) {
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Baixar",
                        tint = SuccessGreen
                    )
                }
            }
        }
    }
}
