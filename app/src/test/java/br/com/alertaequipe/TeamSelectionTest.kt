package br.com.alertaequipe

import org.junit.Assert.*
import org.junit.Test

class TeamSelectionTest {
    @Test fun legacyMembershipIsPreserved() {
        assertEquals(listOf(TeamMembership("brigada_01", "Brigada 01")),
            TeamSelection.legacyMemberships(true, "brigada_01"))
    }
    @Test fun noMembershipIsInventedForUnregisteredDevice() {
        assertTrue(TeamSelection.legacyMemberships(false, "brigada_01").isEmpty())
        assertTrue(TeamSelection.legacyMemberships(true, "").isEmpty())
    }
    @Test fun unknownLegacyTeamKeepsItsActualIdentifier() {
        assertEquals("actual_team", TeamSelection.legacyMemberships(true, "actual_team").single().displayName)
    }
    @Test fun legacySelectionCanInitializeWithoutChangingMemberships() {
        val teams = TeamSelection.legacyMemberships(true, "brigada_01")
        assertEquals("brigada_01", TeamSelection.resolve(teams, null)?.teamId)
        assertEquals(1, teams.size)
    }
    @Test fun staleSelectionFallsBackToAnActualMembership() {
        val teams = TeamSelection.legacyMemberships(true, "brigada_01")
        assertEquals("brigada_01", TeamSelection.resolve(teams, "removed_team")?.teamId)
    }
    @Test fun selectionModelAcceptsMultipleMemberships() {
        // Test fixtures only: no fictional teams are exposed in the running application.
        val teams = listOf(TeamMembership("a", "Equipe A"), TeamMembership("b", "Equipe B"))
        assertEquals("b", TeamSelection.resolve(teams, "b")?.teamId)
        assertEquals(2, teams.size)
    }
    @Test fun noSelectedTeamWithoutMemberships() {
        assertNull(TeamSelection.resolve(emptyList(), "brigada_01"))
    }
    @Test fun legacySendCannotMisrepresentTheDestination() {
        assertTrue(TeamSelection.canSendWithLegacyBackend("brigada_01", "brigada_01"))
        assertFalse(TeamSelection.canSendWithLegacyBackend("brigada_01", "other"))
        assertFalse(TeamSelection.canSendWithLegacyBackend("brigada_01", null))
        assertFalse(TeamSelection.canSendWithLegacyBackend("", ""))
    }
    @Test fun receivedTeamComesFromAlertNotSelection() {
        val teams = listOf(TeamMembership("a", "Equipe A"), TeamMembership("b", "Equipe B"))
        assertEquals("a", TeamSelection.resolve(teams, "a")?.teamId)
        assertEquals("Equipe B", TeamSelection.receivedTeamName("b", teams))
    }
    @Test fun unknownReceivedTeamIsNotReplacedBySelectedTeam() {
        assertEquals("other", TeamSelection.receivedTeamName("other", TeamSelection.legacyMemberships(true, "brigada_01")))
    }
}

