package br.com.alertaequipe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class IncidentType(val label: String) {
    ALARM_TRIGGER("Acionamento de alarme"), MEDICAL("Atendimento Pré-Hospitalar"),
    TRANSPORT("Transporte"), POWER_OUTAGE("Queda de energia"), FIRE("Incêndio"),
    ACCIDENT("Acidente"), SUDDEN_ILLNESS("Mal súbito"), LEAK("Vazamento"),
    ELEVATOR_TRAP("Pessoa presa no elevador"), SECURITY_BREACH("Segurança"), OTHER("Outro")
}

data class IncidentDraft(val type: IncidentType, val title: String, val location: String,
    val description: String, val actionsTaken: String, val reference: String = "") {
    val valid get() = title.trim().length in 3..80 && location.trim().length in 1..100 &&
        description.trim().length in 1..2000 && actionsTaken.length <= 2000 && reference.length <= 80
    fun payload(teamId: String): Map<String, Any?> = mapOf("teamId" to teamId, "type" to type.name,
        "title" to title.trim(), "location" to location.trim(), "description" to description.trim(),
        "actionsTaken" to actionsTaken.trim(), "reference" to reference.trim())
}

data class RecordShareAudit(
    val targetSector: String, val sharedAt: Long, val sharedByUid: String,
    val sharedByName: String, val sharedByFunction: String
) {
    val sectorLabel get() = runCatching { OperationalSector.valueOf(targetSector).label }.getOrDefault(targetSector)
    val formattedTime get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(sharedAt))
    companion object {
        fun fromMap(data: Map<*, *>) = RecordShareAudit(
            data["targetSector"] as? String ?: "",
            (data["sharedAt"] as? Number)?.toLong() ?: 0L,
            data["sharedByUid"] as? String ?: "",
            data["sharedByName"] as? String ?: "",
            data["sharedByFunction"] as? String ?: ""
        )
    }
}

