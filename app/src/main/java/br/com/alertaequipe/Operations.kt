package br.com.alertaequipe

import java.net.URI

data class MemberStatus(val uid: String, val name: String, val role: String, val status: String,
    val operationalFunction: String = "", val pauseReason: String?, val lastSeenAt: Long?, val appReady: Boolean, val appVersion: String?)
data class TeamInvite(val code: String, val active: Boolean, val expiresAt: Long?)
data class EmergencyContact(val id: String, val name: String, val phone: String)
data class TeamDetails(val teamId: String, val name: String, val myRole: String,
    val total: Int, val online: Int, val paused: Int, val offline: Int, val ready: Int,
    val members: List<MemberStatus>, val invite: TeamInvite?, val serverNow: Long,
    val requests: List<MembershipRequest> = emptyList(),
    val emergencyContacts: List<EmergencyContact> = emptyList())
data class MembershipRequest(val uid: String, val name: String, val operationalFunction: String = "", val requestedAt: Long?)
data class MyRequest(val teamId: String, val teamName: String, val status: String, val requestedAt: Long?)
data class JoinResult(val teamId: String, val status: String)
data class InvitePreview(val code: String, val teamId: String, val teamName: String, val expiresAt: Long? = null)
data class TeamAccount(val teams: List<TeamMembership>, val availability: String, val pauseReason: String?,
    val requests: List<MyRequest> = emptyList())

enum class ReleaseStatus { UNKNOWN, UP_TO_DATE, UPDATE_AVAILABLE, REQUIRED }

enum class ReleasePolicyStatus { DRAFT, ANNOUNCED, PUBLISHED }

data class ReleasePolicy(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minSupportedVersionCode: Int,
    val status: ReleasePolicyStatus = ReleasePolicyStatus.PUBLISHED,
    val downloadUrl: String?,
    val releaseNotes: List<String>,
    val sha256: String?,
    val apkSize: Long? = null,
    val cachedAtMs: Long
) {
    /** Policy fetched by the server is trusted (and may block) only inside this window. */
    val validUntilMs: Long get() = cachedAtMs + VALID_FOR_MS
    fun valid(now: Long): Boolean = cachedAtMs >= 0 && now in cachedAtMs..validUntilMs
    /** Somente PUBLISHED com APK válido (URL https + SHA-256 + tamanho) oferece download. */
    fun canDownload(): Boolean =
        status == ReleasePolicyStatus.PUBLISHED && sha256 != null && apkSize != null &&
            downloadUrl != null && ReleaseInfo.validDownload(downloadUrl)
    companion object {
        const val VALID_FOR_MS = 24L * 60 * 60 * 1000
        fun parse(map: Map<*, *>, cachedAtMs: Long): ReleasePolicy? = runCatching {
            val latest = (map["latestVersionCode"] as? Number)?.toInt() ?: return null
            val name = (map["latestVersionName"] as? String)?.trim() ?: return null
            val notes = (map["releaseNotes"] as? List<*>)?.filterIsInstance<String>()
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (latest < 1 || name.isEmpty() || notes.isEmpty()) return null
            val min = ((map["minSupportedVersionCode"] as? Number)?.toInt() ?: latest).coerceIn(1, latest)
            val status = when ((map["status"] as? String)?.uppercase(java.util.Locale.ROOT)) {
                "DRAFT" -> ReleasePolicyStatus.DRAFT
                "ANNOUNCED" -> ReleasePolicyStatus.ANNOUNCED
                else -> ReleasePolicyStatus.PUBLISHED
            }
            val url = (map["downloadUrl"] as? String)?.takeIf { ReleaseInfo.validDownload(it) }
            val sha = (map["sha256"] as? String)?.trim()?.takeIf { SHA256_RE.matches(it) }?.lowercase(java.util.Locale.ROOT)
            val size = ((map["apkSize"] as? Number)?.toLong())?.takeIf { it >= 1 }
            ReleasePolicy(latest, name, min, status, url, notes, sha, size, cachedAtMs)
        }.getOrNull()
        private val SHA256_RE = Regex("[0-9a-fA-F]{64}")
    }
}

