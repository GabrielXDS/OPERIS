package br.com.alertaequipe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PttConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

enum class PttFloorPhase {
    IDLE, REQUESTING, TRANSMITTING, BUSY
}

enum class PttChannel(val label: String) {
    TODOS("Todos"), BRIGADA("Brigada"), SEGURANCA("Seguran\u00e7a");

    companion object {
        fun forFunction(function: String?): PttChannel = when (function) {
            OperationalFunction.BRIGADISTA.name -> BRIGADA
            OperationalFunction.VIGILANTE.name, OperationalFunction.AGP.name -> SEGURANCA
            else -> TODOS
        }

        // Cada integrante escuta permanentemente o canal geral e o canal da própria função.
        // Assim, uma transmissão em TODOS alcança Brigada e Segurança mesmo que a tela do
        // receptor esteja selecionada no canal funcional.
        fun listeningChannels(function: String?): Set<PttChannel> {
            val functional = forFunction(function)
            return if (functional == TODOS) setOf(TODOS) else linkedSetOf(functional, TODOS)
        }
    }
}

data class PttTransmission(
    val speakerName: String,
    val channel: PttChannel,
    val timestamp: Long,
    val local: Boolean = false,
)

// Evento sonoro derivado das transições do rádio (puro e testável).
// Regras OPERIS 4.0:
// - TRANSMISSOR: NÃO toca ptt_start ao começar a transmitir; toca ptt_end ao soltar após
//   transmissão válida (inclui fim de conexão durante a fala); ptt_error somente em erro
//   real (canal ocupado/piso negado, incl. heartbeat perdido).
// - RECEPTOR: toca ptt_start UMA ÚNICA VEZ quando uma transmissão remota começa e ptt_end
//   UMA ÚNICA VEZ quando termina. Falante repetido (state refresh / recomposição) não gera
//   som novo, e o falante próprio (durante transmissão local) jamais gera som de receptor.
enum class PttSoundEvent { NONE, START, END, ERROR }

data class PttAudioState(
    val status: PttConnectionStatus = PttConnectionStatus.DISCONNECTED,
    val floor: PttFloorPhase = PttFloorPhase.IDLE,
    val speaker: String? = null,
)

data class PttSoundOutcome(val event: PttSoundEvent, val remoteActive: Boolean)

internal fun pttSound(
    prev: PttAudioState,
    now: PttAudioState,
    remoteActive: Boolean = false
): PttSoundOutcome {
    val talking = !now.speaker.isNullOrBlank()
    val wasTalking = !prev.speaker.isNullOrBlank()
    val idleNow = now.floor != PttFloorPhase.TRANSMITTING
    val idleBefore = prev.floor != PttFloorPhase.TRANSMITTING
    return when {
        // Erro real do transmissor: canal ocupado (inclui heartbeat perdido) ou piso negado.
        now.floor == PttFloorPhase.BUSY && prev.floor != PttFloorPhase.BUSY ->
            PttSoundOutcome(PttSoundEvent.ERROR, remoteActive)
        // Transmissor solta após transmissão válida (ou conexão cai durante a fala).
        prev.floor == PttFloorPhase.TRANSMITTING && now.floor != PttFloorPhase.TRANSMITTING ->
            PttSoundOutcome(PttSoundEvent.END, remoteActive)
        // Receptor: transmissão remota começou (falante surgiu sem eu estar transmitindo).
        talking && !wasTalking && idleNow && idleBefore ->
            PttSoundOutcome(PttSoundEvent.NONE, true)
        // Receptor: transmissão remota terminou (falante sumiu) — somente se houve START receptor.
        !talking && wasTalking && idleNow && idleBefore && remoteActive ->
            PttSoundOutcome(PttSoundEvent.END, false)
        else -> PttSoundOutcome(PttSoundEvent.NONE, remoteActive)
    }
}

data class PttUiState(
    val status: PttConnectionStatus = PttConnectionStatus.DISCONNECTED,
    val floor: PttFloorPhase = PttFloorPhase.IDLE,
    val connectedCount: Int = 0,
    val speakingName: String? = null,
    val holderName: String? = null,
    val error: String? = null,
) {
    val micEnabled: Boolean
        get() = status == PttConnectionStatus.CONNECTED && floor == PttFloorPhase.TRANSMITTING
    val requesting: Boolean get() = floor == PttFloorPhase.REQUESTING
    val transmitting: Boolean get() = floor == PttFloorPhase.TRANSMITTING
    val busy: Boolean get() = floor == PttFloorPhase.BUSY
}