data class Incident(val incidentId: String, val teamId: String, val type: IncidentType,
    val title: String, val location: String, val description: String, val actionsTaken: String,
    val shiftId: String? = null, val shiftLabel: String? = null, val shiftStartedAt: Long? = null,
    val status: String, val authorUid: String, val authorName: String, val createdAt: Long,
    val updatedAt: Long, val editCount: Int, val attachments: List<Attachment> = emptyList(),
    val deleted: Boolean = false, val updatedByUid: String? = null, val updatedByName: String? = null,
    val deletedByUid: String? = null, val deletedByName: String? = null, val deletedAt: Long? = null,
    val ownerSector: String? = null, val sharedWithSectors: List<String> = emptyList(),
    val shareHistory: List<RecordShareAudit> = emptyList(), val authorOperationalFunction: String? = null, val reference: String = "") {
    val formattedTime get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(createdAt))
    companion object {
        fun fromMap(data: Map<*, *>): Incident = Incident(
            data["incidentId"] as String, data["teamId"] as String,
            IncidentType.valueOf(data["type"] as String), data["title"] as String,
            data["location"] as String, data["description"] as String, data["actionsTaken"] as String,
            data["shiftId"] as? String, data["shiftLabel"] as? String, (data["shiftStartedAt"] as? Number)?.toLong(),
            data["status"] as String, data["authorUid"] as String, data["authorName"] as String,
            (data["createdAt"] as Number).toLong(), (data["updatedAt"] as Number).toLong(),
            (data["editCount"] as Number).toInt(),
            (data["attachments"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { m -> Attachment.fromMap(m) } } ?: emptyList(),
            data["deleted"] == true, data["updatedByUid"] as? String, data["updatedByName"] as? String,
            data["deletedByUid"] as? String, data["deletedByName"] as? String,
            (data["deletedAt"] as? Number)?.toLong(), data["ownerSector"] as? String,
            (data["sharedWithSectors"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            (data["shareHistory"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(RecordShareAudit::fromMap) } ?: emptyList(),
            data["authorOperationalFunction"] as? String, data["reference"] as? String ?: "")
    }
}
data class IncidentPage(val records: List<Incident>, val nextCursor: String?)

enum class RoundType(val label: String) {
    OPEN_DOOR("Porta aberta"), EXTINGUISHER_DEPRESSURIZED("Extintor despressurizado"),
    EXTINGUISHER_EXPIRED("Extintor com validade vencida"), BROKEN_ITEM("Item quebrado"),
    BURNT_LIGHTING("Iluminação queimada"), OBSTRUCTED_HYDRANT("Hidrante obstruído"),
    UNLOCKED_ACCESS("Acesso destrancado"), EQUIPMENT_MISPLACED("Equipamento fora do lugar"),
    LEAK_FOUND("Vazamento encontrado"), IRREGULARITY("Irregularidade")
}

data class RoundDraft(val type: RoundType, val title: String, val location: String,
    val description: String, val immediateAction: String) {
    val valid get() = title.trim().length in 3..80 && location.trim().length in 1..100 &&
        description.trim().length in 1..2000 && immediateAction.length <= 2000
    fun payload(teamId: String): Map<String, Any?> = mapOf("teamId" to teamId,
        "findingType" to type.name, "title" to title.trim(), "location" to location.trim(),
        "description" to description.trim(), "immediateAction" to immediateAction.trim())
}

data class RoundReport(val reportId: String, val teamId: String, val findingType: RoundType,
    val title: String, val location: String, val description: String, val immediateAction: String,
    val shiftId: String? = null, val shiftLabel: String? = null, val shiftStartedAt: Long? = null,
    val status: String, val authorUid: String, val authorName: String, val createdAt: Long,
    val updatedAt: Long, val editCount: Int, val attachments: List<Attachment> = emptyList(),
    val deleted: Boolean = false, val updatedByUid: String? = null, val updatedByName: String? = null,
    val deletedByUid: String? = null, val deletedByName: String? = null, val deletedAt: Long? = null,
    val ownerSector: String? = null, val sharedWithSectors: List<String> = emptyList(),
    val shareHistory: List<RecordShareAudit> = emptyList(), val authorOperationalFunction: String? = null) {
    val formattedTime get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(createdAt))
    companion object {
        fun fromMap(data: Map<*, *>): RoundReport = RoundReport(
            data["reportId"] as String, data["teamId"] as String,
            RoundType.valueOf(data["findingType"] as String), data["title"] as String,
            data["location"] as String, data["description"] as String, data["immediateAction"] as String,
            data["shiftId"] as? String, data["shiftLabel"] as? String, (data["shiftStartedAt"] as? Number)?.toLong(),
            data["status"] as String, data["authorUid"] as String, data["authorName"] as String,
            (data["createdAt"] as Number).toLong(), (data["updatedAt"] as Number).toLong(),
            (data["editCount"] as Number).toInt(),
            (data["attachments"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { m -> Attachment.fromMap(m) } } ?: emptyList(),
            data["deleted"] == true, data["updatedByUid"] as? String, data["updatedByName"] as? String,
            data["deletedByUid"] as? String, data["deletedByName"] as? String,
            (data["deletedAt"] as? Number)?.toLong(), data["ownerSector"] as? String,
            (data["sharedWithSectors"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            (data["shareHistory"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(RecordShareAudit::fromMap) } ?: emptyList(),
            data["authorOperationalFunction"] as? String)
    }
}
data class RoundReportPage(val records: List<RoundReport>, val nextCursor: String?)

data class Attachment(val attachmentId: String, val fileName: String, val contentType: String,
    val size: Long, val uploadedByUid: String, val createdAt: Long) {
    val formattedSize get() = when {
        size >= 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
        size >= 1024 -> "%.0f KB".format(size / 1024.0)
        else -> "$size B"
    }
    companion object {
        fun fromMap(data: Map<*, *>): Attachment = Attachment(
            data["attachmentId"] as String, data["fileName"] as String, data["contentType"] as String,
            (data["size"] as Number).toLong(), data["uploadedByUid"] as String,
            (data["createdAt"] as Number).toLong())
    }
}

data class UploadTicket(val ticketId: String, val storagePath: String, val uploadUrl: String,
    val headers: Map<String, String>, val expiresAt: Long)

data class DownloadRef(val downloadUrl: String, val expiresAt: Long)
