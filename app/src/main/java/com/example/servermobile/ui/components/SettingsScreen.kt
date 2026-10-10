package com.example.servermobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.servermobile.ServerManager
import com.example.servermobile.ServerSettings
import com.example.servermobile.ui.theme.CardBackground
import com.example.servermobile.ui.theme.TextPrimary
import com.example.servermobile.ui.theme.TextSecondary

@Composable
fun SettingsScreenContent(serverManager: ServerManager, isLocalServerRunning: Boolean) {
    val context = LocalContext.current
    val settings by serverManager.settings.collectAsState()
    var port by remember(settings) { mutableStateOf(settings.port.toString()) }
    var maxConnections by remember(settings) { mutableStateOf(settings.maxConnections.toString()) }
    var uploadLimit by remember(settings) { mutableStateOf(settings.uploadLimitKbps.toString()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Configurações", color = TextPrimary, fontSize = 24.sp)
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Porta do host local") },
                    supportingText = { Text("Entre 1 e 65535") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxConnections,
                    onValueChange = { maxConnections = it.filter(Char::isDigit).take(3) },
                    label = { Text("Máximo de solicitações simultâneas") },
                    supportingText = { Text("Entre 1 e 100 solicitações") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uploadLimit,
                    onValueChange = { uploadLimit = it.filter(Char::isDigit).take(6) },
                    label = { Text("Limite de upload (KB/s)") },
                    supportingText = { Text("0 significa sem limite; máximo de 102400 KB/s") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        try {
                            serverManager.updateSettings(
                                ServerSettings(
                                    port = port.toInt(),
                                    maxConnections = maxConnections.toInt(),
                                    uploadLimitKbps = uploadLimit.toInt()
                                )
                            )
                            Toast.makeText(context, "Configurações salvas", Toast.LENGTH_SHORT).show()
                        } catch (error: NumberFormatException) {
                            Toast.makeText(context, "Preencha todos os campos numéricos", Toast.LENGTH_SHORT).show()
                        } catch (error: IllegalArgumentException) {
                            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salvar configurações")
                }
                if (isLocalServerRunning) {
                    Text(
                        "A nova porta será aplicada ao reiniciar o host. Os limites de conexões e upload são aplicados imediatamente.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
