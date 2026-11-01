package com.graphreader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Speed

private const val REQ_OVERLAY = 4101
private const val REQ_CAPTURE = 4102
private val Bg = Color(0xFF090B12)
private val Card = Color(0xFF121722)
private val Card2 = Color(0xFF171D2A)
private val Accent = Color(0xFF7C5CFF)
private val Accent2 = Color(0xFF25D0A5)
private val Muted = Color(0xFF8B93A7)

private fun formatInterval(ms: Long): String = if (ms < 1000) "${ms} ms" else "${ms / 1000}s"

class MainActivity : ComponentActivity() {
    private var pendingActivation = false
    private var panelActive by mutableStateOf(false)
    private var updateInterval by mutableStateOf(500L)

    private val intervalOptions = listOf(100L, 250L, 500L, 750L, 1000L, 2000L, 3000L, 5000L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateInterval = prefs().getLong(MonitorService.KEY_INTERVAL, MonitorService.DEFAULT_INTERVAL)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Card, primary = Accent, secondary = Accent2)) {
                GraphReaderHome(
                    active = panelActive,
                    updateInterval = updateInterval,
                    onActivatePanel = { activatePanel() },
                    onOpenSettings = { showUpdateSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingActivation && Settings.canDrawOverlays(this)) {
            pendingActivation = false
            requestScreenCapture()
        }
    }

    private fun prefs() = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE)

    private fun activatePanel() {
        if (!Settings.canDrawOverlays(this)) {
            pendingActivation = true
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun showUpdateSettings() {
        val labels = intervalOptions.map { formatInterval(it) }.toTypedArray()
        val checked = intervalOptions.indexOf(updateInterval).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Taxa de atualização")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                updateInterval = intervalOptions[which]
                prefs().edit().putLong(MonitorService.KEY_INTERVAL, updateInterval).apply()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .setMessage("Quanto menor o intervalo, mais frequente é a análise e maior pode ser o consumo de bateria.")
            .show()
    }

    @Deprecated("Deprecated in Android SDK but supported for this flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
        } else if (requestCode == REQ_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            panelActive = true
            val intent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START
                putExtra(MonitorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(MonitorService.EXTRA_RESULT_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        }
    }
}

@Composable
fun GraphReaderHome(
    active: Boolean,
    updateInterval: Long,
    onActivatePanel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111426), Bg, Color(0xFF07080D))))) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("LZGRAPH", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Reconhecimento inteligente de gráficos", color = Muted, fontSize = 12.sp)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = Card2) {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Configurações", tint = Color.White) }
                }
            }

            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), color = Card) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoGraph, null, tint = Accent2)
                        Spacer(Modifier.width(10.dp))
                        Text("STATUS DA ANÁLISE", color = Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (active) "Análise ao vivo" else "Aguardando ativação", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (active) "Captura autorizada e reconhecimento sendo atualizado continuamente."
                        else "Ative o painel para iniciar a captura autorizada da tela.",
                        color = Muted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = onActivatePanel,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (active) Accent2 else Accent)
                    ) {
                        Icon(Icons.Default.Visibility, null)
                        Spacer(Modifier.width(9.dp))
                        Text(if (active) "ANÁLISE ATIVA" else "ATIVAR PAINEL", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(Modifier.weight(1f), Icons.Default.Analytics, "Reconhecimento", if (active) "Ao vivo" else "Pronto")
                MetricCard(Modifier.weight(1f), Icons.Default.ArrowUpward, "Atualização", formatInterval(updateInterval))
            }

            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), color = Card) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GridView, null, tint = Accent)
                            Spacer(Modifier.width(9.dp))
                            Text("Reconhecimento avançado", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Text(if (active) "LIVE" else "OFF", color = if (active) Accent2 else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Velas/padrões", "Doji • Martelo • Engolfos")
                    InfoRow("Sequências", "3 candles / pressão")
                    InfoRow("Tendência", "Alta • Baixa • Lateral")
                    InfoRow("Atualização", formatInterval(updateInterval))
                    InfoRow("Fonte", "Tela autorizada")
                }
            }

            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), color = Card) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF1D2434)) {
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Speed, null, tint = Accent2) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Taxa de atualização", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Atual: ${formatInterval(updateInterval)} • toque para alterar", color = Muted, fontSize = 12.sp)
                    }
                    Text("ALTERAR", color = Accent2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("LZGraph • versão 1.4", Modifier.fillMaxWidth(), color = Color(0xFF5F6677), fontSize = 11.sp)
        }
    }
}

@Composable
fun MetricCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Surface(modifier, RoundedCornerShape(20.dp), color = Card) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