data class PttAccess(
    val serverUrl: String,
    val participantToken: String,
    val roomName: String,
    val expiresInSeconds: Long,
)

data class PttGrant(val granted: Boolean, val holderName: String? = null)

interface PttBackend {
    suspend fun access(teamId: String, channel: PttChannel): PttAccess
    suspend fun requestFloor(teamId: String, channel: PttChannel): PttGrant
    suspend fun renewFloor(teamId: String, channel: PttChannel): PttGrant
    suspend fun releaseFloor(teamId: String, channel: PttChannel)
}

// Máquina de estados pura do PTT, sem Android e sem I/O: as transições de micrófono
// são decididas aqui e verificadas nos testes de unidade. O gerenciador aplica o efeito.
class PttSessionController {
    private val _state = MutableStateFlow(PttUiState())
    val stateFlow: StateFlow<PttUiState> = _state.asStateFlow()
    val state: PttUiState get() = _state.value

    private var pressToken = 0
    private var pressing = false

    fun onConnecting() {
        pressing = false
        _state.value = state.copy(
            status = PttConnectionStatus.CONNECTING,
            floor = PttFloorPhase.IDLE,
            connectedCount = 0,
            speakingName = null,
            holderName = null,
            error = null,
        )
    }

    fun onConnected(count: Int) {
        pressing = false
        _state.value = state.copy(
            status = PttConnectionStatus.CONNECTED,
            connectedCount = count,
            floor = PttFloorPhase.IDLE,
            speakingName = null,
            holderName = null,
            error = null,
        )
    }

    fun onSpeakersChanged(speakingName: String?) {
        if (state.speakingName != speakingName) _state.value = state.copy(speakingName = speakingName)
    }

    fun onParticipantsChanged(count: Int) {
        if (state.status == PttConnectionStatus.CONNECTED && state.connectedCount != count) {
            _state.value = state.copy(connectedCount = count)
        }
    }

    fun onReconnecting() {
        pressing = false
        _state.value = state.copy(
            status = PttConnectionStatus.RECONNECTING,
            floor = PttFloorPhase.IDLE,
            connectedCount = 0,
            speakingName = null,
            holderName = null,
        )
    }

    fun onDisconnected(error: String?) {
        pressing = false
        _state.value = state.copy(
            status = PttConnectionStatus.DISCONNECTED,
            floor = PttFloorPhase.IDLE,
            connectedCount = 0,
            speakingName = null,
            holderName = null,
            error = error,
        )
    }

    // Retorna o token da solicitação para correlacionar a resposta assíncrona do servidor.
    // 0 significa ignorado (não conectado). Cada pressionar avança o token.
    fun pressStart(): Int {
        if (state.status != PttConnectionStatus.CONNECTED) return 0
        pressToken += 1
        pressing = true
        _state.value = state.copy(floor = PttFloorPhase.REQUESTING, holderName = null)
        return pressToken
    }

    fun releaseStart() {
        pressing = false
        if (state.floor != PttFloorPhase.IDLE) {
            _state.value = state.copy(floor = PttFloorPhase.IDLE, holderName = null)
        }
    }

    fun onFloorGranted(token: Int) {
        if (!pressing || token != pressToken) return
        if (state.status != PttConnectionStatus.CONNECTED) return
        _state.value = state.copy(floor = PttFloorPhase.TRANSMITTING)
    }

    fun onFloorDenied(token: Int, holderName: String?) {
        if (!pressing || token != pressToken) return
        pressing = false
        _state.value = state.copy(floor = PttFloorPhase.BUSY, holderName = holderName)
    }

    fun onHeartbeatLost() {
        if (state.floor == PttFloorPhase.TRANSMITTING) {
            pressing = false
            _state.value = state.copy(floor = PttFloorPhase.BUSY, holderName = null)
        }
    }

    fun onMaxTransmissionReached() {
        if (state.floor == PttFloorPhase.TRANSMITTING) {
            pressing = false
            _state.value = state.copy(floor = PttFloorPhase.IDLE)
        }
    }
}

// Persistent radio: fast recovery first, then battery-friendly offline backoff.
internal object PttReconnect {
    fun delayMs(attempt: Int): Long = when {
        attempt <= 1 -> 1_000L
        attempt == 2 -> 2_000L
        attempt == 3 -> 4_000L
        attempt == 4 -> 8_000L
        attempt == 5 -> 15_000L
        attempt == 6 -> 30_000L
        else -> 60_000L
    }
}