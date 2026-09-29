package br.com.alertaequipe

/** UI membership projection. Selection is intentionally not membership/receive authorization. */
data class TeamMembership(
    val teamId: String,
    val displayName: String,
    val role: String = "member",
    val operationalFunction: String = ""
)

enum class OperationalFunction(val label: String) {
    BRIGADISTA("Brigadista"),
    VIGILANTE("Vigilante"),
    AGP("AGP");

    companion object {
        fun labelOf(value: String?): String = entries.firstOrNull { it.name == value }?.label
            ?: value?.takeIf { it.isNotBlank() } ?: "Função não informada"
    }
}

enum class OperationalSector(val label: String) {
    BRIGADA("Brigada"),
    SEGURANCA("Segurança");

    companion object {
        fun fromFunction(value: String?): OperationalSector? = when(value) {
            OperationalFunction.BRIGADISTA.name -> BRIGADA
            OperationalFunction.VIGILANTE.name, OperationalFunction.AGP.name -> SEGURANCA
            else -> null
        }
    }
}

enum class AgpPost(val label: String) {
    PORTARIA_VEICULOS("Portaria de Veículos"),
    BATALHAO("Batalhão");

    companion object {
        fun labelOf(value: String?): String = entries.firstOrNull { it.name == value }?.label
            ?: value?.takeIf { it.isNotBlank() } ?: "Posto não informado"
    }
}

enum class MemberAvailability {
    AVAILABLE,
    PAUSED,
    UNAVAILABLE
}

enum class MemberDisplayStatus {
    ONLINE,
    PAUSED,
    OFFLINE,
    UNAVAILABLE
}

data class TeamMemberPresence(
    val deviceId: String,
    val name: String,
    val availability: MemberAvailability = MemberAvailability.AVAILABLE,
    val pauseReason: String? = null,
    val lastSeenAtMillis: Long = 0L
) {
    fun displayStatus(
        nowMillis: Long = System.currentTimeMillis(),
        offlineAfterMillis: Long = 3 * 60 * 1000L
    ): MemberDisplayStatus {
        if (
            lastSeenAtMillis <= 0L ||
            nowMillis - lastSeenAtMillis > offlineAfterMillis
        ) {
            return MemberDisplayStatus.OFFLINE
        }

        return when (availability) {
            MemberAvailability.AVAILABLE -> MemberDisplayStatus.ONLINE
            MemberAvailability.PAUSED -> MemberDisplayStatus.PAUSED
            MemberAvailability.UNAVAILABLE -> MemberDisplayStatus.UNAVAILABLE
        }
    }
}

object TeamSelection {
    /** Only the real legacy membership is exposed until the server supports multiple teams. */
    fun legacyMemberships(
        registered: Boolean,
        legacyTeamId: String
    ): List<TeamMembership> =
        if (!registered || legacyTeamId.isBlank()) {
            emptyList()
        } else {
            listOf(
                TeamMembership(
                    legacyTeamId,
                    if (legacyTeamId == "brigada_01") "Brigada 01" else legacyTeamId
                )
            )
        }

    fun resolve(
        memberships: List<TeamMembership>,
        selectedTeamId: String?
    ): TeamMembership? =
        memberships.firstOrNull { it.teamId == selectedTeamId }
            ?: memberships.firstOrNull()

    fun receivedTeamName(
        teamId: String,
        memberships: List<TeamMembership>
    ): String =
        memberships.firstOrNull { it.teamId == teamId }?.displayName
            ?: teamId.ifBlank { "Equipe não identificada" }

    /** Never show a different destination while the existing callable infers the legacy team. */
    fun canSendWithLegacyBackend(
        legacyTeamId: String,
        selectedTeamId: String?
    ): Boolean =
        legacyTeamId.isNotBlank() && selectedTeamId == legacyTeamId
}
