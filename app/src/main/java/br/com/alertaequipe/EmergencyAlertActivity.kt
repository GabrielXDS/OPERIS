package br.com.alertaequipe

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmergencyAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareLockScreen()
        if (!stage(intent)) { finish(); return }
        setContent {
            AlertaTheme {
                val active by Local.current.collectAsState()
                LaunchedEffect(active?.id) {
                    if (active == null) finishAndRemoveTask()
                }
                BackHandler(enabled = active != null) { }
                EmergencyLockScreen(active) {
                    Alerts.silence(this@EmergencyAlertActivity)
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stage(intent)
    }

    private fun stage(intent: Intent): Boolean {
        val alert = Panic.decode(intent.getStringExtra("panic"))
            ?: Panic.decode(Local.prefs.getString("active", null))
            ?: return false
        val age = System.currentTimeMillis() - alert.time
        if (age !in 0..119_999) return false
        Local.current.value = alert
        return true
    }
    private fun prepareLockScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}

@Composable
private fun EmergencyLockScreen(alert: Panic?, onSilence: () -> Unit) {
    if (alert == null) return
    val time = remember(alert.time) {
        SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date(alert.time))
    }
    Column(
        Modifier.fillMaxSize().background(AppRed).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ALERTA DE EMERGÊNCIA", color = Color.White, fontSize = 30.sp,
                fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Text("Acionado por", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            Text(alert.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Recebido às $time", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("A sirene continuará até você silenciar ou o tempo de segurança encerrar.",
                color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onSilence,
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AppRed)
            ) {
                Text("SILENCIAR ALERTA", fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(12.dp))
            Text("Também é possível silenciar pela notificação na tela bloqueada.",
                color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, fontSize = 12.sp)
        }
    }
}
