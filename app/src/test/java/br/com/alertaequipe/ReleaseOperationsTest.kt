package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test

class ReleaseOperationsTest {
    @Test fun incidentContractDoesNotSendPrivilegedFields() {
        val draft=IncidentDraft(IncidentType.MEDICAL,"Teste","Bloco A","Registro fictício","")
        assertTrue(draft.valid)
        assertEquals(setOf("teamId","type","title","location","description","actionsTaken","reference"),draft.payload("a").keys)
        assertEquals("MEDICAL",draft.payload("a")["type"])
        assertFalse(draft.copy(title="ab").valid)
        assertFalse(draft.copy(description="x".repeat(2001)).valid)
    }
    @Test fun roundAllowsOptionalImmediateActionAndCorrectTypes() {
        val draft=RoundDraft(RoundType.OPEN_DOOR,"Teste","Bloco A","Registro fictício","")
        assertTrue(draft.valid)
        assertEquals("OPEN_DOOR",draft.payload("a")["findingType"])
        assertEquals(setOf("teamId","findingType","title","location","description","immediateAction"),draft.payload("a").keys)
        assertEquals(10,RoundType.entries.size)
        assertFalse(draft.copy(location="x".repeat(101)).valid)
    }
    @Test fun attachmentMetadataParsesNumericTypes() {
        val a=Attachment.fromMap(mapOf("attachmentId" to "a","fileName" to "ficticio.pdf","contentType" to "application/pdf","size" to 1024.0,"uploadedByUid" to "u","createdAt" to 1000L))
        assertEquals(1024L,a.size)
        assertEquals(1000L,a.createdAt)
    }
}