object Operational {
    const val ONLINE_MS = 180_000L
    fun canViewLastCommunication(myRole: String?): Boolean = myRole == "owner" || myRole == "admin"
    fun normalizeInvite(input: String): String = input.filterNot { it.isWhitespace() || it == '-' }.uppercase(java.util.Locale.ROOT)
    fun validInvite(input: String): Boolean = normalizeInvite(input).let {
        it.length == 8 && it.all { c -> c in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" }
    }
    fun formatInvite(code: String): String = code.chunked(4).joinToString("-")
    fun status(lastSeen: Long?, availability: String, now: Long): String =
        if (lastSeen == null || now - lastSeen > ONLINE_MS || lastSeen > now + 5000) "offline"
        else if (availability == "paused") "paused" else "online"
    fun validAvailability(value: String, reason: String?): Boolean =
        (value == "available" && reason == null) || (value == "paused" && reason in listOf("lunch","dinner","break","other"))
    fun pauseLabel(reason: String?): String = when(reason) {
        "lunch" -> "Almoço"; "dinner" -> "Janta"; "break" -> "Intervalo"; else -> "Outro"
    }
    fun statusLabel(value: String?, reason: String?): String =
        if (value == "paused") (if (reason == "other") "Indisponível" else "Em pausa") else "Disponível"
    fun shouldShowRelease(current: Int, lastSeen: Int?): Boolean = current != lastSeen
    fun classifyRelease(currentVersionCode: Int, policy: ReleasePolicy?, now: Long = System.currentTimeMillis()): ReleaseStatus {
        if (policy == null || !policy.valid(now)) return ReleaseStatus.UNKNOWN
        return when {
            currentVersionCode >= policy.latestVersionCode -> ReleaseStatus.UP_TO_DATE
            // Rascunhos/anúncios não oferecem download nem bloqueiam versões antigas.
            policy.status != ReleasePolicyStatus.PUBLISHED -> ReleaseStatus.UNKNOWN
            currentVersionCode < policy.minSupportedVersionCode -> ReleaseStatus.REQUIRED
            else -> ReleaseStatus.UPDATE_AVAILABLE
        }
    }
    fun validTeamIdentifier(value: String?): Boolean =
        value != null && value.length in 1..64 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    fun validAlertPayload(
        type: String?,
        alertId: String?,
        teamId: String?,
        senderDeviceId: String?,
        senderName: String?,
        timestamp: Long?,
        currentDeviceId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (type != "PANIC_ALERT") return false
        if (alertId == null || !alertId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) return false
        if (!validTeamIdentifier(teamId)) return false
        if (senderDeviceId.isNullOrBlank() || senderDeviceId == currentDeviceId) return false
        val cleanName = senderName?.trim() ?: return false
        if (cleanName.isEmpty() || cleanName.length > 40) return false
        if (timestamp == null) return false
        val age = now - timestamp
        if (age < -30_000 || age >= 60_000) return false
        return true
    }
    fun validAlertPayload(
        data: Map<String, String>,
        currentDeviceId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = validAlertPayload(
        type = data["type"],
        alertId = data["alertId"],
        teamId = data["teamId"],
        senderDeviceId = data["senderDeviceId"],
        senderName = data["senderName"],
        timestamp = data["timestamp"]?.toLongOrNull(),
        currentDeviceId = currentDeviceId,
        now = now
    )
    fun validRecordPayload(
        type: String?,
        recordType: String?,
        recordId: String?,
        teamId: String?,
        createdBy: String?,
        title: String?,
        createdAt: Long?,
        currentDeviceId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (type != "record_created") return false
        if (recordType != "occurrence" && recordType != "round") return false
        if (recordId == null || recordId.isEmpty() || recordId.length > 64 ||
            !recordId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return false
        if (!validTeamIdentifier(teamId)) return false
        if (createdBy.isNullOrBlank() || createdBy == currentDeviceId) return false
        val cleanTitle = title?.trim() ?: return false
        if (cleanTitle.isEmpty() || cleanTitle.length > 80) return false
        if (createdAt == null) return false
        val age = now - createdAt
        if (age < -30_000 || age > 4L * 60 * 60 * 1000) return false
        return true
    }
    fun validRecordPayload(
        data: Map<String, String>,
        currentDeviceId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = validRecordPayload(
        type = data["type"],
        recordType = data["recordType"],
        recordId = data["recordId"],
        teamId = data["teamId"],
        createdBy = data["createdBy"],
        title = data["title"],
        createdAt = data["createdAt"]?.toLongOrNull(),
        currentDeviceId = currentDeviceId,
        now = now
    )
}
object ReleaseInfo {
    val versionName get() = BuildConfig.VERSION_NAME
    val title = "NOVIDADES DA OPERIS"
    // Local fallback notes for offline "novidades" and settings. The server policy, when present,
    // is the source of truth for release notes and the download package.
    val releaseNotes = listOf(
        "Sirene de emergência agora pode abrir em tela cheia sobre a tela bloqueada quando autorizado pelo Android",
        "Botão SILENCIAR disponível na tela de emergência e na notificação da tela bloqueada",
        "Diagnóstico orienta a liberar a permissão de alertas em tela cheia no Android 14 ou superior",
        "Proprietários e administradores agora podem remover integrantes da equipe com confirmação e regras de segurança"
    )
    fun validDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.isAbsolute && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null
    }.getOrDefault(false)
    fun shareMessage(policy: ReleasePolicy): String = "Nova versão do OPERIS disponível.\n\nVersão ${policy.latestVersionName}\n\nNovidades:\n" +
        policy.releaseNotes.joinToString("\n") { "• $it" } +
        "\n\nInstale a nova versão por cima da versão atual.\n\nDownload:\n${policy.downloadUrl ?: "ainda não publicado"}"
    fun shareMessage(): String = "Nova atualização do OPERIS disponível.\n\nVersão $versionName\n\nNovidades:\n" +
        releaseNotes.joinToString("\n") { "• $it" } + "\n\nInstale a nova versão por cima da versão atual.\n\nDownload:\n${downloadUrl}"
    private const val downloadUrl = ""
}
