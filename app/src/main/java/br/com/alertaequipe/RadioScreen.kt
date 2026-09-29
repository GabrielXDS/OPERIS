package br.com.alertaequipe

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RadioScreen(teamId: String?, teamName: String?, operationalFunction: String?, onDuty: Boolean) {
    val context = LocalContext.current.applicationContext
    val state by PttRadioRuntime.state.collectAsState()
    val runtimeTeamId by PttRadioRuntime.teamId.collectAsState()
    val runtimeChannel by PttRadioRuntime.channel.collectAsState()
    val activeSpeaker by PttRadioRuntime.activeSpeaker.collectAsState()
    val activeSpeakerChannel by PttRadioRuntime.activeSpeakerChannel.collectAsState()
    val recentHistory by PttRadioRuntime.history.collectAsState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val effectiveFunction = operationalFunction?.takeIf { it.isNotBlank() } ?: Local.operationalFunction
    val functionChannel = PttChannel.forFunction(effectiveFunction)
    val availableChannels = if (functionChannel == PttChannel.TODOS) listOf(PttChannel.TODOS) else listOf(functionChannel, PttChannel.TODOS)
    val micGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted.value = granted
    }

    val activeForThisTeam = onDuty && !teamId.isNullOrBlank() && runtimeTeamId == teamId
    val effectiveState = when {
        !onDuty -> PttUiState(status = PttConnectionStatus.DISCONNECTED)
        activeForThisTeam -> state
        else -> PttUiState(status = PttConnectionStatus.CONNECTING)
    }
    val scale by animateFloatAsState(if (effectiveState.transmitting) 0.94f else 1f, label = "pttScale")
    val buttonColor by animateColorAsState(
        when {
            effectiveState.busy -> AppRadioBusy
            effectiveState.status == PttConnectionStatus.DISCONNECTED && !effectiveState.error.isNullOrBlank() -> AppRadioError
            effectiveState.transmitting -> AppRadioActive
            else -> AppRadio
        }, label = "pttColor"
    )
    val buttonReady = effectiveState.status == PttConnectionStatus.CONNECTED

    val buttonLabel = when {
        teamId == null -> "SELECIONE\nUMA EQUIPE"
        !onDuty -> "FORA DO\nPLANT\u00c3O"
        effectiveState.transmitting -> "TRANSMITINDO..."
        effectiveState.requesting -> "SOLICITANDO\nCANAL..."
        effectiveState.busy -> "CANAL\nOCUPADO"
        effectiveState.status == PttConnectionStatus.CONNECTING ||
            effectiveState.status == PttConnectionStatus.RECONNECTING -> "CONECTANDO..."
        effectiveState.status == PttConnectionStatus.DISCONNECTED -> "RECONECTAR"
        else -> "FALAR"
    }

    val hint = when {
        teamId == null -> "Selecione uma equipe para usar o r\u00e1dio."
        !onDuty -> "Assuma um plant\u00e3o para ativar o r\u00e1dio operacional."
        effectiveState.busy -> "Canal ocupado" + (effectiveState.holderName?.let { " por $it" } ?: "") + ". Solte e tente de novo."
        effectiveState.status == PttConnectionStatus.CONNECTING -> "Conectando ao canal..."
        effectiveState.status == PttConnectionStatus.RECONNECTING -> "Reconectando..."
        effectiveState.status == PttConnectionStatus.CONNECTED && !micGranted.value -> "Conceda o acesso ao microfone para falar."
        effectiveState.status == PttConnectionStatus.CONNECTED -> "Segure para falar"
        effectiveState.status == PttConnectionStatus.DISCONNECTED ->
            effectiveState.error?.takeIf { it.isNotBlank() } ?: "Sem conex\u00e3o com o canal. Toque para tentar novamente."
        else -> ""
    }

    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CANAL", color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            availableChannels.forEach { option ->
                FilterChip(
                    selected = activeForThisTeam && runtimeChannel == option,
                    onClick = {
                        if (onDuty && teamId != null && runtimeChannel != option)
                            PttRadioService.start(context, teamId, teamName ?: teamId, effectiveFunction, option)
                    },
                    enabled = teamId != null && onDuty,
                    label = { Text(option.label.uppercase()) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (effectiveState.status == PttConnectionStatus.CONNECTED && effectiveState.connectedCount > 0) {
            Text(
                "${effectiveState.connectedCount} conectado${if (effectiveState.connectedCount > 1) "s" else ""}",
                color = if (buttonReady) AppRadioAccent else AppMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppPanel)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (activeSpeaker != null) "FALANDO AGORA" else "CANAL PRONTO",
                    color = if (activeSpeaker != null) AppRadioAccent else AppMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    activeSpeaker ?: "Ninguém falando",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    (activeSpeakerChannel ?: runtimeChannel).label.uppercase(),
                    color = AppMuted,
                    fontSize = 11.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .size(200.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (teamId != null && onDuty) buttonColor else AppPanel)
                .pointerInput(teamId, onDuty, effectiveState.status, micGranted.value) {
                    detectTapGestures(onPress = {
                        if (teamId == null || !onDuty) {
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        when {
                            !micGranted.value -> {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                tryAwaitRelease()
                            }
                            effectiveState.status == PttConnectionStatus.DISCONNECTED -> {
                                PttRadioService.reconnect(context)
                                tryAwaitRelease()
                            }
                            effectiveState.status != PttConnectionStatus.CONNECTED -> tryAwaitRelease()
                            effectiveState.transmitting -> tryAwaitRelease()
                            else -> {
                                PttRadioService.press(context)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    PttRadioService.release(context)
                                }
                            }
                        }
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                buttonLabel,
                color = Color.White,
                fontSize = if (effectiveState.requesting || effectiveState.busy) 22.sp else 27.sp,
                lineHeight = 30.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Black
            )
        }
        Text(hint, color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (onDuty && effectiveState.status == PttConnectionStatus.DISCONNECTED && effectiveState.error.isNullOrBlank()) {
            Text("Tentando reconectar automaticamente. Toque para tentar agora.",
                color = AppAmber, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppPanel)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "ÚLTIMAS TRANSMISSÕES",
                    color = AppMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (recentHistory.isEmpty()) {
                    Text("Nenhuma transmissão recente.", color = AppMuted, fontSize = 13.sp)
                } else {
                    recentHistory.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.speakerName,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(item.channel.label.uppercase(), color = AppMuted, fontSize = 10.sp)
                            }
                            Text(
                                timeFormatter.format(Date(item.timestamp)),
                                color = AppMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
