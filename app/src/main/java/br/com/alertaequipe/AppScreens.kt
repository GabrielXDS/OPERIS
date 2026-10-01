package br.com.alertaequipe

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Identidade visual original, restaurada e madura: fundo escuro, vermelho de alerta,
// verde/verde-água de prontidão, textos claros e contraste forte.
internal val AppInk = Color(0xFF101820)
internal val AppPanel = Color(0xFF1B2732)
internal val AppRed = Color(0xFFE74448)
internal val AppMuted = Color(0xFFA9B7C5)
// Cor operacional do Rádio (PTT): verde militar, claramente separada do vermelho de emergência.
internal val AppRadio = Color(0xFF556B2F)
internal val AppRadioActive = Color(0xFF465A27)
internal val AppRadioAccent = Color(0xFF98A85C)
internal val AppRadioBusy = Color(0xFFB06500)
internal val AppRadioError = Color(0xFF8A2B33)
private val AppGreen = Color(0xFF69DAB1)
internal val AppAmber = Color(0xFFFFCC80)
private val DangerCard = Color(0xFF481D26)
private val LightRed = Color(0xFFFF888F)
internal val BRAND_TAGLINE = "Operational Response Platform"

data class AlertDiagnostics(
    val notificationsEnabled: Boolean,
    val channelReady: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val microphoneGranted: Boolean,
    val notificationPolicyAccess: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val doNotDisturb: Boolean,
    val audioWarning: Boolean,
    val alarmVolume: Int,
    val maximumAlarmVolume: Int,
    val cameraGranted: Boolean = false
) {
    // Camera, battery exemption and policy access are recommendations, not operation gates.
    fun preparationAdjustments(online: Boolean, connectedToTeam: Boolean): Int =
        listOf(!notificationsEnabled, !channelReady, !fullScreenIntentAllowed, !microphoneGranted,
            doNotDisturb, audioWarning, !online || !connectedToTeam).count { it }

    // Alarm volume zero is not, by itself, a failure: the existing service raises it temporarily.
    val needsAttention get() = !notificationsEnabled || !channelReady || !fullScreenIntentAllowed || !microphoneGranted ||
        doNotDisturb || audioWarning
}

@Composable
internal fun AlertaTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = AppRed,
        background = AppInk,
        surface = AppPanel,
        onPrimary = Color.White,
        onSurface = Color.White,
        onBackground = Color.White
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}

