package br.com.alertaequipe

import org.junit.Assert.assertEquals
import org.junit.Test

class PttChannelListeningTest {
    @Test fun brigadistaListensToRoleAndGeneral() {
        assertEquals(
            linkedSetOf(PttChannel.BRIGADA, PttChannel.TODOS),
            PttChannel.listeningChannels(OperationalFunction.BRIGADISTA.name)
        )
    }

    @Test fun segurancaListensToRoleAndGeneral() {
        assertEquals(
            linkedSetOf(PttChannel.SEGURANCA, PttChannel.TODOS),
            PttChannel.listeningChannels(OperationalFunction.VIGILANTE.name)
        )
        assertEquals(
            linkedSetOf(PttChannel.SEGURANCA, PttChannel.TODOS),
            PttChannel.listeningChannels(OperationalFunction.AGP.name)
        )
    }

    @Test fun unknownFunctionFallsBackToGeneralOnly() {
        assertEquals(setOf(PttChannel.TODOS), PttChannel.listeningChannels(null))
    }
}
