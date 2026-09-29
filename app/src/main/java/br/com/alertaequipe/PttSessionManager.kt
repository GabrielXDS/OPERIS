package br.com.alertaequipe

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Orquestra conexão, pedido/renew/liberação de piso e micrófono sobre o bridge LiveKit.
// O micrófono é um reflexo da máquina de estados: um efeito colateral incondicional.
//
// Conexão é serializada: nunca há dois connect() em voo (o SDK lança IllegalStateException
// se room.connect for chamado duas vezes), e falhas de acesso/connect disparam reconexão
// automática com backoff determinístico 1s/2s/4s/8s. O toque manual em RECONECTAR anula o
// agendamento e tenta na hora.
class PttSessionManager(
    private val teamId: String,
    private val channel: PttChannel,
    private val backend: PttBackend,
    private val bridge: LiveKitBridge,
    private val scope: CoroutineScope,
    private val heartbeatMs: Long = 4_000L,
    private val maxTransmissionMs: Long = 60_000L,
    private val connectionTimeoutMs: Long = 15_000L,
) {
    companion object {
        private const val TAG = "OperisPtt"
    }

    private fun fatalConnectionMessage(message: String?): String? {
        val value = message?.lowercase().orEmpty()
        return when {
            "connection minutes limit exceeded" in value ->
                "Rádio indisponível: limite mensal do serviço de voz atingido."
            "assuma um plantão ativo" in value || "plantão não está ativo" in value ->
                "Rádio disponível somente durante um plantão ativo."
            else -> null
        }
    }

    val controller = PttSessionController()
    private var generation = 0
    private var heartbeatJob: Job? = null
    private var transmissionJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var pendingRetry = false
    private var reconnectAttempt = 0
    private var fatalBlocked = false

    init {
        scope.launch { bridge.events.collect { handleEvent(it) } }
        scope.launch {
            controller.stateFlow.map { it.micEnabled }.distinctUntilChanged().collect { on ->
                if (on) {
                    val ok = runCatching { bridge.setMicrophoneEnabled(true) }.getOrDefault(false)
                    if (!ok) {
                        cancelTransmission()
                        controller.onHeartbeatLost()
                        scope.launch { runCatching { backend.releaseFloor(teamId, channel) } }
                    }
                } else {
                    runCatching { bridge.setMicrophoneEnabled(false) }
                }
            }
        }
    }

    fun connect() {
        if (fatalBlocked) {
            Log.d(TAG, "connect ignorado: falha definitiva bloqueada")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        pendingRetry = false
        reconnectAttempt = 0
        connectCore("manual")
    }

    fun retry() {
        fatalBlocked = false
        connect()
    }

    // Network return interrupts long offline backoff, but never disturbs a healthy session.
    fun networkAvailable() {
        if (fatalBlocked || controller.state.status != PttConnectionStatus.DISCONNECTED) return
        reconnectJob?.cancel()
        reconnectJob = null
        pendingRetry = false
        connectCore("network")
    }

    private fun connectCore(from: String) {
        val status = controller.state.status
        if (status == PttConnectionStatus.CONNECTED ||
            status == PttConnectionStatus.CONNECTING ||
            status == PttConnectionStatus.RECONNECTING
        ) {
            Log.d(TAG, "connect($from) ignorado: status=$status (serializado)")
            return
        }
        val gen = ++generation
        Log.d(TAG, "connect($from) iniciando gen=$gen team=$teamId")
        controller.onConnecting()
        armConnectionTimeout(gen, "connect")
        scope.launch {
            val access = try {
                backend.access(teamId, channel)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "getPttAccess falhou gen=$gen: ${e.javaClass.simpleName}: ${e.message}")
                if (gen == generation) cancelConnectionTimeout()
                val fatal = fatalConnectionMessage(e.message)
                if (gen == generation && fatal != null) {
                    fatalBlocked = true
                    clearReconnectState()
                    controller.onDisconnected(fatal)
                    Log.e(TAG, "acesso PTT recusado definitivamente: ${e.message}")
                } else if (gen == generation) {
                    controller.onDisconnected("Não foi possível acessar o canal.")
                    scheduleReconnect()
                }
                return@launch
            }
            if (gen != generation) return@launch
            val host = runCatching { java.net.URI(access.serverUrl).host }.getOrNull() ?: "?"
            Log.d(TAG, "getPttAccess ok gen=$gen room=${access.roomName} serverUrl=${if (access.serverUrl.isBlank()) "VAZIA" else "SIM"} host=$host token=OK(${access.participantToken.length})")
            try {
                bridge.connect(access.serverUrl, access.participantToken)
                if (gen == generation) {
                    Log.d(TAG, "connect started gen=$gen; waiting RoomEvent.Connected")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "livekit connect falhou gen=$gen: ${e.javaClass.simpleName}: ${e.message}")
                if (gen != generation) return@launch
                cancelConnectionTimeout()
                val fatal = fatalConnectionMessage(e.message)
                if (fatal != null) {
                    fatalBlocked = true
                    clearReconnectState()
                    controller.onDisconnected(fatal)
                    runCatching { bridge.disconnect() }
                    Log.e(TAG, "falha definitiva do serviço de voz: ${e.message}")
                    return@launch
                }
                controller.onDisconnected("Não foi possível conectar ao canal.")
                runCatching { bridge.disconnect() }
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (pendingRetry) return
        pendingRetry = true
        reconnectAttempt += 1
        val attempt = reconnectAttempt
        val gen = generation
        val delay = PttReconnect.delayMs(attempt)
        Log.d(TAG, "scheduleReconnect tentativa=$attempt delayMs=$delay gen=$gen")
        reconnectJob = scope.launch {
            delay(delay)
            if (gen != generation) return@launch
            pendingRetry = false
            reconnectJob = null
            connectCore("auto")
        }
    }

    private fun clearReconnectState() {
        reconnectJob?.cancel()
        reconnectJob = null
        pendingRetry = false
        reconnectAttempt = 0
    }

    private fun armConnectionTimeout(gen: Int, phase: String) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(connectionTimeoutMs)
            if (gen != generation) return@launch
            val status = controller.state.status
            if (status != PttConnectionStatus.CONNECTING && status != PttConnectionStatus.RECONNECTING) return@launch
            Log.w(TAG, "connection timeout phase=$phase gen=$gen status=$status")
            forceReconnect("timeout-$phase", gen)
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    private fun forceReconnect(reason: String, expectedGen: Int = generation) {
        if (expectedGen != generation) return
        generation++
        cancelConnectionTimeout()
        reconnectJob?.cancel()
        reconnectJob = null
        pendingRetry = false
        cancelTransmission()
        controller.onDisconnected(null)
        Log.w(TAG, "forceReconnect reason=$reason gen=$generation")
        runCatching { bridge.disconnect() }
        scheduleReconnect()
    }

    fun press() {
        val token = controller.pressStart()
        if (token == 0) return
        scope.launch {
            val result = try {
                backend.requestFloor(teamId, channel)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                controller.onFloorDenied(token, null)
                return@launch
            }
            if (result.granted) {
                controller.onFloorGranted(token)
                if (controller.state.transmitting) startTransmission()
            } else {
                controller.onFloorDenied(token, result.holderName)
            }
        }
    }

    fun release() {
        controller.releaseStart()
        cancelTransmission()
        scope.launch { runCatching { backend.releaseFloor(teamId, channel) } }
    }

    fun backgrounded() {
        generation++
        cancelConnectionTimeout()
        clearReconnectState()
        cancelTransmission()
        controller.onDisconnected(null)
        Log.d(TAG, "backgrounded: conexão liberada")
        scope.launch {
            runCatching { bridge.setMicrophoneEnabled(false) }
            runCatching { backend.releaseFloor(teamId, channel) }
            bridge.disconnect()
        }
    }

    fun dispose() {
        generation++
        cancelConnectionTimeout()
        clearReconnectState()
        cancelTransmission()
        // Cleanup roda em um escopo próprio: o escopo principal é cancelado abaixo,
        // senão as chamadas de liberação seriam abatidas no meio do caminho.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanup.launch {
            runCatching { bridge.setMicrophoneEnabled(false) }
            runCatching { backend.releaseFloor(teamId, channel) }
            bridge.disconnect()
        }
        scope.cancel()
    }

    private fun startTransmission() {
        cancelTransmission()
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatMs)
                val result = runCatching { backend.renewFloor(teamId, channel) }.getOrNull()
                if (result?.granted != true) {
                    controller.onHeartbeatLost()
                    scope.launch { runCatching { backend.releaseFloor(teamId, channel) } }
                    break
                }
            }
        }
        transmissionJob = scope.launch {
            delay(maxTransmissionMs)
            controller.onMaxTransmissionReached()
            scope.launch { runCatching { backend.releaseFloor(teamId, channel) } }
        }
    }

    private fun cancelTransmission() {
        heartbeatJob?.cancel()
        transmissionJob?.cancel()
        heartbeatJob = null
        transmissionJob = null
    }

    private fun handleEvent(event: LiveKitEvent) {
        when (event) {
            is LiveKitEvent.Connected -> {
                cancelConnectionTimeout()
                clearReconnectState()
                controller.onConnected(event.participantCount)
                Log.d(TAG, "sdk connected participants=${event.participantCount}")
            }
            is LiveKitEvent.Reconnecting -> {
                cancelTransmission()
                controller.onReconnecting()
                armConnectionTimeout(generation, "sdk-reconnecting")
                Log.d(TAG, "sdk reconnecting participantes=${event.participantCount}")
            }
            is LiveKitEvent.Reconnected -> {
                cancelConnectionTimeout()
                clearReconnectState()
                controller.onConnected(event.participantCount)
                Log.d(TAG, "sdk reconnected participantes=${event.participantCount}")
            }
            is LiveKitEvent.Disconnected -> {
                cancelConnectionTimeout()
                cancelTransmission()
                val fatal = fatalConnectionMessage(event.error)
                if (fatal != null) {
                    fatalBlocked = true
                    clearReconnectState()
                    controller.onDisconnected(fatal)
                    runCatching { bridge.disconnect() }
                    Log.e(TAG, "serviço de voz recusou conexão definitivamente: ${event.error}")
                    return
                }
                val liveCount = bridge.participantCount()
                // O LiveKit pode entregar um Disconnected atrasado da sessão anterior depois que
                // a Room já reconectou. Se a Room atual está CONNECTED, a mídia é a fonte de verdade.
                if (liveCount > 0) {
                    clearReconnectState()
                    controller.onConnected(liveCount)
                    Log.d(TAG, "Disconnected tardio ignorado: Room atual conectada participantes=$liveCount")
                } else if (controller.state.status == PttConnectionStatus.DISCONNECTED || pendingRetry) {
                    Log.d(TAG, "Disconnected ignorado (estado já DISCONNECTED ou retry agendado)")
                } else {
                    controller.onDisconnected(if (event.error.isNullOrBlank()) null else "Conexão encerrada.")
                    scope.launch { runCatching { backend.releaseFloor(teamId, channel) } }
                    scheduleReconnect()
                }
            }
            is LiveKitEvent.ParticipantsChanged -> {
                val liveCount = bridge.participantCount()
                Log.d(TAG, "sdk participantsChanged participantes=${event.participantCount} live=$liveCount")
                if (controller.state.status == PttConnectionStatus.DISCONNECTED && liveCount > 0) {
                    clearReconnectState()
                    controller.onConnected(liveCount)
                    Log.d(TAG, "participantes reconciliaram estado para CONNECTED")
                } else {
                    controller.onParticipantsChanged(event.participantCount)
                }
            }
            is LiveKitEvent.ActiveSpeakers -> {
                val speaker = event.speakerNames.firstOrNull()
                val liveCount = bridge.participantCount()
                if (speaker != null && controller.state.status == PttConnectionStatus.DISCONNECTED && liveCount > 0) {
                    clearReconnectState()
                    controller.onConnected(liveCount)
                    Log.d(TAG, "mídia remota reconciliou estado para CONNECTED participantes=$liveCount")
                }
                controller.onSpeakersChanged(speaker)
            }
        }
    }
}