package br.com.alertaequipe

import android.content.Context
import android.util.Patterns
import androidx.work.*
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Backend {
    private val functions get() = FirebaseFunctions.getInstance("southamerica-east1")
    private val syncMutex = Mutex()
    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *> =
        functions.getHttpsCallable(name).call(data + mapOf("deviceId" to Local.installationId())).await().data as Map<*, *>
    private suspend fun token() = FirebaseMessaging.getInstance().token.await()
    private fun identity() {
        check(FirebaseAuth.getInstance().currentUser?.uid == Local.uid && Local.registered) {
            "Identidade local indisponível."
        }
    }
    suspend fun profile(name: String) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously().await()
        val result = call("upsertProfile", mapOf(
            "name" to name.trim(),
            "token" to token()
        ))
        Local.register(result["deviceId"] as String, result["uid"] as String, Local.teamId,
            result["name"] as String, result["operationalFunction"] as? String)
    }
    suspend fun protectAccount(email: String, password: String): ProtectedAccount {
        val cleanEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches())
            throw AccountProtectionException("Informe um e-mail válido.")
        if (password.length < 6)
            throw AccountProtectionException("A senha precisa ter pelo menos 6 caracteres.")

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: throw AccountProtectionException("Não foi possível identificar esta conta.")
        if (!user.isAnonymous)
            throw AccountProtectionException("Esta conta já está protegida.")

        val uidBefore = user.uid
        try {
            user.linkWithCredential(EmailAuthProvider.getCredential(cleanEmail, password)).await()
        } catch (e: FirebaseNetworkException) {
            throw AccountProtectionException("Sem conexão. Confira a internet e tente novamente.", e)
        } catch (e: FirebaseAuthException) {
            val message = when (e.errorCode) {
                "ERROR_INVALID_EMAIL" -> "Informe um e-mail válido."
                "ERROR_WEAK_PASSWORD" -> "A senha informada é fraca. Use pelo menos 6 caracteres."
                "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_EMAIL_ALREADY_IN_USE" ->
                    "Este e-mail já está vinculado a outra conta."
                "ERROR_PROVIDER_ALREADY_LINKED" -> "Esta conta já está protegida."
                else -> "Não foi possível proteger a conta. Tente novamente."
            }
            throw AccountProtectionException(message, e)
        }
        val protectedUser = auth.currentUser
            ?: throw AccountProtectionException("Não foi possível confirmar a proteção da conta.")
        check(protectedUser.uid == uidBefore) { "O UID foi alterado durante a proteção da conta." }
        return ProtectedAccount(uidBefore, protectedUser.email ?: cleanEmail)
    }
    suspend fun migrateExistingSession(): Boolean {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null || Local.registered) return Local.registered
        return try {
            val result = call("upsertProfile", mapOf("token" to token()))
            Local.register(result["deviceId"] as String, result["uid"] as String,
                result["lastSelectedTeamId"] as? String ?: "", result["name"] as String,
                result["operationalFunction"] as? String)
            Local.operationalFunction.isNotBlank()
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) false else throw e
        }
    }
    suspend fun ensureProfile() {
        identity()
        val currentToken = token()
        val result = call("upsertProfile", buildMap {
            put("token", currentToken)
            if (Local.name.isNotBlank()) put("name", Local.name)
            if (Local.operationalFunction.isNotBlank()) put("operationalFunction", Local.operationalFunction)
        })
        Local.register(result["deviceId"] as String, result["uid"] as String, Local.teamId,
            result["name"] as String, result["operationalFunction"] as? String)
    }
    suspend fun signIn(email: String, password: String) {
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) throw AccountProtectionException("Informe um e-mail válido.")
        if (password.isBlank()) throw AccountProtectionException("Informe a senha.")
        try { FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await() }
        catch (e: FirebaseNetworkException) { throw AccountProtectionException("Sem conexão. Confira a internet e tente novamente.", e) }
        catch (e: FirebaseAuthException) { throw AccountProtectionException("E-mail ou senha inválidos, ou conta não protegida.", e) }
        Local.clearAccount()
        Local.installationId()
        val result = call("upsertProfile", mapOf("token" to token()))
        Local.register(result["deviceId"] as String, result["uid"] as String,
            result["lastSelectedTeamId"] as? String ?: "", result["name"] as String,
            result["operationalFunction"] as? String)
    }
    suspend fun logout() {
        if (Local.registered) call("deactivateDevice")
        FirebaseAuth.getInstance().signOut()
        Local.clearAccount()
    }
    suspend fun sync(diagnostics: AlertDiagnostics? = null) = syncMutex.withLock {
        identity()
        val report = diagnostics?.let { mapOf("notificationsEnabled" to it.notificationsEnabled,
            "channelReady" to it.channelReady, "fullScreenIntentAllowed" to it.fullScreenIntentAllowed,
            "audioReady" to (!it.doNotDisturb && !it.audioWarning),
            "appVersion" to BuildConfig.VERSION_NAME) }
        call("syncDevice", mapOf("token" to token(), "modernClient" to true, "diagnostics" to report))
        Unit
    }
    suspend fun teams(): TeamAccount {
        identity()
        val result = call("listMyTeams")
        val teams = (result["teams"] as? List<*>)?.map { value ->
            val t = value as Map<*, *>
            TeamMembership(t["teamId"] as String, t["teamName"] as String, t["role"] as String, t["operationalFunction"] as? String ?: "",
                TeamShiftSchedule.fromMap(t["shiftSchedule"]))
        } ?: emptyList()
        if (Local.teamId.isBlank() && teams.isNotEmpty())
            Local.register(Local.deviceId, Local.uid, teams.first().teamId, result["name"] as String)
        val requests=(result["requests"] as? List<*>)?.map {
            val r=it as Map<*,*>
            MyRequest(r["teamId"] as String,r["teamName"] as String,r["status"] as String,(r["requestedAt"] as? Number)?.toLong())
        } ?: emptyList()
        return TeamAccount(teams, result["availability"] as? String ?: "available", result["pauseReason"] as? String,requests)
    }
    suspend fun createTeam(name: String, requestId: String, operationalFunction: OperationalFunction): String {
        identity()
        return call("createTeam", mapOf("name" to name, "requestId" to requestId, "operationalFunction" to operationalFunction.name))["teamId"] as String
    }
    suspend fun preview(code: String): InvitePreview {
        identity()
        val normalized = Operational.normalizeInvite(code)
        val r = call("previewTeamInvite", mapOf("inviteCode" to normalized))
        return InvitePreview(normalized,r["teamId"] as String,r["teamName"] as String,(r["expiresAt"] as? Number)?.toLong())
    }
    suspend fun join(preview: InvitePreview, operationalFunction: OperationalFunction): JoinResult {
        identity()
        val r=call("joinTeam", mapOf("inviteCode" to preview.code, "expectedTeamId" to preview.teamId, "operationalFunction" to operationalFunction.name))
        return JoinResult(r["teamId"] as String,r["status"] as String)
    }
    suspend fun review(teamId:String,uid:String,approve:Boolean) {
        identity()
        call("reviewMembership",mapOf("teamId" to teamId,"uid" to uid,"action" to if(approve) "approve" else "reject"))
    }
    suspend fun details(teamId: String): TeamDetails {
        identity()
        val r = call("getTeamDetails",mapOf("teamId" to teamId))
        fun number(key: String) = (r[key] as? Number)?.toInt() ?: 0
        val invite = (r["invite"] as? Map<*, *>)?.let {
            TeamInvite(it["code"] as String,it["active"] == true,(it["expiresAt"] as? Number)?.toLong())
        }
        val members = (r["members"] as? List<*>)?.map { value ->
            val m = value as Map<*, *>
            MemberStatus(m["uid"] as String,m["name"] as String,m["role"] as String,m["status"] as String,
                m["operationalFunction"] as? String ?: "",m["pauseReason"] as? String,(m["lastSeenAt"] as? Number)?.toLong(),m["appReady"] == true,m["appVersion"] as? String)
        } ?: emptyList()
        val requests=(r["requests"] as? List<*>)?.map {
            val m=it as Map<*,*>
            MembershipRequest(m["uid"] as String,m["name"] as String,m["operationalFunction"] as? String ?: "",(m["requestedAt"] as? Number)?.toLong())
        } ?: emptyList()
        val emergencyContacts=(r["emergencyContacts"] as? List<*>)?.mapNotNull { value ->
            val m=value as? Map<*,*> ?: return@mapNotNull null
            val id=m["id"] as? String ?: return@mapNotNull null
            val name=m["name"] as? String ?: return@mapNotNull null
            val phone=m["phone"] as? String ?: return@mapNotNull null
            EmergencyContact(id,name,phone)
        } ?: emptyList()
        return TeamDetails(r["teamId"] as String,r["teamName"] as String,r["myRole"] as String,
            number("totalMembers"),number("onlineCount"),number("pausedCount"),number("offlineCount"),number("readyCount"),
            members,invite,(r["serverNow"] as Number).toLong(),requests,emergencyContacts,TeamShiftSchedule.fromMap(r["shiftSchedule"]))
    }
    suspend fun updateMemberFunction(teamId: String, uid: String, operationalFunction: OperationalFunction) {
        identity()
        call("updateMemberFunction", mapOf("teamId" to teamId, "uid" to uid, "operationalFunction" to operationalFunction.name))
    }
    suspend fun removeTeamMember(teamId: String, uid: String) {
        identity()
        call("removeTeamMember", mapOf("teamId" to teamId, "uid" to uid))
    }
    suspend fun dissolveTeam(teamId: String) {
        identity()
        call("dissolveTeam", mapOf("teamId" to teamId))
    }
    suspend fun setEmergencyContacts(teamId: String, contacts: List<EmergencyContact>): List<EmergencyContact> {
        identity()
        val payload=contacts.map { mapOf("id" to it.id,"name" to it.name,"phone" to it.phone) }
        val r=call("setEmergencyContacts",mapOf("teamId" to teamId,"contacts" to payload))
        return (r["contacts"] as? List<*>)?.mapNotNull { value ->
            val m=value as? Map<*,*> ?: return@mapNotNull null
            EmergencyContact(m["id"] as String,m["name"] as String,m["phone"] as String)
        } ?: emptyList()
    }
    suspend fun setTeamShiftSchedule(teamId: String, startTime: String): TeamShiftSchedule {
        identity()
        val r=call("setTeamShiftSchedule",mapOf("teamId" to teamId,"startTime" to startTime.trim()))
        return TeamShiftSchedule.fromMap(r["shiftSchedule"])
    }
    suspend fun status(availability: String, reason: String?) {
        identity()
        require(Operational.validAvailability(availability,reason))
        call("setAvailability",mapOf("availability" to availability,"pauseReason" to reason))
    }
    suspend fun selectActiveTeam(teamId: String) {
        identity()
        call("selectActiveTeam", mapOf("teamId" to teamId))
    }
    suspend fun getAndroidRelease(): ReleasePolicy {
        val result = call("getAndroidRelease")
        return ReleasePolicy.parse(result, System.currentTimeMillis())
            ?: throw Exception("Dados de atualização inválidos.")
    }
    suspend fun startShift(teamId:String, shiftLabel:String="", company:String="", location:String="",
        previousTeam:String="", post:String="", portariaName:String="", batalhaoName:String=""):Shift {
        identity()
        val r=call("startShift",mapOf("teamId" to teamId,"shiftLabel" to shiftLabel,"company" to company,
            "location" to location,"previousTeam" to previousTeam,"post" to post,
            "portariaName" to portariaName,"batalhaoName" to batalhaoName))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }
    suspend fun activeShift(teamId:String):Shift? {
        identity(); val r=call("getActiveShift",mapOf("teamId" to teamId))
        return (r["shift"] as? Map<*,*>)?.let { Shift.fromMap(it) }
    }
    suspend fun listShifts(teamId:String):List<Shift> {
        identity(); val r=call("listShifts",mapOf("teamId" to teamId))
        return (r["shifts"] as? List<*>)?.mapNotNull { (it as? Map<*,*>)?.let(Shift::fromMap) } ?: emptyList()
    }
    suspend fun shiftReport(teamId:String, shiftId:String, kind:ShiftReportKind):ShiftReport {
        identity(); val r=call("getShiftReport",mapOf("teamId" to teamId,"shiftId" to shiftId,"sector" to kind.name))
        val shift=Shift.fromMap(r["shift"] as Map<*,*>)
        val events=(r["events"] as? List<*>)?.mapNotNull { value ->
            val m=value as? Map<*,*> ?: return@mapNotNull null
            ShiftReportEvent(
                m["eventType"] as? String ?: "",m["recordId"] as? String ?: "",m["title"] as? String ?: "",
                m["category"] as? String ?: "",m["location"] as? String ?: "",m["description"] as? String ?: "",
                m["actionText"] as? String ?: "",m["authorName"] as? String ?: "",m["authorOperationalFunction"] as? String ?: "",
                m["status"] as? String ?: "",(m["createdAt"] as? Number)?.toLong() ?: 0L,(m["attachmentCount"] as? Number)?.toInt() ?: 0,
                m["reference"] as? String ?: "")
        } ?: emptyList()
        return ShiftReport(shift,events,(r["incidentCount"] as? Number)?.toInt() ?: 0,(r["roundCount"] as? Number)?.toInt() ?: 0)
    }
    suspend fun addIntermediateBrigadista(teamId:String, shiftId:String, name:String, startedAt:Long, endedAt:Long):Shift {
        identity(); val r=call("addIntermediateBrigadista",mapOf("teamId" to teamId,"shiftId" to shiftId,"name" to name,
            "startedAt" to startedAt,"endedAt" to endedAt))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }
    suspend fun confirmSecurityPosts(teamId:String, shiftId:String, portariaName:String, batalhaoName:String):Shift {
        identity()
        val r=call("confirmSecurityPosts",mapOf(
            "teamId" to teamId,"shiftId" to shiftId,
            "portariaName" to portariaName.trim(),"batalhaoName" to batalhaoName.trim()
        ))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }
    suspend fun updateAgpPost(teamId:String, shiftId:String, targetUid:String, post:AgpPost):Shift {
        identity()
        val r=call("updateAgpPost",mapOf("teamId" to teamId,"shiftId" to shiftId,
            "targetUid" to targetUid,"post" to post.name))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }
    suspend fun startAgpCoverage(teamId:String, shiftId:String, targetUid:String,
        coveringUid:String, reason:CoverageReason):Shift {
        identity()
        val r=call("startAgpCoverage",mapOf("teamId" to teamId,"shiftId" to shiftId,
            "targetUid" to targetUid,"coveringUid" to coveringUid,"reason" to reason.name))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }
    suspend fun finishAgpCoverage(teamId:String, shiftId:String, coverageId:String):Shift {
        identity()
        val r=call("finishAgpCoverage",mapOf("teamId" to teamId,"shiftId" to shiftId,"coverageId" to coverageId))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }
    suspend fun finishShift(teamId:String, shiftId:String, nextTeam:String="", closingNotes:String=""):Shift {
        identity(); val r=call("finishShift",mapOf("teamId" to teamId,"shiftId" to shiftId,"nextTeam" to nextTeam,"closingNotes" to closingNotes))
        return Shift.fromMap(r["shift"] as Map<*,*>)
    }    suspend fun getPttAccess(teamId: String, channel: PttChannel): PttAccess {
        identity()
        val accessFunction = if (BuildConfig.SELF_HOSTED_PTT_LAB) "getPttAccessLabV2" else "getPttAccess"
        val r = call(accessFunction, mapOf("teamId" to teamId, "channel" to channel.name))
        return PttAccess(r["serverUrl"] as String, r["participantToken"] as String,
            r["roomName"] as String, (r["expiresInSeconds"] as Number).toLong())
    }
    suspend fun readState(teamId: String): ReadState {
        identity()
        val r = call("unreadState", mapOf("teamId" to teamId))
        return ReadState(
            teamId = r["teamId"] as String,
            unreadIncidents = (r["unreadIncidents"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            unreadRounds = (r["unreadRounds"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            lastSeenIncidentAt = (r["lastSeenIncidentAt"] as? Number)?.toLong(),
            lastSeenRoundAt = (r["lastSeenRoundAt"] as? Number)?.toLong(),
            serverNow = (r["serverNow"] as? Number)?.toLong() ?: 0L,
        )
    }
    suspend fun markRecordsSeen(teamId: String, recordType: String, seenThrough: Long) {
        identity()
        call("markRecordsSeen", mapOf("teamId" to teamId, "recordType" to recordType, "seenThrough" to seenThrough))
    }
    suspend fun requestPttFloor(teamId: String, channel: PttChannel): PttGrant {
        identity()
        val name = if (BuildConfig.SELF_HOSTED_PTT_LAB) "requestPttFloorLabV2" else "requestPttFloor"
        val r = call(name, mapOf("teamId" to teamId, "channel" to channel.name))
        return PttGrant(r["granted"] == true, r["holderName"] as? String)
    }
    suspend fun renewPttFloor(teamId: String, channel: PttChannel): PttGrant {
        identity()
        val name = if (BuildConfig.SELF_HOSTED_PTT_LAB) "renewPttFloorLabV2" else "renewPttFloor"
        val r = call(name, mapOf("teamId" to teamId, "channel" to channel.name))
        return PttGrant(r["granted"] == true, r["holderName"] as? String)
    }
    suspend fun releasePttFloor(teamId: String, channel: PttChannel) {
        identity()
        val name = if (BuildConfig.SELF_HOSTED_PTT_LAB) "releasePttFloorLabV2" else "releasePttFloor"
        call(name, mapOf("teamId" to teamId, "channel" to channel.name))
    }
    suspend fun manageInvite(teamId: String, action: String) {
        identity()
        call("manageTeamInvite",mapOf("teamId" to teamId,"action" to action))
    }
    suspend fun send(id: String, teamId: String) {
        identity()
        call("triggerAlert",mapOf("alertId" to id,"teamId" to teamId))
    }
    suspend fun createIncident(teamId: String, shiftId: String, draft: IncidentDraft): Incident {
        val data = draft.payload(teamId).toMutableMap()
        data["shiftId"] = shiftId
        val result = call("createIncident", data)
        return Incident.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun listIncidents(teamId: String, beforeId: String? = null, deletedOnly: Boolean = false): IncidentPage {
        val data = mutableMapOf<String, Any?>("teamId" to teamId, "deletedOnly" to deletedOnly)
        if (beforeId != null) data["beforeId"] = beforeId
        val result = call("listIncidents", data)
        return IncidentPage((result["records"] as List<*>).map { Incident.fromMap(it as Map<*, *>) },
            result["nextCursor"] as? String)
    }
    suspend fun getIncidentDetails(teamId: String, incidentId: String): Incident {
        val result = call("getIncidentDetails", mapOf("teamId" to teamId, "incidentId" to incidentId))
        return Incident.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun shareIncident(teamId: String, incidentId: String, targetSector: OperationalSector): Incident {
        val result = call("shareIncident", mapOf("teamId" to teamId, "incidentId" to incidentId, "targetSector" to targetSector.name))
        return Incident.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun updateIncident(teamId: String, incidentId: String, draft: IncidentDraft): Incident {
        val data = draft.payload(teamId).toMutableMap()
        data["incidentId"] = incidentId
        val result = call("updateIncident", data)
        return Incident.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun softDeleteIncident(teamId: String, incidentId: String): Incident {
        val result = call("softDeleteIncident", mapOf("teamId" to teamId, "incidentId" to incidentId))
        return Incident.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun createRoundReport(teamId: String, shiftId: String, draft: RoundDraft): RoundReport {
        val data = draft.payload(teamId).toMutableMap()
        data["shiftId"] = shiftId
        val result = call("createRoundReport", data)
        return RoundReport.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun listRoundReports(teamId: String, beforeId: String? = null, deletedOnly: Boolean = false): RoundReportPage {
        val data = mutableMapOf<String, Any?>("teamId" to teamId, "deletedOnly" to deletedOnly)
        if (beforeId != null) data["beforeId"] = beforeId
        val result = call("listRoundReports", data)
        return RoundReportPage((result["records"] as List<*>).map { RoundReport.fromMap(it as Map<*, *>) },
            result["nextCursor"] as? String)
    }
    suspend fun getRoundReportDetails(teamId: String, reportId: String): RoundReport {
        val result = call("getRoundReportDetails", mapOf("teamId" to teamId, "reportId" to reportId))
        return RoundReport.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun shareRoundReport(teamId: String, reportId: String, targetSector: OperationalSector): RoundReport {
        val result = call("shareRoundReport", mapOf("teamId" to teamId, "reportId" to reportId, "targetSector" to targetSector.name))
        return RoundReport.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun updateRoundReport(teamId: String, reportId: String, draft: RoundDraft): RoundReport {
        val data = draft.payload(teamId).toMutableMap()
        data["reportId"] = reportId
        val result = call("updateRoundReport", data)
        return RoundReport.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun softDeleteRoundReport(teamId: String, reportId: String): RoundReport {
        val result = call("softDeleteRoundReport", mapOf("teamId" to teamId, "reportId" to reportId))
        return RoundReport.fromMap(result["record"] as Map<*, *>)
    }
    suspend fun requestAttachmentUpload(teamId: String, recordType: String, recordId: String,
        fileName: String, contentType: String): UploadTicket {
        identity()
        val r = call("requestAttachmentUpload", mapOf("teamId" to teamId, "recordType" to recordType,
            "recordId" to recordId, "fileName" to fileName, "contentType" to contentType))
        @Suppress("UNCHECKED_CAST")
        return UploadTicket(r["ticketId"] as String, r["storagePath"] as String,
            r["uploadUrl"] as String, (r["headers"] as Map<*, *>) as Map<String, String>,
            (r["expiresAt"] as? Number)?.toLong() ?: 0L)
    }
    suspend fun finalizeAttachment(ticketId: String): Attachment {
        identity()
        val r = call("finalizeAttachment", mapOf("ticketId" to ticketId))
        return Attachment.fromMap(r["attachment"] as Map<*, *>)
    }
    suspend fun requestAttachmentDownload(teamId: String, recordType: String, recordId: String,
        attachmentId: String): DownloadRef {
        identity()
        val r = call("requestAttachmentDownload", mapOf("teamId" to teamId, "recordType" to recordType,
            "recordId" to recordId, "attachmentId" to attachmentId))
        return DownloadRef(r["downloadUrl"] as String, (r["expiresAt"] as? Number)?.toLong() ?: 0L)
    }
    suspend fun uploadBytes(uploadUrl: String, headers: Map<String, String>, bytes: ByteArray) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection
            try {
                require(java.net.URL(uploadUrl).protocol == "https")
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "PUT"
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                if (code !in 200..299 && code != 412) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw Exception("Falha no envio do arquivo (HTTP $code)." +
                        if (body.isNotBlank()) " $body" else "")
                }
            } finally { connection.disconnect() }
        }
    }
    fun error(e: Exception): String {
        val functionsError = e as? FirebaseFunctionsException
        if (functionsError?.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
            (functionsError.details?.toString()?.contains("DEVICE_REPLACED") == true ||
             functionsError.message?.contains("dispositivo ativo", ignoreCase = true) == true))
            return "Este aparelho não é mais o dispositivo ativo da sua conta. Entre novamente ou atualize o OPERIS."
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Muitas tentativas. Aguarde um pouco e tente novamente."
            FirebaseFunctionsException.Code.NOT_FOUND -> "Serviço ou convite indisponível. Confira o código ou tente mais tarde."
            FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Acesso não autorizado. Confira sua participação e a validade do convite."
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Não foi possível validar este aparelho. Tente novamente."
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                functionsError.message?.takeIf { it.isNotBlank() } ?: "Confira os dados informados."
            FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "Confira novamente a equipe e os dados antes de confirmar."
            else -> when (e) {
                is AccountProtectionException -> e.message ?: "Não foi possível proteger a conta."
                else -> "Não foi possível concluir. Confira a conexão e tente novamente."
            }
        }
    }
    fun queueSync(c: Context) {
        val request = OneTimeWorkRequestBuilder<TokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniqueWork("token-sync",ExistingWorkPolicy.REPLACE,request)
    }
}

data class ProtectedAccount(val uid: String, val email: String)
class AccountProtectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
data class ReadState(
    val teamId: String,
    val unreadIncidents: Int,
    val unreadRounds: Int,
    val lastSeenIncidentAt: Long?,
    val lastSeenRoundAt: Long?,
    val serverNow: Long,
) { val totalUnread: Int get() = unreadIncidents + unreadRounds }
class TokenWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        if (!Local.registered) return Result.success()
        return try { Backend.sync(); Result.success() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }
}

