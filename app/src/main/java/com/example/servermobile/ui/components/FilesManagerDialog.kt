package com.example.servermobile.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.servermobile.ServerManager
import com.example.servermobile.SharedFile
import com.example.servermobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FilesManagerDialog(
    serverManager: ServerManager,
    isServerActive: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var files by remember { mutableStateOf(emptyList<SharedFile>()) }
    var fileToRename by remember { mutableStateOf<SharedFile?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }

    suspend fun refreshFiles() {
        try {
            files = withContext(Dispatchers.IO) { serverManager.getLocalFiles() }
            fileError = null
        } catch (error: Exception) {
            fileError = error.message ?: "Não foi possível listar a pasta compartilhada."
        }
    }

    LaunchedEffect(serverManager) { refreshFiles() }

    val sharedFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                val selected = withContext(Dispatchers.IO) {
                    serverManager.useFolderAsShared(it)
                }
                if (selected) {
                    refreshFiles()
                    Toast.makeText(context, "Pasta compartilhada sem copiar os arquivos", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "Não foi possível usar essa pasta. Escolha outra pasta com acesso de leitura e gravação.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.9f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Arquivos", color = TextPrimary)
                    Text(
                        text = "Pasta compartilhada: ${serverManager.getSharedFolderPath()}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                    Text(
                        text = "Uploads do PC: raiz da pasta compartilhada",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "Renomear ou excluir altera os arquivos originais.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                    if (isServerActive) {
                        Text(
                            "Pare os servidores antes de trocar a pasta compartilhada.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = { sharedFolderPickerLauncher.launch(null) },
                        enabled = !isServerActive
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = "Escolher pasta compartilhada",
                            tint = AccentColor
                        )
                    }
                }
            }
        },
        text = {
            if (fileError != null) {
                Text(fileError.orEmpty(), color = TextSecondary)
            } else if (files.isEmpty()) {
                Text("Nenhum arquivo encontrado.", color = TextSecondary)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        FileItemRow(
                            file = file,
                            onDelete = { deleteFromStorage ->
                                coroutineScope.launch {
                                    val deleted = withContext(Dispatchers.IO) {
                                        serverManager.deleteLocalFile(file.name, deleteFromStorage)
                                    }
                                    if (deleted) {
                                        refreshFiles()
                                        Toast.makeText(
                                            context,
                                            if (deleteFromStorage) {
                                                "Arquivo apagado do armazenamento: ${file.name}"
                                            } else {
                                                "Arquivo removido da lista: ${file.name}"
                                            },
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(context, "Erro ao deletar", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onRenameRequest = {
                                fileToRename = file
                            }
                        )
                    }
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

    fileToRename?.let { file ->
        var newName by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Renomear Arquivo", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Novo Nome", color = TextSecondary) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newName != file.name) {
                        coroutineScope.launch {
                            val renamed = withContext(Dispatchers.IO) {
                                serverManager.renameLocalFile(file.name, newName)
                            }
                            if (renamed) {
                                refreshFiles()
                                Toast.makeText(context, "Renomeado", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Erro ao renomear", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    fileToRename = null
                }) {
                    Text("Salvar", color = PrimaryGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkBackground
        )
    }
}

@Composable
fun FileItemRow(file: SharedFile, onDelete: (Boolean) -> Unit, onRenameRequest: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteFromStorage by remember(file.relativePath) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = if (file.isDirectory) "Pasta" else "Arquivo",
                tint = AccentColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(file.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1)
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                deleteFromStorage = false
                confirmDelete = true
            }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = PrimaryRed)
            }
            
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = TextSecondary)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(DarkBackground)
                ) {
                    DropdownMenuItem(
                        text = { Text("Editar Nome", color = TextPrimary) },
                        onClick = {
                            expanded = false
                            onRenameRequest()
                        }
                    )
                }
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Remover arquivo?", color = TextPrimary) },
                    text = {
                        Column {
                            Text(
                                if (deleteFromStorage) {
                                    "O arquivo será removido da pasta compartilhada e apagado do armazenamento do celular."
                                } else {
                                    "O arquivo será removido da lista compartilhada, mas continuará no armazenamento do celular."
                                },
                                color = TextSecondary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Checkbox(
                                    checked = deleteFromStorage,
                                    onCheckedChange = { deleteFromStorage = it }
                                )
                                Text(
                                    "Apagar também do armazenamento do celular",
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            onDelete(deleteFromStorage)
                        }) {
                            Text(
                                if (deleteFromStorage) "Apagar" else "Remover",
                                color = if (deleteFromStorage) PrimaryRed else AccentColor
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) {
                            Text("Cancelar", color = TextSecondary)
                        }
                    },
                    containerColor = DarkBackground
                )
            }
        }
    }
}
