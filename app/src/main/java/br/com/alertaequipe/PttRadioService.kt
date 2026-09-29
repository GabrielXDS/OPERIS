package br.com.alertaequipe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object PttRadioRuntime {
    private val _state = MutableStateFlow(PttUiState())
    val state: StateFlow<PttUiState> = _state.asStateFlow()
    private val _teamId = MutableStateFlow<String?>(null)
    val teamId: StateFlow<String?> = _teamId.asStateFlow()
    private val _teamName = MutableStateFlow<String?>(null)
    val teamName: StateFlow<String?> = _teamName.asStateFlow()
    private val _channel = MutableStateFlow(PttChannel.TODOS)
    val channel: StateFlow<PttChannel> = _channel.asStateFlow()
    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()
    private val _activeSpeakerChannel = MutableStateFlow<PttChannel?>(null)
    val activeSpeakerChannel: StateFlow<PttChannel?> = _activeSpeakerChannel.asStateFlow()
    private val _history = MutableStateFlow<List<PttTransmission>>(emptyList())
    val history: StateFlow<List<PttTransmission>> = _history.asStateFlow()

    internal fun update(teamId: String?, teamName: String?, channel: PttChannel, state: PttUiState) {
        _teamId.value = teamId
        _teamName.value = teamName
        _channel.value = channel
        _state.value = state
    }

    internal fun setActiveSpeaker(name: String?, channel: PttChannel?) {
        _activeSpeaker.value = name?.takeIf { it.isNotBlank() }
        _activeSpeakerChannel.value = if (_activeSpeaker.value == null) null else channel
    }

    internal fun recordTransmission(name: String, channel: PttChannel, local: Boolean = false, at: Long = System.currentTimeMillis()) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val item = PttTransmission(clean, channel, at, local)
        _history.value = (listOf(item) + _history.value).take(3)
    }

    internal fun clearHistory() { _history.value = emptyList() }

    internal fun reset() {
        update(null, null, PttChannel.TODOS, PttUiState())
        setActiveSpeaker(null, null)
        clearHistory()
    }
}

private object PersistentPttBackend : PttBackend {
    override suspend fun access(teamId: String, channel: PttChannel) = Backend.getPttAccess(teamId, channel)
    override suspend fun requestFloor(teamId: String, channel: PttChannel) = Backend.requestPttFloor(teamId, channel)
    override suspend fun renewFloor(teamId: String, channel: PttChannel) = Backend.renewPttFloor(teamId, channel)
    override suspend fun releaseFloor(teamId: String, channel: PttChannel) = Backend.releasePttFloor(teamId, channel)
}

private class PttServiceSounder(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val endId = pool.load(context, R.raw.ptt_end, 1)
    private val errorId = pool.load(context, R.raw.ptt_error, 1)
    fun play(event: PttSoundEvent) {
        val id = when (event) {
            PttSoundEvent.START -> return
            PttSoundEvent.END -> endId
            PttSoundEvent.ERROR -> errorId
            PttSoundEvent.NONE -> return
        }
        if (id != 0) runCatching { pool.play(id, 1f, 1f, 1, 0, 1f) }
    }
    fun release() = runCatching { pool.release() }
}

