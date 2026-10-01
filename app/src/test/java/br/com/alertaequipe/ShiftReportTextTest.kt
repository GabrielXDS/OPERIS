package br.com.alertaequipe

import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftReportTextTest {
    private fun shift(participants: List<ShiftParticipant>) = Shift(
        shiftId = "s1", teamId = "t1", status = "CLOSED",
        startedAt = 1_800_000_000_000L, endedAt = 1_800_043_200_000L,
        shiftLabel = "19 às 07 - Noturno", company = "Universidade Paulista - UNIP",
        location = "Brasília-DF", previousTeam = "Charlie", nextTeam = "Alfa",
        equipmentNotes = "Sem alterações",
        openingNotes = "Recebemos o plantão da equipe Charlie com todas as alterações e ordens em vigor.",
        closingNotes = "Plantão entregue às 07:00 para equipe Alfa com todas as orientações e assinaturas de acordo.",
        participants = participants
    )

    @Test fun emptySectionsBecomeSemAlteracoes() {
        val report = ShiftReport(shift(emptyList()), emptyList(), 0, 0)
        val text = ShiftReportText.summary(report, "Equipe Delta")
        assertTrue(text.contains("*3° Rondas:* Sem alterações"))
        assertTrue(text.contains("*4° Ocorrências:* Sem alterações"))
    }

    @Test fun foReferenceAppearsInIncidentText() {
        val event = ShiftReportEvent(
            eventType = "INCIDENT", recordId = "i1", title = "Atendimento",
            category = "MEDICAL", location = "Bloco A", description = "Foi realizado atendimento pré-hospitalar",
            actionText = "Paciente orientado", authorName = "Gabriel", authorOperationalFunction = "BRIGADISTA",
            status = "OPEN", createdAt = 1_800_001_000_000L, attachmentCount = 0, reference = "F.O. 748"
        )
        val report = ShiftReport(shift(emptyList()), listOf(event), 1, 0)
        assertTrue(ShiftReportText.full(report, "Equipe Delta").contains("De acordo com a F.O. 748"))
    }

    @Test fun intermediateBrigadistaAppearsWithoutPermanentMembership() {
        val roberta = ShiftParticipant(
            uid = "guest:roberta", name = "Roberta", role = "guest",
            operationalFunction = "BRIGADISTA", post = "",
            startedAt = 1_800_000_000_000L, endedAt = 1_800_010_800_000L, intermediate = true
        )
        val report = ShiftReport(shift(listOf(roberta)), emptyList(), 0, 0)
        val text = ShiftReportText.full(report, "Equipe Delta")
        assertTrue(text.contains("Brigadista intermediária: Roberta"))
    }
    @Test fun activeShiftIsSharedAsPartialReport() {
        val active = shift(emptyList()).copy(status = "ACTIVE", endedAt = null, closingNotes = "", nextTeam = "")
        val text = ShiftReportText.full(ShiftReport(active, emptyList(), 0, 0), "Equipe Delta")
        assertTrue(text.contains("RELATÓRIO PARCIAL"))
        assertTrue(text.contains("Plantão em andamento"))
    }

}
