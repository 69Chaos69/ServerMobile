package com.example.servermobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.servermobile.WebRTCP2PManager
import com.example.servermobile.ui.theme.CardBackground
import com.example.servermobile.ui.theme.PrimaryBlue
import com.example.servermobile.ui.theme.TextPrimary
import com.example.servermobile.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ServerCredentialsCard(
    serverKey: String,
    onRegenerateKey: () -> Unit,
    isServerRunning: Boolean,
    webRTCManager: WebRTCP2PManager?
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var offer by remember(webRTCManager) { mutableStateOf("") }
    var answer by remember(webRTCManager) { mutableStateOf("") }
    var error by remember(webRTCManager) { mutableStateOf<String?>(null) }
    var isCreatingAnswer by remember(webRTCManager) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Conexão P2P manual",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = if (isServerRunning) {
                    "Compartilhe a chave e receba a oferta gerada pelo cliente. A oferta e a resposta precisam ser trocadas por outro canal. STUN usa um servidor do Google."
                } else {
                    "Ative o servidor P2P para receber uma oferta de conexão."
                },
                fontSize = 13.sp,
                color = TextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Chave do servidor: $serverKey",
                    color = PrimaryBlue,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboardManager.setText(buildAnnotatedString { append(serverKey) })
                    Toast.makeText(context, "Chave copiada", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar chave",
                        tint = PrimaryBlue
                    )
                }
                IconButton(onClick = onRegenerateKey, enabled = !isServerRunning) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Gerar nova chave",
                        tint = if (isServerRunning) TextSecondary else PrimaryBlue
                    )
                }
            }

            if (isServerRunning && webRTCManager != null) {
                OutlinedTextField(
                    value = offer,
                    onValueChange = { offer = it },
                    label = { Text("Cole aqui a oferta do cliente") },
                    enabled = !isCreatingAnswer,
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
                            isCreatingAnswer = true
                            error = null
                            answer = ""
                            try {
                                answer = webRTCManager.createAnswer(offer)
                            } catch (e: Exception) {
                                error = e.message ?: "Não foi possível processar a oferta."
                            } finally {
                                isCreatingAnswer = false
                            }
                        }
                    },
                    enabled = offer.isNotBlank() && !isCreatingAnswer,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    if (isCreatingAnswer) {
                        CircularProgressIndicator()
                    } else {
                        Text("Gerar resposta")
                    }
                }
                error?.let { Text(it, color = androidx.compose.ui.graphics.Color.Red, fontSize = 13.sp) }

                if (answer.isNotBlank()) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = {},
                        label = { Text("Resposta para enviar ao cliente") },
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            clipboardManager.setText(buildAnnotatedString { append(answer) })
                            Toast.makeText(context, "Resposta copiada", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copiar resposta", tint = PrimaryBlue)
                        }
                        Text("Copiar resposta", color = PrimaryBlue)
                    }
                }
                Text(
                    text = "A conexão tenta ser direta com STUN. Algumas redes exigem TURN. A oferta/resposta pode revelar endereços de rede; envie apenas à outra pessoa.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