class PttRadioService : Service() {
    companion object {
        private const val CHANNEL_ID = "operis_radio"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "br.com.alertaequipe.PTT_START"
        private const val ACTION_PRESS = "br.com.alertaequipe.PTT_PRESS"
        private const val ACTION_RELEASE = "br.com.alertaequipe.PTT_RELEASE"
        private const val ACTION_RECONNECT = "br.com.alertaequipe.PTT_RECONNECT"
        private const val EXTRA_TEAM_ID = "teamId"
        private const val EXTRA_TEAM_NAME = "teamName"
        private const val EXTRA_OPERATIONAL_FUNCTION = "operationalFunction"
        private const val EXTRA_CHANNEL = "channel"
        private const val PREFS = "operis_ptt_channels"
        private const val DUTY_PREFS = "operis_ptt_duty"
        private const val DUTY_END_SUFFIX = "_end"

        private fun isOnDuty(context: Context, teamId: String): Boolean =
            context.getSharedPreferences(DUTY_PREFS, Context.MODE_PRIVATE).getBoolean(teamId, false)

        private fun dutyEndAt(context: Context, teamId: String): Long =
            context.getSharedPreferences(DUTY_PREFS, Context.MODE_PRIVATE).getLong(teamId + DUTY_END_SUFFIX, 0L)

        fun setDuty(context: Context, teamId: String, onDuty: Boolean, endAt: Long? = null) {
            val edit = context.getSharedPreferences(DUTY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(teamId, onDuty)
            if (onDuty && endAt != null && endAt > 0L) edit.putLong(teamId + DUTY_END_SUFFIX, endAt)
            else if (!onDuty) edit.remove(teamId + DUTY_END_SUFFIX)
            edit.apply()
        }

        private fun savedChannel(context: Context, teamId: String, operationalFunction: String): PttChannel {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(teamId, null)
            return runCatching { raw?.let(PttChannel::valueOf) }.getOrNull()
                ?: PttChannel.forFunction(operationalFunction)
        }

        fun start(
            context: Context,
            teamId: String,
            teamName: String,
            operationalFunction: String,
            channel: PttChannel? = null
        ) {
            if (!isOnDuty(context, teamId)) { Log.w("OperisPtt", "service start ignorado: fora do plantão team=$teamId"); return }
            Log.d("OperisPtt", "service start solicitado team=$teamId function=$operationalFunction channel=${channel?.name ?: "AUTO"}")
            val resolved = channel ?: savedChannel(context, teamId, operationalFunction)
            if (channel != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(teamId, channel.name).apply()
            val intent = Intent(context, PttRadioService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TEAM_ID, teamId)
                .putExtra(EXTRA_TEAM_NAME, teamName)
                .putExtra(EXTRA_OPERATIONAL_FUNCTION, operationalFunction)
                .putExtra(EXTRA_CHANNEL, resolved.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun press(context: Context) {
            context.startService(Intent(context, PttRadioService::class.java).setAction(ACTION_PRESS))
        }

        fun release(context: Context) {
            context.startService(Intent(context, PttRadioService::class.java).setAction(ACTION_RELEASE))
        }

        fun reconnect(context: Context) {
            context.startService(Intent(context, PttRadioService::class.java).setAction(ACTION_RECONNECT))
        }

        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, PttRadioService::class.java))
            PttRadioRuntime.reset()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val managers = linkedMapOf<PttChannel, PttSessionManager>()
    private val managerScopes = linkedMapOf<PttChannel, CoroutineScope>()
    private val stateJobs = linkedMapOf<PttChannel, Job>()
    private var dutyStopJob: Job? = null
    private var currentTeamId: String? = null
    private var currentTeamName: String? = null
    private var currentOperationalFunction: String = ""
    private var currentChannel: PttChannel = PttChannel.TODOS
    private var pressedChannel: PttChannel? = null
    private var localSpeakingChannel: PttChannel? = null
    private val remoteSpeakers = linkedMapOf<PttChannel, String>()
    private lateinit var sounder: PttServiceSounder
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch { managers.values.forEach { it.networkAvailable() } }
        }
    }

    private fun selectedManager(): PttSessionManager? = managers[currentChannel]

    private fun refreshRuntimeSpeaker() {
        localSpeakingChannel?.let { localChannel ->
            PttRadioRuntime.setActiveSpeaker(Local.name.ifBlank { "Você" }, localChannel)
            return
        }
        val selectedSpeaker = remoteSpeakers[currentChannel]
        if (!selectedSpeaker.isNullOrBlank()) {
            PttRadioRuntime.setActiveSpeaker(selectedSpeaker, currentChannel)
            return
        }
        val other = remoteSpeakers.entries.firstOrNull { it.value.isNotBlank() }
        PttRadioRuntime.setActiveSpeaker(other?.value, other?.key)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("OperisPtt", "service onCreate")
        createChannel()
        sounder = PttServiceSounder(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("OperisPtt", "service onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                promote(false)
                val id = intent.getStringExtra(EXTRA_TEAM_ID)
                val name = intent.getStringExtra(EXTRA_TEAM_NAME)
                val operationalFunction = intent.getStringExtra(EXTRA_OPERATIONAL_FUNCTION).orEmpty()
                    .ifBlank { Local.operationalFunction }
                val channel = runCatching { PttChannel.valueOf(intent.getStringExtra(EXTRA_CHANNEL) ?: "") }
                    .getOrDefault(PttChannel.forFunction(operationalFunction))
                if (Local.registered && !id.isNullOrBlank())
                    ensureTeam(id, name ?: id, operationalFunction, channel)
                else stopSelf()
            }
            ACTION_PRESS -> handlePress()
            ACTION_RELEASE -> handleRelease()
            ACTION_RECONNECT -> selectedManager()?.retry()
            else -> {
                promote(false)
                restoreCachedTeam()
            }
        }
        return START_STICKY
    }

    private fun restoreCachedTeam() {
        if (!Local.registered) {
            stopSelf()
            return
        }
        val prefs = TeamPreferences(this)
        val teams = prefs.memberships()
        val selected = prefs.selectedTeam(teams)
        if (selected == null || !isOnDuty(this, selected.teamId)) {
            PttRadioRuntime.reset()
            stopSelf()
        } else {
            val operationalFunction = selected.operationalFunction.ifBlank { Local.operationalFunction }
            ensureTeam(
                selected.teamId,
                selected.displayName,
                operationalFunction,
                savedChannel(this, selected.teamId, operationalFunction)
            )
        }
    }

    private fun ensureTeam(teamId: String, teamName: String, operationalFunction: String, channel: PttChannel) {
        val listening = PttChannel.listeningChannels(operationalFunction)
        val selected = channel.takeIf { it in listening } ?: PttChannel.forFunction(operationalFunction)
        Log.d(
            "OperisPtt",
            "ensureTeam team=$teamId selected=${selected.name} listening=${listening.joinToString { it.name }} managers=${managers.keys.joinToString { it.name }}"
        )

        if (currentTeamId != null && currentTeamId != teamId) {
            disposeAllManagers()
            dutyStopJob?.cancel()
            remoteSpeakers.clear()
            localSpeakingChannel = null
            PttRadioRuntime.setActiveSpeaker(null, null)
            PttRadioRuntime.clearHistory()
        }

        currentTeamId = teamId
        currentTeamName = teamName
        currentOperationalFunction = operationalFunction
        currentChannel = selected
        scheduleDutyStop(teamId)

        // Escuta permanente: canal funcional + Todos. O PTT publica somente no selecionado.
        listening.forEach { listenChannel ->
            if (managers[listenChannel] == null) createChannelManager(teamId, listenChannel)
        }
        managers.keys.filter { it !in listening }.toList().forEach(::disposeChannelManager)
        managers.values.forEach { it.connect() }
        refreshRuntimeSpeaker()

        selectedManager()?.let { selectedManager ->
            PttRadioRuntime.update(teamId, teamName, selected, selectedManager.controller.state)
            updateNotification(selectedManager.controller.state)
        }
    }

    private fun createChannelManager(teamId: String, channel: PttChannel) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val room = LiveKit.create(
            appContext = applicationContext,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType(), audioHandler = NoAudioHandler())
            )
        )
        val bridge = LiveKitRoomBridge(room, scope)
        val newManager = PttSessionManager(teamId, channel, PersistentPttBackend, bridge, scope)
        managerScopes[channel] = scope
        managers[channel] = newManager
        stateJobs[channel] = serviceScope.launch {
            var previous = PttAudioState()
            var remoteActive = false
            newManager.controller.stateFlow.collect { state ->
                if (managers[channel] !== newManager) return@collect

                val currentSpeaker = state.speakingName?.trim()?.takeIf { it.isNotBlank() }
                val previousSpeaker = previous.speaker?.trim()?.takeIf { it.isNotBlank() }
                if (currentSpeaker != previousSpeaker) {
                    if (currentSpeaker == null) {
                        remoteSpeakers.remove(channel)
                    } else {
                        remoteSpeakers[channel] = currentSpeaker
                        PttRadioRuntime.recordTransmission(currentSpeaker, channel)
                    }
                }

                if (state.transmitting && previous.floor != PttFloorPhase.TRANSMITTING) {
                    localSpeakingChannel = channel
                    PttRadioRuntime.recordTransmission(Local.name.ifBlank { "Você" }, channel, local = true)
                } else if (!state.transmitting && previous.floor == PttFloorPhase.TRANSMITTING && localSpeakingChannel == channel) {
                    localSpeakingChannel = null
                }
                refreshRuntimeSpeaker()

                val outcome = pttSound(
                    previous,
                    PttAudioState(state.status, state.floor, state.speakingName),
                    remoteActive
                )
                remoteActive = outcome.remoteActive
                sounder.play(outcome.event)
                previous = PttAudioState(state.status, state.floor, state.speakingName)

                if (currentChannel == channel) {
                    PttRadioRuntime.update(currentTeamId, currentTeamName, channel, state)
                    updateNotification(state)
                } else if (!state.speakingName.isNullOrBlank()) {
                    Log.d("OperisPtt", "recebendo em segundo canal=${channel.name} falante=${state.speakingName}")
                }
            }
        }
        Log.d("OperisPtt", "listener criado channel=${channel.name}")
    }

    private fun disposeChannelManager(channel: PttChannel) {
        stateJobs.remove(channel)?.cancel()
        managers.remove(channel)?.dispose()
        managerScopes.remove(channel)?.cancel()
        remoteSpeakers.remove(channel)
        if (localSpeakingChannel == channel) localSpeakingChannel = null
        refreshRuntimeSpeaker()
    }

    private fun disposeAllManagers() {
        stateJobs.values.forEach { it.cancel() }
        stateJobs.clear()
        managers.values.forEach { it.dispose() }
        managers.clear()
        managerScopes.values.forEach { it.cancel() }
        managerScopes.clear()
        remoteSpeakers.clear()
        localSpeakingChannel = null
        PttRadioRuntime.setActiveSpeaker(null, null)
    }

    private fun scheduleDutyStop(teamId: String) {
        dutyStopJob?.cancel()
        if (BuildConfig.SELF_HOSTED_PTT_LAB) {
            Log.d("OperisPtt", "lab: encerramento automático do rádio por horário suspenso")
            return
        }
        val endAt = dutyEndAt(this, teamId)
        if (endAt <= 0L) return
        val remaining = endAt - System.currentTimeMillis()
        if (remaining <= 0L) {
            setDuty(this, teamId, false)
            stopSelf()
            return
        }
        dutyStopJob = serviceScope.launch {
            delay(remaining)
            if (currentTeamId == teamId) {
                setDuty(this@PttRadioService, teamId, false)
                stopSelf()
            }
        }
    }

    private fun handlePress() {
        val current = selectedManager() ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (current.controller.state.status != PttConnectionStatus.CONNECTED) {
            current.connect()
            return
        }
        val promoted = runCatching { promote(true) }.isSuccess
        if (promoted) {
            pressedChannel = currentChannel
            current.press()
        }
    }

    private fun handleRelease() {
        val releaseChannel = pressedChannel ?: currentChannel
        managers[releaseChannel]?.release()
        pressedChannel = null
        serviceScope.launch {
            delay(150)
            runCatching { promote(false) }
        }
    }

    private fun promote(withMicrophone: Boolean) {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    if (withMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "R\u00e1dio OPERIS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mant\u00e9m o canal de voz da equipe pronto para receber transmiss\u00f5es."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun notification(): Notification {
        val state = selectedManager()?.controller?.state ?: PttRadioRuntime.state.value
        val content = when {
            state.transmitting -> "Transmitindo em ${currentChannel.label} · " + (currentTeamName ?: "equipe")
            !state.speakingName.isNullOrBlank() -> "Recebendo " + state.speakingName + " · ${currentChannel.label}"
            state.status == PttConnectionStatus.CONNECTED -> "Canal ${currentChannel.label} pronto · " + (currentTeamName ?: "equipe")
            state.status == PttConnectionStatus.RECONNECTING -> "Reconectando ao canal de voz"
            state.status == PttConnectionStatus.CONNECTING -> "Conectando ao canal de voz"
            else -> "Aguardando conex\u00e3o com o canal"
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio_custom)
            .setContentTitle("R\u00e1dio OPERIS ativo")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(state: PttUiState) {
        if (currentTeamId == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        Log.d("OperisPtt", "service onDestroy team=$currentTeamId channel=${currentChannel.name}")
        disposeAllManagers()
        pressedChannel = null
        currentTeamId = null
        currentTeamName = null
        currentOperationalFunction = ""
        currentChannel = PttChannel.TODOS
        sounder.release()
        if (::connectivityManager.isInitialized) runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        PttRadioRuntime.reset()
        serviceScope.cancel()
        super.onDestroy()
    }
}
