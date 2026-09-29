package br.com.alertaequipe

import org.junit.Assert.assertEquals
import org.junit.Test

class PttSoundEventTest {
    private val conn = PttConnectionStatus.CONNECTED
    private val drop = PttConnectionStatus.RECONNECTING

    private fun sound(
        prevStatus: PttConnectionStatus = conn,
        status: PttConnectionStatus = conn,
        prevFloor: PttFloorPhase = PttFloorPhase.IDLE,
        floor: PttFloorPhase = PttFloorPhase.IDLE,
        prevSpeaker: String? = null,
        speaker: String? = null,
        remoteActive: Boolean = false,
    ) = pttSound(
        PttAudioState(prevStatus, prevFloor, prevSpeaker),
        PttAudioState(status, floor, speaker),
        remoteActive,
    )

    // TRANSMISSOR: NÃO toca ptt_start ao começar a transmitir.
    @Test fun `transmissor nao toca start ao entrar em transmissao`() =
        assertEquals(PttSoundEvent.NONE, sound(prevFloor = PttFloorPhase.REQUESTING, floor = PttFloorPhase.TRANSMITTING).event)

    @Test fun `nao reinicia ja transmitindo`() =
        assertEquals(PttSoundEvent.NONE, sound(prevFloor = PttFloorPhase.TRANSMITTING, floor = PttFloorPhase.TRANSMITTING).event)

    // TRANSMISSOR: toca ptt_end ao soltar após transmissão válida.
    @Test fun `transmissor toca end ao soltar o ptt`() =
        assertEquals(PttSoundEvent.END, sound(prevFloor = PttFloorPhase.TRANSMITTING, floor = PttFloorPhase.IDLE).event)

    @Test fun `transmissor toca end na queda de conexao durante transmissao`() =
        assertEquals(PttSoundEvent.END, sound(prevStatus = conn, status = drop,
            prevFloor = PttFloorPhase.TRANSMITTING, floor = PttFloorPhase.IDLE).event)

    // TRANSMISSOR: ptt_error somente em erro real.
    @Test fun `transmissor toca erro quando canal ocupado`() =
        assertEquals(PttSoundEvent.ERROR, sound(prevFloor = PttFloorPhase.REQUESTING, floor = PttFloorPhase.BUSY).event)

    @Test fun `transmissor toca erro no heartbeat perdido`() =
        assertEquals(PttSoundEvent.ERROR, sound(prevFloor = PttFloorPhase.TRANSMITTING, floor = PttFloorPhase.BUSY).event)

    @Test fun `nao repete o erro ja ocupado`() =
        assertEquals(PttSoundEvent.NONE, sound(prevFloor = PttFloorPhase.BUSY, floor = PttFloorPhase.BUSY).event)

    // RECEPTOR: toca ptt_start UMA VEZ quando uma transmissão remota começa.
    @Test fun `receptor toca start quando falante remoto surge`() =
        assertEquals(PttSoundEvent.NONE, sound(speaker = "Ana").event)

    @Test fun `receptor ativa remoto apos start`() =
        assertEquals(true, sound(speaker = "Ana").remoteActive)

    @Test fun `receptor nao repete start com falante repetido`() =
        assertEquals(PttSoundEvent.NONE, sound(prevSpeaker = "Ana", speaker = "Ana").event)

    // RECEPTOR: toca ptt_end UMA VEZ quando a transmissão remota termina.
    @Test fun `receptor toca end quando falante remoto some`() =
        assertEquals(PttSoundEvent.END, sound(prevSpeaker = "Ana", speaker = null, remoteActive = true).event)

    @Test fun `receptor desativa remoto apos end`() =
        assertEquals(false, sound(prevSpeaker = "Ana", speaker = null, remoteActive = true).remoteActive)

    @Test fun `receptor nao toca end sem start receptor previo`() =
        assertEquals(PttSoundEvent.NONE, sound(prevSpeaker = "Ana", speaker = null).event)

    // DEDUPE: falante próprio (durante transmissão local) jamais gera som de receptor.
    @Test fun `falante proprio na transmissao local nao gera start`() =
        assertEquals(PttSoundEvent.NONE, sound(prevFloor = PttFloorPhase.REQUESTING, floor = PttFloorPhase.TRANSMITTING,
            speaker = "Eu").event)

    @Test fun `falante proprio saindo apos soltar nao gera end duplicado`() =
        assertEquals(PttSoundEvent.NONE, sound(prevSpeaker = "Eu", speaker = null).event)

    // Transições irrelevantes permanecem mudas.
    @Test fun `nao toca nada em queda fora de transmissao`() =
        assertEquals(PttSoundEvent.NONE, sound(prevStatus = conn, status = drop).event)

    @Test fun `nao toca transicao sem evento`() =
        assertEquals(PttSoundEvent.NONE, sound(prevFloor = PttFloorPhase.IDLE, floor = PttFloorPhase.REQUESTING).event)
}