@Composable
internal fun BrandTopBar(sectionLabel: String?, operationalReady: Boolean, onMenu: () -> Unit) {
    val coreAlpha = if (operationalReady) {
        val transition = rememberInfiniteTransition(label = "operis-status")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "operis-core-pulse"
        ).value
    } else 1f
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "Abrir menu", tint = Color.White)
        }
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.operis_symbol_structure),
                contentDescription = null,
                modifier = Modifier.matchParentSize()
            )
            Image(
                painter = painterResource(R.drawable.operis_symbol_core),
                contentDescription = if (operationalReady) "OPERIS pronto" else "OPERIS não pronto",
                modifier = Modifier.matchParentSize().alpha(coreAlpha),
                colorFilter = if (operationalReady) null else ColorFilter.tint(Color(0xFF68737D))
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "OPERIS", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onBackground
            )
            Text(BRAND_TAGLINE, color = AppMuted, fontSize = 8.sp, letterSpacing = 0.7.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (sectionLabel != null) {
            Surface(color = AppPanel, shape = RoundedCornerShape(18.dp)) {
                Text(
                    sectionLabel,
                    modifier = Modifier.widthIn(max = 112.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                    color = AppMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding()
            .widthIn(max = 480.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String? = null) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground)
    if (subtitle != null) {
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(AppInk).statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) { Text("‹ VOLTAR", color = AppMuted) }
            }
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun SystemStatus(
    online: Boolean, ready: Boolean, diagnostics: AlertDiagnostics,
    onFix: () -> Unit, onRetry: () -> Unit
) {
    val needsHelp = diagnostics.needsAttention
    val color = when { !online || needsHelp -> AppAmber; ready -> AppGreen; else -> AppMuted }
    val title = when {
        !online -> "SEM CONEXÃO"
        needsHelp -> "ALERTAS PODEM NÃO TOCAR"
        ready -> "SISTEMA PRONTO"
        else -> "VERIFICANDO CONEXÃO"
    }
    val missing = buildList {
        if (!diagnostics.notificationsEnabled) add("notificações")
        if (!diagnostics.channelReady) add("canal de alerta")
        if (!diagnostics.microphoneGranted) add("microfone")
        if (!diagnostics.notificationPolicyAccess) add("acesso ao Não Perturbe")
        if (diagnostics.doNotDisturb) add("Não Perturbe ativo")
        if (!diagnostics.batteryOptimizationIgnored) add("otimização de bateria")
        if (diagnostics.audioWarning) add("restrição de som registrada")
    }
    val description = when {
        !online -> "Conecte-se à internet para enviar e receber alertas."
        needsHelp && missing.isNotEmpty() -> "Falta: ${missing.joinToString("; ")}."
        needsHelp -> "Revise a preparação deste aparelho."
        ready -> "Você receberá alertas"
        else -> "Aguarde a confirmação da conexão com sua equipe."
    }
    Surface(color = AppPanel.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(description, color = AppMuted, fontSize = 12.sp, maxLines = 3)
                }
            }
            if (needsHelp) TextButton(onClick = onFix) { Text("CORRIGIR CONFIGURAÇÃO", color = AppAmber, fontSize = 12.sp) }
            if (online && !ready) TextButton(onClick = onRetry) { Text("TENTAR NOVAMENTE", color = AppMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
internal fun HomeScreen(
    userName: String, selectedTeam: TeamMembership?, online: Boolean, ready: Boolean,
    diagnostics: AlertDiagnostics, busy: Boolean, cooldownSeconds: Int, canSend: Boolean,
    message: String, onAlert: () -> Unit, onTeams: () -> Unit, onFix: () -> Unit,
    onRetry: () -> Unit, onRadio: () -> Unit,
    radioState: PttUiState, onRadioPress: () -> Unit, onRadioRelease: () -> Unit,
    unreadOccurrences: Int = 0, unreadRounds: Int = 0,
    onOccurrences: () -> Unit = {}, onRounds: () -> Unit = {}
) {
    ScreenColumn {
        SystemStatus(online, ready, diagnostics, onFix, onRetry)
        Spacer(Modifier.height(24.dp))
        // Botão circular: destaque máximo sem ocupar metade da tela.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(226.dp)) {
            Surface(shape = CircleShape, color = AppRed.copy(alpha = 0.14f), modifier = Modifier.fillMaxSize()) {}
            Button(
                onClick = onAlert,
                enabled = canSend && online && ready && !busy && cooldownSeconds == 0,
                modifier = Modifier.fillMaxSize().padding(13.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppRed, contentColor = Color.White,
                    disabledContainerColor = AppPanel, disabledContentColor = AppMuted)
            ) {
                Text(
                    when { busy -> "ENVIANDO…"; cooldownSeconds > 0 -> "AGUARDE\n$cooldownSeconds s"; else -> "ACIONAR\nALERTA" },
                    fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                busy -> "Enviando alerta…"
                cooldownSeconds > 0 -> "Aguarde antes de um novo alerta"
                canSend && selectedTeam != null -> "Pressione para alertar ${selectedTeam.displayName}"
                else -> "Nenhuma equipe selecionada para alertar"
            },
            color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(26.dp))
        // Equipe selecionada: clara, porém discreta. Define o destino de um novo alerta.
        Surface(onClick = onTeams, color = AppPanel, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("EQUIPE SELECIONADA", color = AppMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedTeam?.displayName ?: "Nenhuma equipe", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("▾", color = AppMuted, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("IDENTIFICAÇÃO DESTE APARELHO", color = AppMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(4.dp))
            Text(userName.takeIf { it.isNotBlank() } ?: "Sem identificação", fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(onClick = onOccurrences, color = AppPanel, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("OCORRÊNCIAS", color = AppMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(unreadOccurrences.takeIf { it > 0 }?.let { "NÃO LIDOS: ${Badges.label(it)}" } ?: "TODOS EM DIA",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (unreadOccurrences > 0) AppRed else AppMuted)
                }
            }
            Surface(onClick = onRounds, color = AppPanel, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RONDAS", color = AppMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(unreadRounds.takeIf { it > 0 }?.let { "NÃO LIDOS: ${Badges.label(it)}" } ?: "TODOS EM DIA",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (unreadRounds > 0) AppRed else AppMuted)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        val homeRadioColor = when {
            radioState.transmitting -> AppRadioActive
            radioState.busy -> AppRadioBusy
            radioState.status == PttConnectionStatus.CONNECTED -> AppRadio
            else -> AppPanel
        }
        val homeRadioLabel = when {
            radioState.transmitting -> "TRANSMITINDO..."
            !radioState.speakingName.isNullOrBlank() -> "RECEBENDO " + radioState.speakingName
            radioState.busy -> "CANAL OCUPADO"
            radioState.status == PttConnectionStatus.CONNECTED -> "SEGURE PARA FALAR"
            radioState.status == PttConnectionStatus.DISCONNECTED -> "RÁDIO RECONECTANDO"
            else -> "RÁDIO CONECTANDO"
        }
        Surface(color = homeRadioColor, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().pointerInput(selectedTeam?.teamId) {
                var holdActivated = false
                detectTapGestures(
                    onPress = {
                        holdActivated = false
                        coroutineScope {
                            val arm = launch {
                                delay(300)
                                if (selectedTeam != null) {
                                    holdActivated = true
                                    onRadioPress()
                                }
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                arm.cancel()
                                if (holdActivated) onRadioRelease()
                            }
                        }
                    },
                    onTap = { if (!holdActivated) onRadio() }
                )
            }, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_radio_custom), contentDescription = "Rádio",
                    colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(4.dp))
                Text(homeRadioLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Toque para abrir | segure para falar", color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun SchedulesScreen(onBack: () -> Unit) {
    Page("ESCALA / PLANTÕES", onBack = onBack) {
        Surface(color = AppPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Este recurso será disponibilizado em uma próxima versão.",
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun ReportsScreen(onGenerate: () -> Unit) {
    ScreenColumn {
        SectionHeader("RELATÓRIOS", "Resumo das ocorrências e do plantão.")
        Surface(color = AppPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Relatórios em preparação.", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Aqui ficarão os relatórios do plantão, com o resumo das ocorrências do período e a situação final da equipe.",
                    color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onGenerate, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Text("GERAR RELATÓRIO", color = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    diagnostics: AlertDiagnostics, online: Boolean, ready: Boolean, lastAlert: String?,
    onNotifications: () -> Unit, onFullScreen: () -> Unit, onSound: () -> Unit, onDoNotDisturb: () -> Unit,
    onMicrophone: () -> Unit, onCamera: () -> Unit, onDndMode: () -> Unit,
    onNetwork: () -> Unit, onBattery: () -> Unit,
    onTest: () -> Unit, onRetry: () -> Unit,
    latestRelease: ReleasePolicy?, releaseNote: String, checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit, onShowUpdate: () -> Unit, onShareRelease: () -> Unit,
    onStatus: () -> Unit, statusLabel: String,
    accountEmail: String?, onProtectAccount: () -> Unit, onLogout: () -> Unit
) {
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        SectionHeader("CONFIGURAÇÕES")
        TextButton(onClick = onStatus) { Text("MEU STATUS: $statusLabel") }
        SettingsCard("Sobre") {
            Text("Versão instalada: "+BuildConfig.VERSION_NAME)
            val release = latestRelease
            if (release != null && release.status == ReleasePolicyStatus.PUBLISHED) {
                Spacer(Modifier.height(6.dp))
                Text("Última versão disponível: ${release.latestVersionName}", color = AppMuted)
                if (release.latestVersionCode > BuildConfig.VERSION_CODE) {
                    Spacer(Modifier.height(4.dp))
                    Text("Nova versão disponível", color = AppGreen)
                    release.releaseNotes.forEach { Text("• $it", color = AppMuted) }
                    release.apkSize?.let { Text("Tamanho: " + UpdateFlow.formatApkSize(it), color = AppMuted) }
                    Button(onClick = onShowUpdate, enabled = release.canDownload()) { Text("ATUALIZAR") }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
                Text(if (checkingUpdate) "VERIFICANDO..." else "PROCURAR ATUALIZAÇÃO")
            }
            TextButton(
                onClick = onShareRelease,
                enabled = release != null && release.canDownload()
            ) { Text("COMPARTILHAR ATUALIZAÇÃO") }
            if (release != null && !release.canDownload())
                Text("Link de atualização ainda não publicado.", color = AppMuted)
            if (releaseNote.isNotBlank()) Text(releaseNote, color = AppMuted)
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard("Conta") {
            if (accountEmail != null) {
                Text("Conta protegida", color = AppGreen)
                Text(accountEmail, color = AppMuted)
            } else {
                Text("Proteja sua conta para poder recuperá-la após reinstalar o aplicativo.", color = AppMuted)
                TextButton(onClick = onProtectAccount) { Text("PROTEGER CONTA", color = Color.White) }
            }
            TextButton(onClick = onLogout) { Text("SAIR DESTE APARELHO", color = Color.White) }
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard("Preparação do aparelho") {
            val adjustments = diagnostics.preparationAdjustments(online, ready)
            Text(if (adjustments == 0) "APARELHO PRONTO PARA OPERAÇÃO" else "$adjustments AJUSTES NECESSÁRIOS",
                color = if (adjustments == 0) AppGreen else AppAmber, fontWeight = FontWeight.Bold)
            Text("Toque nos ajustes pendentes. Recomendações não impedem o uso do aplicativo.", color = AppMuted)
            if (!online) PreparationAction("Internet", "Abrir conexões do aparelho", false, onNetwork)
            else if (!ready) PreparationAction("Conexão com a equipe", "Verificar conexão", false, onRetry)
            PreparationAction("Notificações", if (diagnostics.notificationsEnabled) "Permitidas" else "Autorizar", diagnostics.notificationsEnabled, onNotifications)
            PreparationAction("Alertas em destaque", if (diagnostics.channelReady) "Configurados" else "Configurar", diagnostics.channelReady, onNotifications)
            PreparationAction("Tela bloqueada", if (diagnostics.fullScreenIntentAllowed) "Alerta em tela cheia permitido" else "Autorizar alerta em tela cheia", diagnostics.fullScreenIntentAllowed, onFullScreen)
            PreparationAction("Microfone", if (diagnostics.microphoneGranted) "Permitido" else "Autorizar", diagnostics.microphoneGranted, onMicrophone)
            if (diagnostics.doNotDisturb) PreparationAction("Não Perturbe ativo", "Revisar modo de som", false, onDndMode)
            if (diagnostics.audioWarning) PreparationAction("Restrição de reprodução", "Revisar som e testar a sirene", false, onSound)
            Text("RECOMENDAÇÕES", color = AppMuted, fontSize = 12.sp)
            PreparationAction("Câmera", if (diagnostics.cameraGranted) "Permitida" else "Autorizar para tirar fotos (opcional)", diagnostics.cameraGranted, onCamera)
            PreparationAction("Não Perturbe", if (diagnostics.notificationPolicyAccess) "Acesso autorizado" else "Autorizar acesso (recomendado)", diagnostics.notificationPolicyAccess, onDoNotDisturb)
            PreparationAction("Bateria", if (diagnostics.batteryOptimizationIgnored) "Sem restrição" else "Revisar otimização (recomendado)", diagnostics.batteryOptimizationIgnored, onBattery)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSound) { Text("CONFIGURAÇÃO DE SOM", color = Color.White) }
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard("Som da sirene") {
            Text("Durante um alerta, o aplicativo pode elevar temporariamente o volume de alarme ao máximo. Ao silenciar ou encerrar normalmente, o volume anterior é restaurado.", color = AppMuted)
            Spacer(Modifier.height(10.dp))
            Text("O funcionamento no modo silencioso foi validado nos aparelhos testados. O Não Perturbe e restrições do aparelho ainda podem afetar os alertas.", color = AppMuted)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onTest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("TESTAR SIRENE") }
            Spacer(Modifier.height(8.dp))
            Text("Toca somente neste celular. O volume de alarme pode ser elevado durante o teste.",
                fontSize = 12.sp, color = AppMuted)
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard("Diagnóstico do aparelho") {
            Diagnostic("Status do aparelho", if (diagnostics.preparationAdjustments(online, ready) == 0) "App pronto" else "Revisar preparação")
            Diagnostic("Internet", if (online) "Conectada" else "Sem conexão")
            Diagnostic("Conexão com a equipe", if (ready && online) "Confirmada" else "Aguardando confirmação")
            TextButton(onClick = { showTechnical = !showTechnical }) {
                Text(if (showTechnical) "OCULTAR INFORMAÇÕES TÉCNICAS" else "INFORMAÇÕES TÉCNICAS", color = AppMuted)
            }
            if (showTechnical) {
                Diagnostic("Notificações", if (diagnostics.notificationsEnabled) "Permitidas" else "Bloqueadas")
                Diagnostic("Alertas em destaque", if (diagnostics.channelReady) "Configurados" else "Revisar configuração")
                Diagnostic("Tela bloqueada", if (diagnostics.fullScreenIntentAllowed) "Alerta em tela cheia permitido" else "Permissão pendente")
                Diagnostic("Não Perturbe", if (diagnostics.doNotDisturb) "ATIVO — desative para prontidão" else "Desativado")
                Diagnostic("Microfone", if (diagnostics.microphoneGranted) "Permitido" else "Pendente")
                Diagnostic("Acesso ao Não Perturbe", if (diagnostics.notificationPolicyAccess) "Autorizado" else "Pendente")
                Diagnostic("Bateria", if (diagnostics.batteryOptimizationIgnored) "Sem restrição" else "Otimização ativa")
                Diagnostic("Volume de alarme", "${diagnostics.alarmVolume} de ${diagnostics.maximumAlarmVolume}")
                Diagnostic("Câmera", if (diagnostics.cameraGranted) "Permitida" else "Não autorizada (opcional)")
                val diagAuthUid = FirebaseAuth.getInstance().currentUser?.uid
                val diagLocalDeviceId = Local.deviceId
                Diagnostic("Firebase Auth UID", diagAuthUid ?: "sem sessão")
                Diagnostic("Local deviceId", diagLocalDeviceId.ifBlank { "vazio" })
                Diagnostic("Installation ID", Local.installationId())
                Diagnostic("Identidade consistente", if (!diagAuthUid.isNullOrBlank() && diagAuthUid == Local.uid) "SIM" else "NÃO")
            }
            if (diagnostics.audioWarning) Text("Uma restrição de reprodução foi registrada. Confira as configurações e teste a sirene.", color = AppAmber)
            TextButton(onClick = onRetry) { Text("VERIFICAR CONEXÃO", color = Color.White) }
            if (lastAlert != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = AppMuted.copy(alpha = 0.2f))
                Text("Último alerta recebido", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(lastAlert, color = AppMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun ProtectAccountDialog(
    busy: Boolean, error: String, onDismiss: () -> Unit, onProtect: (String, String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("PROTEGER CONTA") },
        text = {
            Column {
                Text("Vincule um e-mail e senha à conta atual. Suas equipes permanecerão nesta mesma conta.", color = AppMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Senha") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = AppAmber)
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("CANCELAR") } },
        confirmButton = {
            Button(enabled = !busy, onClick = { onProtect(email, password) }) {
                Text(if (busy) "PROTEGENDO..." else "PROTEGER")
            }
        }
    )
}

@Composable
private fun PreparationAction(label: String, value: String, ok: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White)
            Text(value, color = if (ok) AppGreen else AppAmber, fontSize = 12.sp)
        }
        Text(if (ok) "OK" else ">", color = if (ok) AppGreen else AppAmber, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Diagnostic(label: String, value: String) {
    Column(Modifier.padding(vertical = 7.dp)) {
        Text(label, color = AppMuted, fontSize = 12.sp)
        Text(value, fontSize = 15.sp)
    }
}

@Composable
internal fun EmergencyScreen(
    alert: Panic, teamName: String, audioWarning: Boolean,
    formattedTime: String, onSilence: () -> Unit, onActivate: () -> Unit
) {
    Page("OPERIS") {
        Surface(color = DangerCard, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("!", fontSize = 56.sp, fontWeight = FontWeight.Black, color = LightRed)
                Text("ALERTA DE\nEMERGÊNCIA", fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Text("Equipe: $teamName", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Text("Acionado por", color = AppMuted)
                Text(alert.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(formattedTime, color = AppMuted, fontSize = 13.sp)
                Spacer(Modifier.height(28.dp))
                Button(onClick = onSilence, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AppInk),
                    shape = RoundedCornerShape(16.dp)) {
                    Text("SILENCIAR", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text("Silencia somente este aparelho", fontSize = 12.sp, color = AppMuted, textAlign = TextAlign.Center)
                if (audioWarning) {
                    Spacer(Modifier.height(12.dp))
                    Text("A reprodução automática foi restringida.", color = AppAmber, textAlign = TextAlign.Center)
                    TextButton(onClick = onActivate) { Text("ATIVAR SIRENE", color = Color.White) }
                }
            }
        }
    }
}

@Composable
internal fun AppDrawer(currentSection: String, occurrences: Int = 0, rounds: Int = 0,
    operationalReady: Boolean, onNavigate: (String) -> Unit) {
    val drawerCoreAlpha = if (operationalReady) {
        val transition = rememberInfiniteTransition(label = "operis-drawer-status")
        transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "drawer-core-pulse").value
    } else 1f
    ModalDrawerSheet(drawerContainerColor = AppPanel) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.operis_symbol_structure), contentDescription = null, modifier = Modifier.matchParentSize())
                    Image(
                        painterResource(R.drawable.operis_symbol_core),
                        contentDescription = if (operationalReady) "OPERIS pronto" else "OPERIS não pronto",
                        modifier = Modifier.matchParentSize().alpha(drawerCoreAlpha),
                        colorFilter = if (operationalReady) null else ColorFilter.tint(Color(0xFF68737D))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(BRAND_TAGLINE, color = AppMuted, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
            }
            HorizontalDivider(color = AppMuted.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            DrawerItem("home", "Início", currentSection, onNavigate) { Icon(Icons.Filled.Home, contentDescription = null) }
            DrawerItem("radio", "Rádio", currentSection, onNavigate) {
                Image(painterResource(R.drawable.ic_radio_custom), contentDescription = "Rádio",
                    modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
            }
            DrawerItem("occurrences", "Ocorrências", currentSection, onNavigate,
                badge = Badges.label(occurrences)?.let { label -> { Badge { Text(label) } } }) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
            }
            DrawerItem("rounds", "Rondas", currentSection, onNavigate,
                badge = Badges.label(rounds)?.let { label -> { Badge { Text(label) } } }) {
                Image(painterResource(R.drawable.ic_rounds_custom), contentDescription = "Rondas",
                    modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
            }
            DrawerItem("teams", "Equipes", currentSection, onNavigate) {
                Image(painterResource(R.drawable.ic_teams_custom), contentDescription = "Equipes",
                    modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
            }
            DrawerItem("emergency", "Emergência", currentSection, onNavigate) {
                Icon(Icons.Filled.Phone, contentDescription = null)
            }
            DrawerItem("schedules", "Escala / Plantões", currentSection, onNavigate) {
                Image(painterResource(R.drawable.ic_schedules_custom), contentDescription = "Escala / Plantões",
                    modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
            }
            DrawerItem("settings", "Configurações", currentSection, onNavigate) { Icon(Icons.Filled.Settings, contentDescription = null) }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = AppMuted.copy(alpha = 0.2f))
            Text("OPERIS • v"+BuildConfig.VERSION_NAME, color = AppMuted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun DrawerItem(id: String, label: String, current: String, onNavigate: (String) -> Unit,
    badge: (@Composable () -> Unit)? = null, icon: @Composable () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = current == id,
        onClick = { onNavigate(id) },
        icon = icon,
        badge = badge,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = AppRed.copy(alpha = 0.18f),
            selectedIconColor = Color.White,
            selectedTextColor = Color.White,
            unselectedIconColor = AppMuted,
            unselectedTextColor = AppMuted
        ),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
