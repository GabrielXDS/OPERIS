package br.com.alertaequipe

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        recoverAlert(intent)
        RecordRoute.stage(this, intent)
        setContent { AlertaTheme { AppScreen(startWithSplash = savedInstanceState == null) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recoverAlert(intent)
        RecordRoute.stage(this, intent)
    }

    // Existing receive/recovery contract is unchanged.
    private fun recoverAlert(intent: Intent) {
        Panic.decode(intent.getStringExtra("panic"))?.let {
            if (System.currentTimeMillis() - it.time in 0..119_999 &&
                Panic.decode(Local.prefs.getString("active", null))?.id == it.id) Local.current.value = it
        }
    }

    private fun connected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun diagnostics(): AlertDiagnostics {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AudioManager::class.java)
        val channel = nm.getNotificationChannel(Alerts.CHANNEL)
        val pm = getSystemService(PowerManager::class.java)
        return AlertDiagnostics(
            notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            channelReady = (channel?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH && channel?.sound != null,
            fullScreenIntentAllowed = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationPolicyAccess = nm.isNotificationPolicyAccessGranted,
            batteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(packageName),
            doNotDisturb = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
            audioWarning = Local.prefs.getBoolean("audioWarning", false),
            alarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM),
            maximumAlarmVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun openNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            openRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Alerts.CHANNEL))
        }
    }


    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
                .onFailure { openNotifications() }
        } else openNotifications()
    }

    private fun openRuntimePermission(name: String) {
        val requestedKey = "preparation.requested.$name"
        val deniedPermanently = Local.prefs.getBoolean(requestedKey, false) && !shouldShowRequestPermissionRationale(name)
        if (checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED && !deniedPermanently) {
            Local.prefs.edit().putBoolean(requestedKey, true).apply()
            permission.launch(name)
        } else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openMicrophonePermission() = openRuntimePermission(Manifest.permission.RECORD_AUDIO)

    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
    @Composable
    private fun AppScreen(startWithSplash: Boolean = true) {
        val scope = rememberCoroutineScope()
        val teamPreferences = remember { TeamPreferences(this@MainActivity) }
        var splashVisible by rememberSaveable { mutableStateOf(startWithSplash && SplashController.shouldShow(startWithSplash)) }
        var releasePolicy by remember { mutableStateOf(teamPreferences.releasePolicy()) }
        var releaseStatus by remember { mutableStateOf(Operational.classifyRelease(BuildConfig.VERSION_CODE, releasePolicy, System.currentTimeMillis())) }
        var releaseNote by remember { mutableStateOf("") }
        var checkingUpdate by remember { mutableStateOf(false) }
        var optionalDismissed by rememberSaveable { mutableStateOf(false) }
        var downloading by rememberSaveable { mutableStateOf(false) }
        var downloadId by rememberSaveable { mutableStateOf<Long?>(null) }
        var downloadName by rememberSaveable { mutableStateOf("") }
        var downloadHash by rememberSaveable { mutableStateOf<String?>(null) }
        var downloadVersion by rememberSaveable { mutableStateOf(0) }
        var apkPath by rememberSaveable { mutableStateOf<String?>(null) }
        var validatingUpdate by remember { mutableStateOf(false) }
        var updateError by remember { mutableStateOf<String?>(null) }
        var identified by remember { mutableStateOf(Local.registered && Local.operationalFunction.isNotBlank()) }
        var restoringSession by remember { mutableStateOf(!Local.registered && FirebaseAuth.getInstance().currentUser != null) }
        var memberships by remember { mutableStateOf(teamPreferences.memberships()) }
        var myRequests by remember { mutableStateOf<List<MyRequest>>(emptyList()) }
        var selectedTeamId by remember { mutableStateOf(teamPreferences.selectedTeam(memberships)?.teamId) }
        val selectedTeam = TeamSelection.resolve(memberships,selectedTeamId)
        var screen by rememberSaveable { mutableStateOf(if(memberships.isEmpty()) "teams" else "home") }
        val operationsTeamId = selectedTeam?.teamId
        fun canMutate(authorUid: String): Boolean =
            authorUid == Local.uid || selectedTeam?.role == "owner" || selectedTeam?.role == "admin"
        var incidents by remember(operationsTeamId) { mutableStateOf<List<Incident>>(emptyList()) }
        var incidentCursor by remember(operationsTeamId) { mutableStateOf<String?>(null) }
        var incidentDeletedOnly by remember(operationsTeamId) { mutableStateOf(false) }
        var editingIncident by remember { mutableStateOf<Incident?>(null) }
        var deletingIncident by remember { mutableStateOf<Incident?>(null) }
        var incidentId by rememberSaveable(operationsTeamId) { mutableStateOf<String?>(null) }
        var incidentDetail by remember(operationsTeamId) { mutableStateOf<Incident?>(null) }
        var incidentError by remember(operationsTeamId) { mutableStateOf("") }
        var incidentLoading by remember(operationsTeamId) { mutableStateOf(false) }
        var incidentSaving by remember { mutableStateOf(false) }
        var incidentRefresh by remember { mutableIntStateOf(0) }
        val incidentUpload = rememberSaveable(operationsTeamId, saver=AttachmentUploadState.saver) { AttachmentUploadState() }
        val incidentForms = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        var rounds by remember(operationsTeamId) { mutableStateOf<List<RoundReport>>(emptyList()) }
        var roundCursor by remember(operationsTeamId) { mutableStateOf<String?>(null) }
        var reportId by rememberSaveable(operationsTeamId) { mutableStateOf<String?>(null) }
        var roundDetail by remember(operationsTeamId) { mutableStateOf<RoundReport?>(null) }
        var roundError by remember(operationsTeamId) { mutableStateOf("") }
        var roundLoading by remember(operationsTeamId) { mutableStateOf(false) }
        var roundSaving by remember { mutableStateOf(false) }
        var roundRefresh by remember { mutableIntStateOf(0) }
        val roundUpload = rememberSaveable(operationsTeamId, saver=AttachmentUploadState.saver) { AttachmentUploadState() }
        val roundForms = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        var roundDeletedOnly by remember(operationsTeamId) { mutableStateOf(false) }
        var editingRound by remember { mutableStateOf<RoundReport?>(null) }
        var deletingRound by remember { mutableStateOf<RoundReport?>(null) }
        var shareText by remember { mutableStateOf<String?>(null) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var createdTeam by rememberSaveable { mutableStateOf(false) }
        var online by remember { mutableStateOf(connected()) }
        var ready by remember { mutableStateOf(false) }
        var deviceDiagnostics by remember { mutableStateOf(diagnostics()) }
        var message by remember { mutableStateOf("") }
        var rosterMessage by remember { mutableStateOf("") }
        var emergencySaving by remember { mutableStateOf(false) }
        var emergencyMessage by remember { mutableStateOf("") }
        var activeShift by remember(operationsTeamId) { mutableStateOf<Shift?>(null) }
        var activeShiftLoaded by remember(operationsTeamId) { mutableStateOf(false) }
        var shiftHistory by remember(operationsTeamId) { mutableStateOf<List<Shift>>(emptyList()) }
        var shiftReportId by rememberSaveable(operationsTeamId) { mutableStateOf<String?>(null) }
        var shiftReportKind by rememberSaveable(operationsTeamId) { mutableStateOf(ShiftReportKind.BRIGADA) }
        var shiftReport by remember(operationsTeamId) { mutableStateOf<ShiftReport?>(null) }
        var shiftRefresh by remember { mutableIntStateOf(0) }
        var shiftBusy by remember { mutableStateOf(false) }
        var shiftMessage by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var refreshing by remember { mutableStateOf(false) }
        var confirmTeamId by rememberSaveable { mutableStateOf<String?>(null) }
        var unavailableFeature by rememberSaveable { mutableStateOf<String?>(null) }
        var cooldown by remember { mutableLongStateOf(0L) }
        var seconds by remember { mutableIntStateOf(0) }
        var retryConnection by remember { mutableIntStateOf(0) }
        var diagnosticsRevision by remember { mutableIntStateOf(0) }
        var profileReady by remember { mutableStateOf(false) }
        var refreshRoster by remember { mutableIntStateOf(0) }
        var details by remember { mutableStateOf<Map<String,TeamDetails>>(emptyMap()) }
        var preview by remember { mutableStateOf<InvitePreview?>(null) }
        var availability by remember { mutableStateOf("available") }
        var pauseReason by remember { mutableStateOf<String?>(null) }
        var showStatus by rememberSaveable { mutableStateOf(false) }
        var releasePending by remember { mutableStateOf(Operational.shouldShowRelease(BuildConfig.VERSION_CODE, teamPreferences.releaseSeen())) }
        var inviteAction by rememberSaveable { mutableStateOf<String?>(null) }
        var showProtectAccount by rememberSaveable { mutableStateOf(false) }
        var accountProtectionError by rememberSaveable { mutableStateOf("") }
        var protectedAccountEmail by remember {
            mutableStateOf(FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous }?.email)
        }
        val panic by Local.current.collectAsState()
        val radioState by PttRadioRuntime.state.collectAsState()
        val radioOnDuty = activeShift?.status == "ACTIVE" &&
            activeShift?.participants?.any { it.uid == Local.uid && it.endedAt == null } == true
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val recordsTick by Records.updated.collectAsState()
        var unreadOcc by remember(operationsTeamId) { mutableIntStateOf(Records.occurrences(operationsTeamId.orEmpty())) }
        var unreadRound by remember(operationsTeamId) { mutableIntStateOf(Records.rounds(operationsTeamId.orEmpty())) }
        val snackbarHostState = remember { SnackbarHostState() }

        suspend fun refreshMemberships() {
            val account=Backend.teams()
            memberships=account.teams
            myRequests=account.requests
            teamPreferences.saveMemberships(memberships)
            selectedTeamId=teamPreferences.selectedTeam(memberships)?.teamId
            availability=account.availability
            pauseReason=account.pauseReason
        }
        LaunchedEffect(Unit) {
            if (restoringSession) {
                try {
                    if (Backend.migrateExistingSession()) {
                        identified = true
                        refreshMemberships()
                        selectedTeamId = teamPreferences.selectedTeam(memberships)?.teamId
                        screen = if (memberships.isEmpty()) "teams" else "home"
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    message = Backend.error(e)
                } finally {
                    restoringSession = false
                }
            }
        }
        LaunchedEffect(identified, selectedTeam?.teamId, selectedTeam?.displayName, selectedTeam?.operationalFunction, radioOnDuty, activeShiftLoaded) {
            val radioTeam = selectedTeam
            val dutyEndAt = activeShift?.officialEndAt ?: activeShift?.startedAt?.plus(12L * 60L * 60L * 1000L)
            Log.d("OperisPtt", "radioEffect identified=$identified team=${radioTeam?.teamId} shiftLoaded=$activeShiftLoaded onDuty=$radioOnDuty")
            when {
                !identified || radioTeam == null -> PttRadioService.stop(this@MainActivity)
                !activeShiftLoaded -> Log.d("OperisPtt", "radioEffect aguardando confirmação do plantão; serviço preservado")
                radioOnDuty -> {
                    PttRadioService.setDuty(this@MainActivity, radioTeam.teamId, true, dutyEndAt)
                    val operationalFunction = radioTeam.operationalFunction.ifBlank { Local.operationalFunction }
                    PttRadioService.start(
                        this@MainActivity,
                        radioTeam.teamId,
                        radioTeam.displayName,
                        operationalFunction,
                        PttChannel.forFunction(operationalFunction)
                    )
                }
                else -> {
                    PttRadioService.setDuty(this@MainActivity, radioTeam.teamId, false)
                    PttRadioService.stop(this@MainActivity)
                }
            }
        }
        // "CORRIGIR CONFIGURAÇÃO" leva à ação correta para o que está faltando, não a uma tela genérica.
        fun fixHealth() {
            val d = deviceDiagnostics
            when {
                !d.notificationsEnabled || !d.channelReady -> openNotifications()
                !d.fullScreenIntentAllowed -> openFullScreenIntentSettings()
                !d.microphoneGranted -> permission.launch(Manifest.permission.RECORD_AUDIO)
                !d.notificationPolicyAccess || d.doNotDisturb -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                !d.batteryOptimizationIgnored -> openBatterySettings()
                d.audioWarning -> startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                else -> { screen="settings"; message="" }
            }
        }
        fun perform(action:suspend ()->Unit) {
            if(busy)return
            busy=true;message=""
            scope.launch {
                try {action()} catch(e:Exception) {
                    if(e is kotlinx.coroutines.CancellationException)throw e
                    message=Backend.error(e)
                } finally {busy=false}
            }
        }
        suspend fun afterMembership(id:String,created:Boolean) {
            refreshMemberships()
            if(teamPreferences.select(id,memberships))selectedTeamId=id
            detailId=id;createdTeam=created;screen="details"
            preview=null;refreshRoster++
            if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val updateUi = UpdateUiState(downloading || validatingUpdate, apkPath != null, apkPath != null, updateError, validatingUpdate)

        suspend fun refreshRelease(userInitiated: Boolean = false) {
            if (checkingUpdate) return
            checkingUpdate = true
            try {
                val fetched = Backend.getAndroidRelease()
                val stamped = fetched.copy(cachedAtMs = System.currentTimeMillis())
                releasePolicy = stamped
                teamPreferences.saveReleasePolicy(stamped)
                releaseStatus = Operational.classifyRelease(BuildConfig.VERSION_CODE, stamped)
                if (userInitiated) {
                    releaseNote = UpdateFlow.checkMessage(releaseStatus, stamped.latestVersionName)
                    optionalDismissed = false
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (userInitiated) releaseNote = UpdateFlow.checkMessage(ReleaseStatus.UNKNOWN, "")
            } finally { checkingUpdate = false }
        }
        suspend fun validCandidate(file: File, hash: String?, version: Int): Boolean = withContext(Dispatchers.IO) {
            UpdateFlow.verified(hash, file) && UpdateFlow.inspect(this@MainActivity, file)?.let { i ->
                UpdateFlow.installable(i.packageName, i.versionCode, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE, version)
            } == true
        }
        fun onDownloadComplete(id: Long) {
            if (id != downloadId || validatingUpdate) return
            validatingUpdate = true
            scope.launch {
                try {
                    val file = UpdateFlow.downloadedFile(this@MainActivity, downloadName)
                    if (UpdateFlow.queryStatus(this@MainActivity, id) == DownloadManager.STATUS_SUCCESSFUL &&
                        validCandidate(file, downloadHash, downloadVersion)) {
                        apkPath = file.absolutePath
                        updateError = null
                    } else {
                        file.delete(); apkPath = null
                        updateError = "O download falhou ou o pacote não corresponde à atualização. Tente novamente."
                    }
                    downloadId = null
                    downloading = false
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    updateError = "Não foi possível validar o download. Tente novamente."
                    downloadId = null; downloading = false; apkPath = null
                } finally { validatingUpdate = false }
            }
        }
        fun updateNow(useFallback: Boolean = false) {
            if (downloading || validatingUpdate) return
            val policy = releasePolicy ?: return
            updateError = null
            if (!policy.canDownload()) { updateError = "O pacote de atualização ainda não foi publicado."; return }
            val existing = apkPath?.let(::File)
            if (existing != null) {
                validatingUpdate = true
                scope.launch {
                    try {
                        if (!validCandidate(existing, policy.sha256, policy.latestVersionCode)) {
                            existing.delete(); apkPath = null
                            updateError = "O pacote não corresponde à versão disponível. Toque em ATUALIZAR para baixar novamente."
                        } else if (!UpdateFlow.canInstall(this@MainActivity)) {
                            updateError = if (UpdateFlow.openUnknownSources(this@MainActivity))
                                "Autorize esta fonte e volte ao OPERIS para tocar em INSTALAR."
                            else "Abra as configurações do Android e autorize o OPERIS a instalar aplicativos."
                        } else {
                            val started = if (useFallback) UpdateFlow.fallback(this@MainActivity, existing)
                                else withContext(Dispatchers.IO) { UpdateFlow.install(this@MainActivity, existing) }
                            updateError = if (started) "Conclua a confirmação do Android. Se ela não aparecer, use ABRIR INSTALADOR ALTERNATIVO."
                                else "Não foi possível abrir a instalação. Use ABRIR INSTALADOR ALTERNATIVO ou tente novamente."
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        updateError = "Não foi possível iniciar a instalação. Tente o instalador alternativo."
                    } finally { validatingUpdate = false }
                }
                return
            }
            runCatching {
                downloadName = policy.latestVersionName
                downloadHash = policy.sha256
                downloadVersion = policy.latestVersionCode
                downloadId = UpdateFlow.enqueue(this@MainActivity, requireNotNull(policy.downloadUrl), downloadName)
                downloading = true
            }.onFailure { updateError = "Não foi possível iniciar o download. Verifique a conexão e o armazenamento e tente novamente." }
        }
        LaunchedEffect(identified, online) {
            if (!identified) return@LaunchedEffect
            if (online) refreshRelease()
            else if (releaseStatus == ReleaseStatus.UNKNOWN) releaseNote = "Sem conexão: não foi possível verificar atualizações."
        }

        DownloadCompletionMonitor(downloadId, onComplete = ::onDownloadComplete)

        LaunchedEffect(Unit) {
            while(true) {
                online=connected()
                val am = getSystemService(AudioManager::class.java)
                if (Local.reconcileAudioWarning(am.getStreamVolume(AudioManager.STREAM_ALARM))) {
                    diagnosticsRevision++
                    deviceDiagnostics=diagnostics()
                } else {
                    deviceDiagnostics=diagnostics()
                }
                seconds=((cooldown-System.currentTimeMillis()+999)/1000).coerceAtLeast(0).toInt()
                Local.current.value?.let {
                    if(System.currentTimeMillis()-it.time>120_000) Alerts.silence(this@MainActivity)
                }
                delay(500)
            }
        }
        // One foreground loop, cancelled at STOP. WorkManager remains token maintenance only.
        LaunchedEffect(identified, online, retryConnection) {
            if (identified && online && !profileReady) {
                try {
                    Backend.ensureProfile()
                    profileReady = true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    message = Backend.error(e)
                }
            }
        }
        LaunchedEffect(identified,online,retryConnection,diagnosticsRevision) {
            ready=false
            if(identified && online) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while(true) {
                    try {
                        Backend.sync(diagnostics())
                        refreshMemberships()
                        ready=true
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        ready=false;message=Backend.error(e)
                    }
                    delay(60_000)
                }
            }
        }
        LaunchedEffect(screen,detailId,online,identified,refreshRoster) {
            if(!identified || !online || (screen!="teams" && screen!="details" && screen!="emergency"))return@LaunchedEffect
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while(true) {
                    refreshing=true
                    try {
                        refreshMemberships()
                        run {
                            val ids=when(screen) {
                                "details" -> listOfNotNull(detailId)
                                "emergency" -> listOfNotNull(selectedTeamId)
                                else -> memberships.map{it.teamId}
                            }
                            val updated=details.toMutableMap()
                            for(id in ids)updated[id]=Backend.details(id)
                            details=updated
                            rosterMessage=""
                        }
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        rosterMessage=Backend.error(e)+" Os dados exibidos podem estar desatualizados."
                    } finally {refreshing=false}
                    delay(30_000)
                }
            }
        }
        LaunchedEffect(screen, operationsTeamId, incidentId, incidentRefresh, incidentDeletedOnly) {
            incidentError = ""
            if (screen != "occurrences" && screen != "occurrence_detail") return@LaunchedEffect
            val teamId = operationsTeamId ?: return@LaunchedEffect
            incidentLoading = true
            try {
                if (screen == "occurrences") {
                    val page = Backend.listIncidents(teamId, deletedOnly = incidentDeletedOnly)
                    incidents = page.records
                    incidentCursor = page.nextCursor
                    Records.markSeen(this@MainActivity, teamId, Records.MODULE_OCCURRENCE,
                        page.records.maxOfOrNull { it.createdAt } ?: 0L)
                    unreadOcc = 0
                } else {
                    incidentDetail = null
                    incidentId?.let { incidentDetail = Backend.getIncidentDetails(teamId, it) }
                    Records.markSeen(this@MainActivity, teamId, Records.MODULE_OCCURRENCE,
                        incidentDetail?.createdAt ?: 0L)
                    unreadOcc = 0
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                incidentError = Backend.error(e)
            } finally { incidentLoading = false }
        }
        LaunchedEffect(screen, operationsTeamId, reportId, roundRefresh, roundDeletedOnly) {
            roundError = ""
            if (screen != "rounds" && screen != "round_detail") return@LaunchedEffect
            val teamId = operationsTeamId ?: return@LaunchedEffect
            roundLoading = true
            try {
                if (screen == "rounds") {
                    val page = Backend.listRoundReports(teamId, deletedOnly = roundDeletedOnly)
                    rounds = page.records
                    roundCursor = page.nextCursor
                    Records.markSeen(this@MainActivity, teamId, Records.MODULE_ROUND,
                        page.records.maxOfOrNull { it.createdAt } ?: 0L)
                    unreadRound = 0
                } else {
                    roundDetail = null
                    reportId?.let { roundDetail = Backend.getRoundReportDetails(teamId, it) }
                    Records.markSeen(this@MainActivity, teamId, Records.MODULE_ROUND,
                        roundDetail?.createdAt ?: 0L)
                    unreadRound = 0
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                roundError = Backend.error(e)
            } finally { roundLoading = false }
        }
        LaunchedEffect(panic?.id) {
            if(panic!=null) {
                confirmTeamId=null;unavailableFeature=null;showStatus=false;inviteAction=null
                drawerState.close()
            }
        }
        // Deep link da notificação agregada: abre o módulo (ou o registro) correspondente.
        val deepLinkTick by RecordRoute.deepLink.collectAsState()
        LaunchedEffect(identified, operationsTeamId, deepLinkTick) {
            if (!identified || operationsTeamId == null) return@LaunchedEffect
            val (module, recordId) = RecordRoute.take()
            when (module) {
                "occurrences" -> if (recordId != null && recordId.isNotBlank()) {
                    incidentId = recordId; incidentDetail = null; screen = "occurrence_detail"
                } else screen = "occurrences"
                "rounds" -> if (recordId != null && recordId.isNotBlank()) {
                    reportId = recordId; roundDetail = null; screen = "round_detail"
                } else screen = "rounds"
                "schedules" -> { screen = "schedules"; shiftRefresh++ }
            }
        }
        // Contadores de não lidos: estado canônico no backend (users/{uid}/recordReadState), com o
        // FCM apenas antecipando a atualização. Loop periódico + reação imediata a recordsTick.
        suspend fun recomputeUnread() {
            val teamId = operationsTeamId ?: return
            val state = Backend.readState(teamId)
            unreadOcc = state.unreadIncidents
            unreadRound = state.unreadRounds
            val push = Records.adoptCanonical(this@MainActivity, teamId, state)
            for (module in push) {
                try {
                    Backend.markRecordsSeen(teamId, module, Records.lastSeenAt(teamId, module))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Drift residual; a próxima leitura re-tenta a reconciliação.
                }
            }
        }
        LaunchedEffect(operationsTeamId, identified, online, shiftRefresh) {
            if (operationsTeamId == null || !identified || !online) return@LaunchedEffect
            while (true) {
                try { recomputeUnread() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                delay(30_000)
            }
        }
        LaunchedEffect(recordsTick, operationsTeamId, identified, online) {
            if (operationsTeamId == null || !identified || !online) return@LaunchedEffect
            try { recomputeUnread() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        // Popup interno não-bloqueante sempre que um registro chegar pelo FCM.
        LaunchedEffect(recordsTick, panic?.id) {
            if (recordsTick == 0L || panic != null) return@LaunchedEffect
            val type = Records.pendingType
            val title = Records.pendingTitle
            val id = Records.pendingId
            if (type != null && title != null && snackbarHostState.currentSnackbarData == null) {
                val result = snackbarHostState.showSnackbar(
                    message = "NOVO REGISTRO: $title",
                    actionLabel = "VER",
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    if (type == Records.MODULE_ROUND) { reportId = id; roundDetail = null; screen = "round_detail" }
                    else { incidentId = id; incidentDetail = null; screen = "occurrence_detail" }
                }
            }
        }
        LaunchedEffect(operationsTeamId, identified, online) {
            val teamId = operationsTeamId ?: run {
                activeShift = null
                activeShiftLoaded = true
                return@LaunchedEffect
            }
            if (!identified || !online) { activeShiftLoaded = false; return@LaunchedEffect }
            activeShiftLoaded = false
            try { activeShift = Backend.activeShift(teamId); activeShiftLoaded = true }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("OperisPtt", "activeShift falhou; preservando rádio até nova sincronização: ${e.message}")
            }
        }
        LaunchedEffect(operationsTeamId, screen, identified, online, shiftRefresh, shiftReportId) {
            val teamId = operationsTeamId ?: return@LaunchedEffect
            if (!identified || !online) return@LaunchedEffect
            if (screen == "schedules") {
                shiftBusy = true
                try { shiftHistory = Backend.listShifts(teamId) }
                catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException)throw e; shiftMessage="Erro: "+Backend.error(e) }
                finally { shiftBusy = false }
            } else if (screen == "shift_report") {
                val id = shiftReportId ?: return@LaunchedEffect
                shiftBusy = true; shiftReport = null
                try { shiftReport = Backend.shiftReport(teamId,id,shiftReportKind); shiftMessage="" }
                catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException)throw e; shiftMessage="Erro: "+Backend.error(e) }
                finally { shiftBusy = false }
            }
        }
        BackHandler(enabled=panic==null && identified && !splashVisible && releaseStatus != ReleaseStatus.REQUIRED && screen!="home") {
            if (screen == "new_occurrence" || screen == "occurrence_detail" || screen == "edit_occurrence") {
                if (!incidentSaving) {
                    if(screen=="new_occurrence") {
                        incidentUpload.clear()
                        operationsTeamId?.let { incidentForms.removeState(it) }
                        incidentError=""
                    }
                    screen = if (screen == "edit_occurrence") "occurrence_detail" else "occurrences"
                }
            } else if(screen=="new_round" || screen=="round_detail" || screen=="edit_round") {
                if(!roundSaving) {
                    if(screen=="new_round") {
                        roundUpload.clear()
                        operationsTeamId?.let { roundForms.removeState(it) }
                        roundError=""
                    }
                    screen = if (screen == "edit_round") "round_detail" else "rounds"
                }
            } else if(screen=="shift_report") {
                shiftReport=null;shiftReportId=null;screen="schedules"
            } else screen = "home"
            message="";preview=null
        }
        BackHandler(enabled=panic==null && identified && !splashVisible && drawerState.isOpen) {scope.launch{drawerState.close()}}

        if (splashVisible) {
            OperisSplash(onFinished = { splashVisible = false })
        } else {
        ModalNavigationDrawer(drawerState=drawerState,gesturesEnabled=identified && panic==null,
            drawerContent={
                if(identified && panic==null)AppDrawer(screen, occurrences = unreadOcc, rounds = unreadRound,
                    operationalReady = identified && online && ready && !deviceDiagnostics.needsAttention){target->
                    scope.launch {drawerState.close();screen=target;message=""}
                }
            }) {
            when {
                panic!=null -> {
                    val alert=panic!!
                    EmergencyScreen(alert,TeamSelection.receivedTeamName(alert.team,memberships),
                        deviceDiagnostics.audioWarning,time(alert.time),
                        onSilence={Alerts.silence(this@MainActivity)},onActivate={Alerts.launch(this@MainActivity,alert)})
                }
                restoringSession -> ScreenColumn {
                    Spacer(Modifier.height(48.dp))
                    SectionHeader("OPERIS", BRAND_TAGLINE)
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Restaurando sua conta e equipes...", color = AppMuted)
                }
                !identified -> ProfileScreen(busy,message,{name->perform{
                    Backend.profile(name)
                    teamPreferences.markReleaseSeen();releasePending=false
                    identified=true;screen="teams"
                }},{email,password->perform{
                    Backend.signIn(email,password)
                    refreshMemberships()
                    identified=true;screen=if(memberships.isEmpty())"teams" else "home"
                }})
                releaseStatus == ReleaseStatus.REQUIRED && releasePolicy != null -> RequiredUpdateScreen(
                    installedVersionName = BuildConfig.VERSION_NAME,
                    policy = releasePolicy!!,
                    state = updateUi,
                    onUpdate = { updateNow() },
                    onFallback = { updateNow(true) })
                else -> Box(Modifier.fillMaxSize().background(AppInk)) {
                    Column(Modifier.fillMaxSize()) {
                    BrandTopBar(sectionLabel=when(screen){
                        "teams","details","create","join"->"EQUIPES";"settings"->"CONFIGURAÇÕES"
                        "occurrences","new_occurrence","occurrence_detail","edit_occurrence"->"OCORRÊNCIAS";"rounds","new_round","round_detail","edit_round"->"RONDAS";"radio"->"RÁDIO";"emergency"->"EMERGÊNCIA";"schedules","shift_report"->"ESCALA / PLANTÕES";else->null
                    },operationalReady=identified && online && ready && !deviceDiagnostics.needsAttention,onMenu={scope.launch{drawerState.open()}})
                    when(screen) {
                        "teams" -> TeamsScreen(memberships,selectedTeam?.teamId,details,refreshing,
                            listOf(message,rosterMessage).filter{it.isNotBlank()}.joinToString("\n"),myRequests,
                            onSelect={id->if(teamPreferences.select(id,memberships)){selectedTeamId=id;screen="home";scope.launch{runCatching{Backend.selectActiveTeam(id)}}}},
                            onOpen={id->detailId=id;createdTeam=false;screen="details";message=""},
                            onCreate={screen="create";message="";preview=null},
                            onJoin={screen="join";message="";preview=null},
                            onRefresh={refreshRoster++})
                        "create","join" -> TeamFormScreen(screen=="create",busy,message,preview,
                            onSubmit={input,selectedFunction->perform{
                                if(screen=="create") {
                                    val prefs=Local.prefs
                                    val same=prefs.getString("pendingTeamName",null)==input.trim()
                                    val requestId=if(same) prefs.getString("pendingTeamRequest",null) ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
                                    prefs.edit().putString("pendingTeamName",input.trim()).putString("pendingTeamRequest",requestId).commit()
                                    val id=Backend.createTeam(input.trim(),requestId,checkNotNull(selectedFunction))
                                    afterMembership(id,true)
                                    prefs.edit().remove("pendingTeamName").remove("pendingTeamRequest").apply()
                                } else preview=Backend.preview(input)
                            }},
                            onConfirm={selectedFunction->preview?.let{invitation->perform{
                                val result=Backend.join(invitation,selectedFunction)
                                if(result.status=="active")afterMembership(result.teamId,false)
                                else {
                                    refreshMemberships();preview=null;screen="teams";refreshRoster++
                                    message="Solicitação enviada. Aguarde a aprovação do proprietário."
                                }
                            }}},
                            onEdit={preview=null;message=""},onBack={screen="teams";preview=null;message=""})
                        "details" -> TeamDetailsScreen(details[detailId],createdTeam,
                            listOf(message,rosterMessage).filter{it.isNotBlank()}.joinToString("\n"),refreshing,
                            onRefresh={refreshRoster++},onBack={screen="teams";message=""},
                            onCopy={code->
                                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                    android.content.ClipData.newPlainText("Código da equipe",Operational.formatInvite(code)))
                                message="Código copiado."
                            },
                            onShare={name,code->share("Entre na equipe \""+name+"\" pelo OPERIS.\n\nCódigo de convite:\n"+
                                Operational.formatInvite(code)+"\n\nAbra o OPERIS e escolha Entrar em uma equipe.")},
                            onInvite={inviteAction=it},busy=busy,
                            onReview={uid,approve->detailId?.let{id->perform{Backend.review(id,uid,approve);refreshRoster++}}},
                            onEditFunction={member,function->detailId?.let{id->perform{
                                Backend.updateMemberFunction(id,member.uid,function)
                                details=details+(id to Backend.details(id))
                                refreshMemberships()
                                rosterMessage="Cargo de ${member.name} alterado para ${function.label}."
                            }}},
                            onSaveSchedule={startTime->detailId?.let{id->perform{
                                val schedule=Backend.setTeamShiftSchedule(id,startTime)
                                val current=details[id]
                                if(current!=null)details=details+(id to current.copy(shiftSchedule=schedule))
                                refreshMemberships()
                                rosterMessage="Escala 12x36 atualizada: ${schedule.startTime} ?s ${schedule.endTime}."
                            }}},
                            onRemoveMember={member->detailId?.let{id->perform{
                                Backend.removeTeamMember(id,member.uid)
                                details=details+(id to Backend.details(id))
                                rosterMessage="${member.name} foi removido da equipe."
                            }}},
                            onDissolve={detailId?.let{id->perform{
                                Backend.dissolveTeam(id)
                                details=details-id
                                refreshMemberships()
                                detailId=null;createdTeam=false;screen="teams"
                                message="Equipe desfeita. Os registros operacionais foram preservados."
                            }}})
                        "settings" -> SettingsScreen(deviceDiagnostics,online,ready,
                            lastAlert=Local.last?.let{TeamSelection.receivedTeamName(it.team,memberships)+"\n"+it.name+" • "+time(it.time)},
                            onNotifications={openNotifications()},
                            onFullScreen={openFullScreenIntentSettings()},
                            onSound={startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))},
                            onDoNotDisturb={startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))},
                            onMicrophone={openMicrophonePermission()},
                            onCamera={openRuntimePermission(Manifest.permission.CAMERA)},
                            onNetwork={startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))},
                            onDndMode={runCatching { startActivity(Intent("android.settings.ZEN_MODE_SETTINGS")) }
                                .onFailure { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }},
                            onBattery={openBatterySettings()},
                            onTest={Alerts.launch(this@MainActivity,Panic(UUID.randomUUID().toString(),selectedTeam?.teamId?:Local.teamId,
                                "local-test","TESTE LOCAL",System.currentTimeMillis()))},
                            onRetry={retryConnection++},
                            latestRelease=releasePolicy,releaseNote=releaseNote,checkingUpdate=checkingUpdate,
                            onCheckUpdate={scope.launch{refreshRelease(true)}},
                            onShowUpdate={optionalDismissed=false},
                            onShareRelease={val p=releasePolicy;if(p!=null && p.canDownload())share(ReleaseInfo.shareMessage(p))},
                            onStatus={showStatus=true},statusLabel=Operational.statusLabel(availability,pauseReason),
                            accountEmail=protectedAccountEmail,
                            onProtectAccount={accountProtectionError="";showProtectAccount=true},
                            onLogout={perform{Backend.logout();memberships=emptyList();myRequests=emptyList();selectedTeamId=null;identified=false;screen="home"}})
                        "occurrences" -> IncidentsListScreen(selectedTeam?.displayName,
                            incidents, incidentLoading, incidentError, incidentCursor != null,
                            incidentDeletedOnly, { record -> canMutate(record.authorUid) },
                            onRegister={
                                val teamId=operationsTeamId
                                if(!incidentSaving && teamId!=null) scope.launch {
                                    try {
                                        val fresh=Backend.activeShift(teamId)
                                        activeShift=fresh
                                        val joined=fresh?.participants?.any { it.uid==Local.uid && it.endedAt==null }==true
                                        if(joined) {
                                            incidentUpload.clear()
                                            incidentForms.removeState(teamId)
                                            incidentError=""
                                            screen="new_occurrence"
                                        }
                                        else {
                                            shiftMessage=if(fresh==null) "Inicie um plantão nesta equipe antes de registrar uma ocorrência."
                                                else "Entre no plantão ativo desta equipe antes de registrar uma ocorrência."
                                            incidentError=""
                                            screen="schedules"
                                        }
                                    } catch(e:Exception) {
                                        if(e is kotlinx.coroutines.CancellationException)throw e
                                        incidentError=Backend.error(e)
                                    }
                                }
                            },
                            onOpen={incidentId=it.incidentId;incidentDetail=null;editingIncident=null;screen="occurrence_detail"},
                            onRefresh={incidentRefresh++}, onMore={
                                val teamId=operationsTeamId;val cursor=incidentCursor
                                if(teamId!=null && cursor!=null && !incidentLoading) {
                                    incidentLoading=true;incidentError=""
                                    scope.launch {
                                        try {
                                            val page=Backend.listIncidents(teamId,cursor,incidentDeletedOnly)
                                            if(selectedTeamId==teamId && screen=="occurrences") {
                                                incidents=(incidents+page.records).distinctBy{it.incidentId}
                                                incidentCursor=page.nextCursor
                                            }
                                        } catch(e:Exception) {
                                            if(e is kotlinx.coroutines.CancellationException)throw e
                                            if(selectedTeamId==teamId)incidentError=Backend.error(e)
                                        } finally {if(selectedTeamId==teamId)incidentLoading=false}
                                    }
                                }
                            }, onToggleDeleted={incidentDeletedOnly=!incidentDeletedOnly})
                        "new_occurrence" -> {
                            val team=selectedTeam
                            if(team==null)Text("Selecione uma equipe em Minhas equipes.", color = MaterialTheme.colorScheme.onSurface)
                            else incidentForms.SaveableStateProvider(team.teamId) {
                                NewIncidentScreen(team.displayName,incidentSaving,incidentError,
                                    onBack={
                                        incidentUpload.clear()
                                        incidentForms.removeState(team.teamId)
                                        incidentError=""
                                        screen="occurrences"
                                    },locked=incidentUpload.recordId!=null,initial=null,onSave={draft,picks->
                                        if(!incidentSaving) {
                                            incidentSaving=true;incidentError=""
                                            scope.launch {
                                                try {
                                                    val shift=activeShift ?: throw IllegalStateException("Assuma o plantão antes de registrar uma ocorrência.")
                                                    val recordId=incidentUpload.recordId ?: Backend.createIncident(team.teamId,shift.shiftId,draft).incidentId.also { incidentUpload.recordId=it }
                                                    uploadAttachments(this@MainActivity,team.teamId,"incident",incidentUpload,picks) { incidentError=it }
                                                    val created=Backend.getIncidentDetails(team.teamId,recordId)
                                                    if(picks.isNotEmpty() && created.attachments.size < picks.size)
                                                        throw IllegalStateException("Os anexos ainda não foram confirmados. Tente salvar novamente.")
                                                    incidentUpload.clear()
                                                    incidentError=""
                                                    if(selectedTeamId==team.teamId) {
                                                        incidents=listOf(created)+incidents.filterNot{it.incidentId==created.incidentId}
                                                        if(screen=="new_occurrence")screen="occurrences"
                                                    }
                                                    incidentForms.removeState(team.teamId)
                                                } catch(e:Exception) {
                                                    if(e is kotlinx.coroutines.CancellationException)throw e
                                                    if(selectedTeamId==team.teamId)incidentError=Backend.error(e)
                                                } finally {incidentSaving=false}
                                            }
                                        }
                                    })
                            }
                        }
                        "edit_occurrence" -> {
                            val team=selectedTeam;val current=editingIncident
                            if(team==null || current==null)Text("Selecione uma equipe em Minhas equipes.", color = MaterialTheme.colorScheme.onSurface)
                            else {
                                val stateKey=team.teamId+":edit:"+current.incidentId
                                incidentForms.SaveableStateProvider(stateKey) {
                                    NewIncidentScreen(team.displayName,incidentSaving,incidentError,
                                        onBack={screen="occurrence_detail"},locked=false,initial=current,onSave={draft,_->
                                            if(!incidentSaving) {
                                                incidentSaving=true;incidentError=""
                                                scope.launch {
                                                    try {
                                                        Backend.updateIncident(team.teamId,current.incidentId,draft)
                                                        editingIncident=null
                                                        incidentDetail=null
                                                        incidentRefresh++
                                                        if(selectedTeamId==team.teamId && screen=="edit_occurrence")screen="occurrence_detail"
                                                        incidentForms.removeState(stateKey)
                                                    } catch(e:Exception) {
                                                        if(e is kotlinx.coroutines.CancellationException)throw e
                                                        if(selectedTeamId==team.teamId)incidentError=Backend.error(e)
                                                    } finally {incidentSaving=false}
                                                }
                                            }
                                        })
                                }
                            }
                        }
                        "occurrence_detail" -> IncidentDetailsScreen(incidentDetail,incidentLoading,incidentError,
                            canEdit=incidentDetail!=null && canMutate(incidentDetail!!.authorUid),
                            canDelete=incidentDetail!=null && canMutate(incidentDetail!!.authorUid),
                            onBack={screen="occurrences"},onRetry={incidentRefresh++},
                            onEdit={incidentDetail?.let{editingIncident=it;incidentError="";screen="edit_occurrence"}},
                            onShareSector={sector->
                                val current=incidentDetail; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    incidentLoading=true
                                    try {
                                        val shared=Backend.shareIncident(t.teamId,current.incidentId,sector)
                                        incidentDetail=shared
                                        incidents=incidents.map{if(it.incidentId==shared.incidentId)shared else it}
                                        incidentError="Ocorrência encaminhada para ${sector.label}. O setor foi notificado."
                                    } catch(e:Exception){incidentError="Erro: "+Backend.error(e)}
                                    finally{incidentLoading=false}
                                }
                            },
                            onDelete={incidentDetail?.let{deletingIncident=it}})
                        "rounds" -> RoundsListScreen(selectedTeam?.displayName,
                            rounds, roundLoading, roundError, roundCursor != null,
                            roundDeletedOnly, { record -> canMutate(record.authorUid) },
                            onRegister={
                                val teamId=operationsTeamId
                                if(!roundSaving && teamId!=null) scope.launch {
                                    try {
                                        val fresh=Backend.activeShift(teamId)
                                        activeShift=fresh
                                        val joined=fresh?.participants?.any { it.uid==Local.uid && it.endedAt==null }==true
                                        if(joined) {
                                            roundUpload.clear()
                                            roundForms.removeState(teamId)
                                            roundError=""
                                            screen="new_round"
                                        }
                                        else {
                                            shiftMessage=if(fresh==null) "Inicie um plantão nesta equipe antes de registrar uma ronda."
                                                else "Entre no plantão ativo desta equipe antes de registrar uma ronda."
                                            roundError=""
                                            screen="schedules"
                                        }
                                    } catch(e:Exception) {
                                        if(e is kotlinx.coroutines.CancellationException)throw e
                                        roundError=Backend.error(e)
                                    }
                                }
                            },
                            onOpen={reportId=it.reportId;roundDetail=null;editingRound=null;screen="round_detail"},
                            onRefresh={roundRefresh++}, onMore={
                                val teamId=operationsTeamId;val cursor=roundCursor
                                if(teamId!=null && cursor!=null && !roundLoading) {
                                    roundLoading=true;roundError=""
                                    scope.launch {
                                        try {
                                            val page=Backend.listRoundReports(teamId,cursor,roundDeletedOnly)
                                            if(selectedTeamId==teamId && screen=="rounds") {
                                                rounds=(rounds+page.records).distinctBy{it.reportId}
                                                roundCursor=page.nextCursor
                                            }
                                        } catch(e:Exception) {
                                            if(e is kotlinx.coroutines.CancellationException)throw e
                                            if(selectedTeamId==teamId)roundError=Backend.error(e)
                                        } finally {if(selectedTeamId==teamId)roundLoading=false}
                                    }
                                }
                            }, onToggleDeleted={roundDeletedOnly=!roundDeletedOnly})
                        "new_round" -> {
                            val team=selectedTeam
                            if(team==null)Text("Selecione uma equipe em Minhas equipes.", color = MaterialTheme.colorScheme.onSurface)
                            else roundForms.SaveableStateProvider(team.teamId) {
                                NewRoundScreen(team.displayName,roundSaving,roundError,
                                    onBack={
                                        roundUpload.clear()
                                        roundForms.removeState(team.teamId)
                                        roundError=""
                                        screen="rounds"
                                    },locked=roundUpload.recordId!=null,initial=null,onSave={draft,picks->
                                        if(!roundSaving) {
                                            roundSaving=true;roundError=""
                                            scope.launch {
                                                try {
                                                    val shift=activeShift ?: throw IllegalStateException("Assuma o plantão antes de registrar uma ronda.")
                                                    if(roundUpload.recordId==null) {
                                                        roundUpload.recordId=Backend.createRoundReport(team.teamId,shift.shiftId,draft).reportId
                                                    }
                                                    val recordId=checkNotNull(roundUpload.recordId)
                                                    uploadAttachments(this@MainActivity,team.teamId,"round",roundUpload,picks) { roundError=it }
                                                    val created=Backend.getRoundReportDetails(team.teamId,recordId)
                                                    if(picks.isNotEmpty() && created.attachments.size < picks.size)
                                                        throw IllegalStateException("Os anexos ainda não foram confirmados. Tente salvar novamente.")
                                                    roundUpload.clear()
                                                    roundError=""
                                                    roundForms.removeState(team.teamId)
                                                    roundRefresh++
                                                    if(selectedTeamId==team.teamId) {
                                                        rounds=listOf(created)+rounds.filterNot{it.reportId==created.reportId}
                                                        if(screen=="new_round")screen="rounds"
                                                    }
                                                } catch(e:Exception) {
                                                    if(e is kotlinx.coroutines.CancellationException)throw e
                                                    if(selectedTeamId==team.teamId)roundError=Backend.error(e)
                                                } finally {roundSaving=false}
                                            }
                                        }
                                    })
                            }
                        }
                        "edit_round" -> {
                            val team=selectedTeam;val current=editingRound
                            if(team==null || current==null)Text("Selecione uma equipe em Minhas equipes.", color = MaterialTheme.colorScheme.onSurface)
                            else {
                                val stateKey=team.teamId+":edit:"+current.reportId
                                roundForms.SaveableStateProvider(stateKey) {
                                    NewRoundScreen(team.displayName,roundSaving,roundError,
                                        onBack={screen="round_detail"},locked=false,initial=current,onSave={draft,_->
                                            if(!roundSaving) {
                                                roundSaving=true;roundError=""
                                                scope.launch {
                                                    try {
                                                        Backend.updateRoundReport(team.teamId,current.reportId,draft)
                                                        editingRound=null
                                                        roundDetail=null
                                                        roundRefresh++
                                                        if(selectedTeamId==team.teamId && screen=="edit_round")screen="round_detail"
                                                        roundForms.removeState(stateKey)
                                                    } catch(e:Exception) {
                                                        if(e is kotlinx.coroutines.CancellationException)throw e
                                                        if(selectedTeamId==team.teamId)roundError=Backend.error(e)
                                                    } finally {roundSaving=false}
                                                }
                                            }
                                        })
                                }
                            }
                        }
                        "round_detail" -> RoundDetailsScreen(roundDetail,roundLoading,roundError,
                            canEdit=roundDetail!=null && canMutate(roundDetail!!.authorUid),
                            canDelete=roundDetail!=null && canMutate(roundDetail!!.authorUid),
                            onBack={screen="rounds"},onRetry={roundRefresh++},
                            onEdit={roundDetail?.let{editingRound=it;roundError="";screen="edit_round"}},
                            onDelete={roundDetail?.let{deletingRound=it}})
                        "radio" -> RadioScreen(
                            selectedTeam?.teamId,
                            selectedTeam?.displayName,
                            selectedTeam?.operationalFunction,
                            radioOnDuty
                        )
                        "emergency" -> {
                            val team=selectedTeam
                            val teamDetails=team?.teamId?.let { details[it] }
                            EmergencyContactsScreen(
                                teamName=team?.displayName,
                                contacts=teamDetails?.emergencyContacts ?: emptyList(),
                                canManage=teamDetails?.myRole=="owner" || teamDetails?.myRole=="admin",
                                saving=emergencySaving,
                                message=emergencyMessage,
                                onDial={ phone -> runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel",phone,null))) } },
                                onSave={ contacts ->
                                    val target=team ?: return@EmergencyContactsScreen
                                    if(!emergencySaving) {
                                        emergencySaving=true;emergencyMessage=""
                                        scope.launch {
                                            try {
                                                val saved=Backend.setEmergencyContacts(target.teamId,contacts)
                                                val current=details[target.teamId]
                                                if(current!=null)details=details+(target.teamId to current.copy(emergencyContacts=saved))
                                                emergencyMessage="Contatos da equipe atualizados."
                                            } catch(e:Exception) {
                                                if(e is kotlinx.coroutines.CancellationException)throw e
                                                emergencyMessage="Erro: "+Backend.error(e)
                                            } finally { emergencySaving=false }
                                        }
                                    }
                                })
                        }
                        "schedules" -> ShiftScreen(
                            selectedTeam,activeShift,shiftHistory,shiftBusy,shiftMessage,
                            onRefresh={ selectedTeam?.let { t -> scope.launch {
                                shiftBusy=true
                                try { activeShift=Backend.activeShift(t.teamId);shiftMessage="" }
                                catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                finally{shiftBusy=false}
                            } } },
                            onStart={label,company,location,previous,post,portaria,batalhao-> selectedTeam?.let { t -> scope.launch {
                                shiftBusy=true
                                try {
                                    activeShift=Backend.startShift(t.teamId,label,company,location,previous,post,portaria,batalhao)
                                    shiftMessage="Entrada no plantão registrada."
                                } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                finally{shiftBusy=false}
                            } } },
                            onAddIntermediate={name,startedAt,endedAt->
                                val current=activeShift; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    shiftBusy=true
                                    try {
                                        activeShift=Backend.addIntermediateBrigadista(t.teamId,current.shiftId,name,startedAt,endedAt)
                                        shiftMessage="Participação intermediária registrada."
                                    } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                    finally{shiftBusy=false}
                                }
                            },
                            onConfirmSecurityPosts={portaria,batalhao->
                                val current=activeShift; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    shiftBusy=true
                                    try {
                                        activeShift=Backend.confirmSecurityPosts(t.teamId,current.shiftId,portaria,batalhao)
                                        shiftMessage="Postos da Segurança confirmados."
                                    } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                    finally{shiftBusy=false}
                                }
                            },
                            onUpdateAgpPost={targetUid,post->
                                val current=activeShift; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    shiftBusy=true
                                    try {
                                        activeShift=Backend.updateAgpPost(t.teamId,current.shiftId,targetUid,post)
                                        shiftMessage="Posto atualizado e registrado na auditoria."
                                    } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                    finally{shiftBusy=false}
                                }
                            },
                            onStartCoverage={targetUid,coveringUid,reason->
                                val current=activeShift; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    shiftBusy=true
                                    try {
                                        activeShift=Backend.startAgpCoverage(t.teamId,current.shiftId,targetUid,coveringUid,reason)
                                        shiftMessage="Rendição temporária registrada."
                                    } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                    finally{shiftBusy=false}
                                }
                            },
                            onFinishCoverage={coverageId->
                                val current=activeShift; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    shiftBusy=true
                                    try {
                                        activeShift=Backend.finishAgpCoverage(t.teamId,current.shiftId,coverageId)
                                        shiftMessage="Cobertura encerrada e registrada na auditoria."
                                    } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                    finally{shiftBusy=false}
                                }
                            },
                            onOpenReport={past,kind->
                                shiftReportId=past.shiftId
                                shiftReportKind=kind
                                shiftReport=null
                                shiftMessage=""
                                screen="shift_report"
                            },
                            onFinish={next,closing->
                                val current=activeShift; val t=selectedTeam
                                if(current!=null&&t!=null) scope.launch {
                                    shiftBusy=true
                                    try {
                                        val updated=Backend.finishShift(t.teamId,current.shiftId,next,closing)
                                        activeShift=if(updated.status=="ACTIVE")updated else null
                                        shiftMessage=if(updated.status=="ACTIVE")"Sua participação foi encerrada. O plantão continua ativo para os demais integrantes." else "Plantão encerrado."
                                    } catch(e:Exception){shiftMessage="Erro: "+Backend.error(e)}
                                    finally{shiftBusy=false}
                                }
                            })
                        "shift_report" -> ShiftReportScreen(
                            report=shiftReport,
                            kind=shiftReportKind,
                            teamName=selectedTeam?.displayName.orEmpty(),
                            loading=shiftBusy,
                            message=shiftMessage,
                            onBack={shiftReport=null;shiftReportId=null;screen="schedules"})
                        else -> HomeScreen(Local.name,selectedTeam,online,ready,deviceDiagnostics,busy,seconds,
                            canSend=selectedTeam!=null,
                            message=message,onAlert={confirmTeamId=selectedTeam?.teamId},onTeams={screen="teams"},
                            onFix={fixHealth()},onRetry={retryConnection++},onRadio={screen="radio"},
                            radioState=radioState,
                            onRadioPress={
                                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                    PttRadioService.press(this@MainActivity)
                                else permission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onRadioRelease={PttRadioService.release(this@MainActivity)},
                            unreadOccurrences=unreadOcc,unreadRounds=unreadRound,
                            onOccurrences={screen="occurrences"},onRounds={screen="rounds"})
                    }
                    }
                    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        val confirmedTeam=memberships.firstOrNull{it.teamId==confirmTeamId}
        if(confirmedTeam!=null && panic==null) AlertDialog(
            onDismissRequest={confirmTeamId=null},title={Text("ACIONAR ALERTA?")},
            text={Column{Text("Equipe:",color=AppMuted);Text(confirmedTeam.displayName,fontSize=23.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(16.dp));Text("O alerta será enviado para esta equipe.")}},
            dismissButton={TextButton(onClick={confirmTeamId=null}){Text("CANCELAR")}},
            confirmButton={Button(enabled=online && ready && !busy && seconds==0,onClick={
                confirmTeamId=null
                perform{
                    val now=System.currentTimeMillis()
                    val old=Local.prefs.getString("pendingId",null)
                    val pendingTeam=Local.prefs.getString("pendingTeamId",Local.teamId)
                    val id=if(old!=null && pendingTeam==confirmedTeam.teamId && now-Local.prefs.getLong("pendingTime",0)<60_000)old else UUID.randomUUID().toString()
                    if(id!=old)Local.prefs.edit().putString("pendingId",id).putString("pendingTeamId",confirmedTeam.teamId).putLong("pendingTime",now).commit()
                    Backend.send(id,confirmedTeam.teamId)
                    Local.prefs.edit().remove("pendingId").remove("pendingTime").remove("pendingTeamId").apply()
                    message="Alerta enviado. O recebimento depende da conexão dos aparelhos."
                    cooldown=System.currentTimeMillis()+5000;seconds=5
                }
            }){Text("ACIONAR")}})
        if(showStatus && panic==null)StatusDialog(busy,message,{showStatus=false}){value,reason->perform{
            Backend.status(value,reason);availability=value;pauseReason=reason;showStatus=false;refreshRoster++
        }}
        if(inviteAction!=null && panic==null)AlertDialog(onDismissRequest={inviteAction=null},
            title={Text(if(inviteAction=="rotate")"Gerar novo convite?" else "Revogar convite?")},
            text={Text("O código anterior deixará de permitir novas entradas. Os participantes atuais serão mantidos.")},
            dismissButton={TextButton(onClick={inviteAction=null}){Text("CANCELAR")}},
            confirmButton={TextButton(enabled=!busy,onClick={
                val action=inviteAction;val id=detailId
                inviteAction=null
                if(action!=null && id!=null)perform{Backend.manageInvite(id,action);refreshRoster++}
            }){Text("CONFIRMAR")}})
        if(showProtectAccount && panic==null) ProtectAccountDialog(
            busy=busy,error=accountProtectionError,
            onDismiss={showProtectAccount=false;accountProtectionError=""},
            onProtect={email,password->
                if(!busy) {
                    busy=true;accountProtectionError=""
                    scope.launch {
                        try {
                            val account=Backend.protectAccount(email,password)
                            check(account.uid==FirebaseAuth.getInstance().currentUser?.uid)
                            protectedAccountEmail=account.email
                            showProtectAccount=false
                            message="Conta protegida com sucesso."
                        } catch(e:Exception) {
                            if(e is kotlinx.coroutines.CancellationException)throw e
                            accountProtectionError=Backend.error(e)
                        } finally {busy=false}
                    }
                }
            })
        val updatePolicy = releasePolicy
        if (releaseStatus == ReleaseStatus.UPDATE_AVAILABLE && !optionalDismissed && updatePolicy != null &&
            identified && panic==null && confirmTeamId==null && !showStatus && inviteAction==null) OptionalUpdateDialog(
            installedVersionName = BuildConfig.VERSION_NAME,
            policy = updatePolicy,
            state = updateUi,
            onUpdate = { updateNow() },
            onFallback = { updateNow(true) },
            onLater = { optionalDismissed = true })
        if(releasePending && identified && memberships.isNotEmpty() && panic==null && confirmTeamId==null &&
            !showStatus && inviteAction==null && releaseStatus != ReleaseStatus.REQUIRED)AlertDialog(
            onDismissRequest={teamPreferences.markReleaseSeen();releasePending=false},
            title={Text(ReleaseInfo.title + " " + BuildConfig.VERSION_NAME)},text={Column{
                Text("Versão "+BuildConfig.VERSION_NAME)
                ((releasePolicy?.takeIf { it.latestVersionCode == BuildConfig.VERSION_CODE }?.releaseNotes) ?: ReleaseInfo.releaseNotes).forEach{Text("• "+it)}
                Text("Os recursos de equipes dependem da atualização do serviço.",color=AppMuted)
            }},confirmButton={TextButton(onClick={teamPreferences.markReleaseSeen();releasePending=false}){Text("ENTENDI")}})
        if(unavailableFeature!=null && panic==null)AlertDialog(
            onDismissRequest={unavailableFeature=null},title={Text(unavailableFeature!!)},
            text={Text("Este módulo está sendo preparado.")},
            confirmButton={TextButton(onClick={unavailableFeature=null}){Text("ENTENDI")}})
        deletingIncident?.let { record ->
            AlertDialog(onDismissRequest={deletingIncident=null},
                title={Text("EXCLUIR OCORRÊNCIA?")},
                text={Text("O registro será movido para \u201cREGISTROS EXCLUÍDOS\u201d e ficará disponível somente para leitura. Anexos não são apagados.")},
                dismissButton={TextButton(onClick={deletingIncident=null}){Text("CANCELAR")}},
                confirmButton={TextButton(enabled=!busy,onClick={
                    val id=record.incidentId;val team=operationsTeamId
                    if(team!=null && !busy) {
                        busy=true;incidentError=""
                        scope.launch {
                            try {
                                Backend.softDeleteIncident(team,id)
                                deletingIncident=null
                                incidentDetail=null
                                incidentId=null
                                incidentDeletedOnly=false
                                incidentRefresh++
                                screen="occurrences"
                                message="Ocorrência excluída. Ela permanece em REGISTROS EXCLUÍDOS."
                            } catch(e:Exception) {
                                if(e is kotlinx.coroutines.CancellationException)throw e
                                deletingIncident=null
                                incidentError="Erro ao excluir: "+Backend.error(e)
                            } finally {busy=false}
                        }
                    }
                }){Text("EXCLUIR")}})
        }
        deletingRound?.let { record ->
            AlertDialog(onDismissRequest={deletingRound=null},
                title={Text("EXCLUIR RONDA?")},
                text={Text("O registro será movido para \u201cREGISTROS EXCLUÍDOS\u201d e ficará disponível somente para leitura. Anexos não são apagados.")},
                dismissButton={TextButton(onClick={deletingRound=null}){Text("CANCELAR")}},
                confirmButton={TextButton(enabled=!busy,onClick={
                    val id=record.reportId;val team=operationsTeamId
                    if(team!=null && !busy) {
                        busy=true;roundError=""
                        scope.launch {
                            try {
                                Backend.softDeleteRoundReport(team,id)
                                deletingRound=null
                                roundDetail=null
                                reportId=null
                                roundDeletedOnly=false
                                roundRefresh++
                                screen="rounds"
                                message="Ronda excluída. Ela permanece em REGISTROS EXCLUÍDOS."
                            } catch(e:Exception) {
                                if(e is kotlinx.coroutines.CancellationException)throw e
                                deletingRound=null
                                roundError="Erro ao excluir: "+Backend.error(e)
                            } finally {busy=false}
                        }
                    }
                }){Text("EXCLUIR")}})
        }
        shareText?.let { NoteTextDialog(it, { shareText = null }) }
        }
    }

    private fun share(message: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type="text/plain";putExtra(Intent.EXTRA_TEXT,message)
        },"Compartilhar"))
    }
    private fun time(value:Long)=SimpleDateFormat("dd/MM HH:mm:ss",Locale("pt","BR")).format(Date(value))
}
