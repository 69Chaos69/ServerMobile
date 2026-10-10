package com.example.servermobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.servermobile.ServerManager
import com.example.servermobile.ui.theme.*

@Composable
fun ApiManagerDialog(serverManager: ServerManager, onDismiss: () -> Unit) {
    val apis by serverManager.customApis.collectAsState()
    var newPath by remember { mutableStateOf("") }
    var newResponse by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.9f),
        title = { Text("Gerenciador de APIs", color = TextPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                // Form to add new API
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Caminho (Ex: meus-dados)", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newResponse,
                    onValueChange = { newResponse = it },
                    label = { Text("Resposta JSON", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (newPath.isNotBlank() && newResponse.isNotBlank()) {
                            serverManager.addApi(newPath, newResponse)
                            newPath = ""
                            newResponse = ""
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
                ) {
                    Text("Adicionar API", color = Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = TextSecondary.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // List of APIs
                if (apis.isEmpty()) {
                    Text("Nenhuma API cadastrada.", color = TextSecondary)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(apis) { api ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("/api/custom/${api.path}", color = AccentColor, fontSize = 16.sp)
                                    Text(api.response, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                                }
                                IconButton(onClick = { serverManager.removeApi(api.path) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Deletar API", tint = PrimaryRed)
                                }
                            }
                        }
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
}
