package br.com.alertaequipe

import android.util.Log
import io.livekit.android.ConnectOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class LiveKitEvent {
    data class Connected(val participantCount: Int) : LiveKitEvent()
    data class Reconnecting(val participantCount: Int) : LiveKitEvent()
    data class Reconnected(val participantCount: Int) : LiveKitEvent()
    data class Disconnected(val error: String?) : LiveKitEvent()
    data class ParticipantsChanged(val participantCount: Int) : LiveKitEvent()
    data class ActiveSpeakers(val speakerNames: List<String>) : LiveKitEvent()
}

interface LiveKitBridge {
    val events: Flow<LiveKitEvent>
    suspend fun connect(url: String, token: String)
    fun disconnect()
    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean
    fun participantCount(): Int
}

class LiveKitRoomBridge(
    private val room: Room,
    private val scope: CoroutineScope,
) : LiveKitBridge {
    companion object {
        private const val TAG = "OperisPtt"
    }

    private val _events = MutableSharedFlow<LiveKitEvent>(extraBufferCapacity = 32)
    override val events: Flow<LiveKitEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.d(TAG, "RoomEvent.Connected participantes=${participantCount()}")
                        _events.tryEmit(LiveKitEvent.Connected(participantCount()))
                    }

                    is RoomEvent.Reconnecting -> {
                        Log.d(TAG, "RoomEvent.Reconnecting participantes=${participantCount()}")
                        _events.tryEmit(LiveKitEvent.Reconnecting(participantCount()))
                    }

                    is RoomEvent.Reconnected -> {
                        Log.d(TAG, "RoomEvent.Reconnected participantes=${participantCount()}")
                        _events.tryEmit(LiveKitEvent.Reconnected(participantCount()))
                    }

                    is RoomEvent.FailedToConnect -> {
                        val message = event.error?.message ?: event.error?.javaClass?.simpleName
                        Log.e(TAG, "RoomEvent.FailedToConnect: ${message ?: "unknown"}")
                        _events.tryEmit(LiveKitEvent.Disconnected(message))
                    }

                    is RoomEvent.Disconnected -> {
                        Log.d(TAG, "RoomEvent.Disconnected reason=${event.reason} error=${event.error?.message ?: "none"}")
                        _events.tryEmit(LiveKitEvent.Disconnected(event.error?.message))
                    }

                    is RoomEvent.ParticipantConnected -> {
                        Log.d(TAG, "RoomEvent.ParticipantConnected nome=${event.participant.name} identity=${event.participant.identity?.value ?: "?"} participantes=${participantCount()}")
                        _events.tryEmit(LiveKitEvent.ParticipantsChanged(participantCount()))
                    }

                    is RoomEvent.ParticipantDisconnected -> {
                        Log.d(TAG, "RoomEvent.ParticipantDisconnected participantes=${participantCount()}")
                        _events.tryEmit(LiveKitEvent.ParticipantsChanged(participantCount()))
                    }

                    is RoomEvent.TrackSubscribed ->
                        Log.d(TAG, "RoomEvent.TrackSubscribed participante=${event.participant.name} track=${event.track.javaClass.simpleName} participantes=${participantCount()}")

                    is RoomEvent.TrackUnsubscribed ->
                        Log.d(TAG, "RoomEvent.TrackUnsubscribed participante=${event.participant.name} track=${event.track.javaClass.simpleName}")

                    is RoomEvent.ActiveSpeakersChanged ->
                        _events.tryEmit(LiveKitEvent.ActiveSpeakers(remoteSpeakerNames(event.speakers)))

                    else -> Unit
                }
            }
        }
    }

    override suspend fun connect(url: String, token: String) {
        val host = runCatching { java.net.URI(url).host ?: "?" }.getOrElse { "?" }
        Log.d(TAG, "connect url=${if (url.isBlank()) "VAZIA" else "SIM"} host=$host token=${if (token.isBlank()) "VAZIO" else "SIM(${token.length})"}")
        room.connect(url, token, ConnectOptions(autoSubscribe = true))
        Log.d(TAG, "connect OK estado=${room.state}")
    }

    override fun disconnect() {
        Log.d(TAG, "disconnect estado=${room.state}")
        room.disconnect()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        val ok = runCatching { room.localParticipant.setMicrophoneEnabled(enabled) }.getOrDefault(false)
        Log.d(TAG, "mic enabled=$enabled ok=$ok")
        return ok
    }

    override fun participantCount(): Int =
        if (room.state == Room.State.CONNECTED) room.remoteParticipants.size + 1 else 0

    private fun remoteSpeakerNames(speakers: List<Participant>): List<String> {
        val localSid = runCatching { room.localParticipant.sid }.getOrNull()
        return speakers.asSequence()
            .filter { it.sid != localSid }
            .map { it.name?.takeIf { n -> n.isNotBlank() } ?: it.identity?.value.orEmpty() }
            .filter { it.isNotBlank() }
            .toList()
    }
}