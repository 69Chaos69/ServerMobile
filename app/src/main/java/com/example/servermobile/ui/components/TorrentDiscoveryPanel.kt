package com.example.servermobile.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.servermobile.ServerManager
import com.example.servermobile.TorrentDiscoveryManager
import com.example.servermobile.TorrentTransfer
import com.example.servermobile.ui.theme.CardBackground
import com.example.servermobile.ui.theme.ErrorRed
import com.example.servermobile.ui.theme.PrimaryBlue
import com.example.servermobile.ui.theme.TextPrimary
import com.example.servermobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
fun TorrentDiscoveryPanel(
    discoveryManager: TorrentDiscoveryManager,
    serverManager: ServerManager,
    startupError: String?
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val transfers by discoveryManager.transfers.collectAsState()
    val networkStatus by discoveryManager.networkStatus.collectAsState()
    var localFiles by remember { mutableStateOf(emptyList<java.io.File>()) }
    var magnetInput by remember { mutableStateOf("") }
    var generatedMagnet by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busyFilePath by remember { mutableStateOf<String?>(null) }
    var isAddingMagnet by remember { mutableStateOf(false) }
    var isImportingFile by remember { mutableStateOf(false) }
    var pendingConnectionFile by remember { mutableStateOf("") }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isImportingFile = true
                error = null
                try {
                    val contents = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            readConnectionFile(input)
                        } ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado.")
                    }
                    discoveryManager.addConnectionFile(contents)
                    Toast.makeText(context, "Arquivo importado; buscando peers", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    error = e.message ?: "Não foi possível importar o arquivo de conexão."
                } finally {
                    isImportingFile = false
                }
            }
        }
    }
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(pendingConnectionFile)
                        } ?: throw IllegalStateException("Não foi possível gravar o arquivo selecionado.")
                    }
                    Toast.makeText(context, "Arquivo de conexão salvo", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    error = e.message ?: "Não foi possível salvar o arquivo de conexão."
                } finally {
                    pendingConnectionFile = ""
                }
            }
        } else {
            pendingConnectionFile = ""
        }
    }

    LaunchedEffect(serverManager) {
        localFiles = withContext(Dispatchers.IO) { serverManager.getLocalFiles() }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Descoberta BitTorrent", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(networkStatus, color = TextSecondary, fontSize = 13.sp)
            Text(
                "Use um magnet para encontrar peers do mesmo torrent: DHT funciona pela internet, LSD na rede local e PEX repassa peers já encontrados.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Button(
                onClick = { importFileLauncher.launch(arrayOf("*/*")) },
                enabled = !isImportingFile,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImportingFile) {
                    CircularProgressIndicator()
                } else {
                    Text("Importar arquivo de conexão")
                }
            }
            OutlinedTextField(
                value = magnetInput,
                onValueChange = { magnetInput = it },
                label = { Text("Magnet link") },
                enabled = !isAddingMagnet,
                minLines = 2,
                maxLines = 4,
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
                        isAddingMagnet = true
                        error = null
                        try {
                            discoveryManager.addMagnet(magnetInput)
                            magnetInput = ""
                        } catch (e: Exception) {
                            error = e.message ?: "Não foi possível adicionar o magnet."
                        } finally {
                            isAddingMagnet = false
                        }
                    }
                },
                enabled = magnetInput.isNotBlank() && !isAddingMagnet,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAddingMagnet) {
                    CircularProgressIndicator()
                } else {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Encontrar peers e baixar")
                }
            }

            if (localFiles.isNotEmpty()) {
                Text("Publicar item compartilhado", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                localFiles.forEach { file ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(file.name, color = TextSecondary, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        busyFilePath = file.absolutePath
                                        error = null
                                        generatedMagnet = ""
                                        try {
                                            generatedMagnet = discoveryManager.createAndSeed(file)
                                        } catch (e: Exception) {
                                            error = e.message ?: "Não foi possível publicar este item."
                                        } finally {
                                            busyFilePath = null
                                        }
                                    }
                                },
                                enabled = busyFilePath == null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (busyFilePath == file.absolutePath) {
                                    CircularProgressIndicator()
                                } else {
                                    Text("Criar magnet")
                                }
                            }
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        busyFilePath = file.absolutePath
                                        error = null
                                        try {
                                            val magnet = discoveryManager.createAndSeed(file)
                                            pendingConnectionFile =
                                                discoveryManager.createConnectionFile(file.name, magnet)
                                            val safeName = file.name
                                                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                                .take(80)
                                            exportFileLauncher.launch("ServerMobile-$safeName.smpeer")
                                        } catch (e: Exception) {
                                            error = e.message ?: "Não foi possível criar o arquivo de conexão."
                                        } finally {
                                            busyFilePath = null
                                        }
                                    }
                                },
                                enabled = busyFilePath == null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Exportar arquivo")
                            }
                        }
                    }
                }
            }

            if (generatedMagnet.isNotBlank()) {
                OutlinedTextField(
                    value = generatedMagnet,
                    onValueChange = {},
                    label = { Text("Magnet para compartilhar") },
                    readOnly = true,
                    minLines = 2,
                    maxLines = 4,
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
                        clipboardManager.setText(buildAnnotatedString { append(generatedMagnet) })
                        Toast.makeText(context, "Magnet copiado", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar magnet")
                }
            }

            startupError?.let { Text(it, color = ErrorRed, fontSize = 13.sp) }
            error?.let { Text(it, color = ErrorRed, fontSize = 13.sp) }

            transfers.forEach { transfer ->
                TorrentTransferRow(transfer) {
                    coroutineScope.launch {
                        try {
                            discoveryManager.removeTransfer(transfer.infoHash)
                        } catch (e: Exception) {
                            error = e.message ?: "Não foi possível remover o torrent."
                        }
                    }
                }
            }

            Text(
                "Atenção: torrents publicados aqui são públicos. Qualquer pessoa com o magnet pode baixar os arquivos; DHT/LSD/PEX não aplicam usuário, senha ou a chave do WebRTC. Não publique conteúdo sensível. A identidade e o navegador autenticado por peer ainda não estão ligados a este fluxo.",
                color = ErrorRed,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TorrentTransferRow(transfer: TorrentTransfer, onRemove: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transfer.name.ifBlank { transfer.infoHash },
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remover torrent", tint = ErrorRed)
            }
        }
        LinearProgressIndicator(
            progress = { transfer.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${(transfer.progress * 100).toInt()}% • ${transfer.peerCount} peers • " +
                "${transfer.downloadBytesPerSecond / 1024} KB/s ↓ • " +
                "${transfer.uploadBytesPerSecond / 1024} KB/s ↑" +
                if (transfer.isSeeding) " • semeando" else "",
            color = TextSecondary,
            fontSize = 12.sp
        )
        if (transfer.error.isNotBlank()) {
            Text(transfer.error, color = ErrorRed, fontSize = 12.sp)
        }
    }
}

private const val MAX_CONNECTION_FILE_BYTES = 64 * 1024

private fun readConnectionFile(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_CONNECTION_FILE_BYTES) {
            "O arquivo de conexão excede o tamanho permitido."
        }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
