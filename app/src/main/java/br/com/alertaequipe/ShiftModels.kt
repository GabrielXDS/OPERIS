package br.com.alertaequipe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShiftParticipant(
    val uid:String,val name:String,val role:String,val operationalFunction:String,val post:String,
    val startedAt:Long,val endedAt:Long?, val intermediate:Boolean = false
) {
    val functionLabel:String get()=OperationalFunction.labelOf(operationalFunction)
    val postLabel:String get()=if(operationalFunction==OperationalFunction.AGP.name) AgpPost.labelOf(post) else "Móvel"
}

data class ShiftSecurityPost(
    val post:String,
    val name:String,
    val confirmedByUid:String,
    val confirmedByName:String,
    val confirmedAt:Long
) {
    val postLabel:String get()=AgpPost.labelOf(post)
    val formattedAt:String get()=SimpleDateFormat("dd/MM HH:mm",Locale("pt","BR")).format(Date(confirmedAt))
}

data class ShiftPostChange(
    val targetUid:String,val targetName:String,val fromPost:String,val toPost:String,
    val editedByUid:String,val editedByName:String,val editedByFunction:String,val editedByRole:String,val editedAt:Long
) {
    val fromLabel:String get()=AgpPost.labelOf(fromPost)
    val toLabel:String get()=AgpPost.labelOf(toPost)
    val formattedAt:String get()=SimpleDateFormat("dd/MM HH:mm",Locale("pt","BR")).format(Date(editedAt))
}

enum class CoverageReason(val label:String) {
    JANTAR("Janta"),
    NECESSIDADE_OPERACIONAL("Necessidade operacional");

    companion object {
        fun labelOf(value:String?):String=entries.firstOrNull{it.name==value}?.label
            ?: value?.takeIf{it.isNotBlank()} ?: "Motivo não informado"
    }
}

data class ShiftCoverage(
    val coverageId:String,val targetUid:String,val targetName:String,val post:String,
    val coveredByUid:String,val coveredByName:String,val reason:String,
    val recordedByUid:String,val recordedByName:String,val recordedByRole:String,
    val startedAt:Long,val endedAt:Long?,val endedByUid:String?,val endedByName:String?
) {
    val active:Boolean get()=endedAt==null
    val postLabel:String get()=AgpPost.labelOf(post)
    val reasonLabel:String get()=CoverageReason.labelOf(reason)
    val formattedStart:String get()=SimpleDateFormat("dd/MM HH:mm",Locale("pt","BR")).format(Date(startedAt))
    val formattedEnd:String? get()=endedAt?.let{SimpleDateFormat("dd/MM HH:mm",Locale("pt","BR")).format(Date(it))}
}

data class Shift(
    val shiftId:String,val teamId:String,val status:String,val startedAt:Long,val endedAt:Long?,
    val shiftLabel:String,val company:String,val location:String,val previousTeam:String,val nextTeam:String,
    val equipmentNotes:String,val openingNotes:String,val closingNotes:String,val participants:List<ShiftParticipant>,
    val securityPosts:List<ShiftSecurityPost> = emptyList(),
    val postHistory:List<ShiftPostChange> = emptyList(), val coverages:List<ShiftCoverage> = emptyList(),
    val officialStartAt:Long? = null, val officialEndAt:Long? = null, val autoClosed:Boolean = false, val reportGeneratedAt:Long? = null
) {
    val formattedStart:String get()=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("pt","BR")).format(Date(startedAt))
    val formattedEnd:String? get()=endedAt?.let{SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("pt","BR")).format(Date(it))}
    val formattedScheduledEnd:String? get()=officialEndAt?.let{SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("pt","BR")).format(Date(it))}
    companion object {
        fun fromMap(m:Map<*,*>):Shift=Shift(
            m["shiftId"] as String,m["teamId"] as String,m["status"] as String,(m["startedAt"] as Number).toLong(),(m["endedAt"] as? Number)?.toLong(),
            m["shiftLabel"] as? String?:"",m["company"] as? String?:"",m["location"] as? String?:"",m["previousTeam"] as? String?:"",m["nextTeam"] as? String?:"",
            m["equipmentNotes"] as? String?:"",m["openingNotes"] as? String?:"",m["closingNotes"] as? String?:"",
            (m["participants"] as? List<*>)?.mapNotNull{v->(v as? Map<*,*>)?.let{ShiftParticipant(
                it["uid"] as String,it["name"] as String,it["role"] as? String?:"member",
                it["operationalFunction"] as? String?:"",it["post"] as? String?:"",
                (it["startedAt"] as Number).toLong(),(it["endedAt"] as? Number)?.toLong(),it["intermediate"] == true)}}?:emptyList(),
            (m["securityPosts"] as? List<*>)?.mapNotNull{v->(v as? Map<*,*>)?.let{ShiftSecurityPost(
                it["post"] as? String?:"",it["name"] as? String?:"",it["confirmedByUid"] as? String?:"",
                it["confirmedByName"] as? String?:"",(it["confirmedAt"] as? Number)?.toLong()?:0L)}}?:emptyList(),
            (m["postHistory"] as? List<*>)?.mapNotNull{v->(v as? Map<*,*>)?.let{ShiftPostChange(
                it["targetUid"] as? String?:"",it["targetName"] as? String?:"",it["fromPost"] as? String?:"",it["toPost"] as? String?:"",
                it["editedByUid"] as? String?:"",it["editedByName"] as? String?:"",it["editedByFunction"] as? String?:"",it["editedByRole"] as? String?:"member",
                (it["editedAt"] as? Number)?.toLong()?:0L)}}?:emptyList(),
            (m["coverages"] as? List<*>)?.mapNotNull{v->(v as? Map<*,*>)?.let{ShiftCoverage(
                it["coverageId"] as? String?:"",it["targetUid"] as? String?:"",it["targetName"] as? String?:"",it["post"] as? String?:"",
                it["coveredByUid"] as? String?:"",it["coveredByName"] as? String?:"",it["reason"] as? String?:"",
                it["recordedByUid"] as? String?:"",it["recordedByName"] as? String?:"",it["recordedByRole"] as? String?:"member",
                (it["startedAt"] as? Number)?.toLong()?:0L,(it["endedAt"] as? Number)?.toLong(),it["endedByUid"] as? String,it["endedByName"] as? String)}}?:emptyList(),
            (m["officialStartAt"] as? Number)?.toLong(), (m["officialEndAt"] as? Number)?.toLong(), m["autoClosed"] == true,
            (m["reportGeneratedAt"] as? Number)?.toLong())
    }
}

enum class ShiftReportKind(val label:String) {
    BRIGADA("Relatório da Brigada"),
    SEGURANCA("Relatório da Segurança")
}

data class ShiftReportEvent(
    val eventType:String,val recordId:String,val title:String,val category:String,val location:String,
    val description:String,val actionText:String,val authorName:String,val authorOperationalFunction:String,val status:String,
    val createdAt:Long,val attachmentCount:Int,val reference:String = ""
) {
    val kindLabel:String get()=if(eventType=="INCIDENT") "Ocorrência" else "Ronda"
    val categoryLabel:String get()=runCatching {
        if(eventType=="INCIDENT") IncidentType.valueOf(category).label else RoundType.valueOf(category).label
    }.getOrDefault(category)
    val formattedTime:String get()=SimpleDateFormat("dd/MM HH:mm",Locale("pt","BR")).format(Date(createdAt))
}

data class ShiftReport(val shift:Shift,val events:List<ShiftReportEvent>,val incidentCount:Int,val roundCount:Int)
