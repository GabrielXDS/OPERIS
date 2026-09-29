package br.com.alertaequipe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

// Registros (Ocorrências e Rondas): notificação agrupada, contadores por equipe ativa,
// badge do launcher (setNumber) e deduplicação por recordId. O estado de leitura canônico
// vive no servidor (users/{uid}/recordReadState) e é adotado no boot, troca de equipe e
// reinstalação; SharedPreferences é apenas cache. O FCM apenas antecipa a atualização.
object Records {
    const val CHANNEL = "records_updates"
    const val ID = 200
    const val MODULE_OCCURRENCE = "occurrence"
    const val MODULE_ROUND = "round"
    private const val MAX_SEEN = 80
    private const val SEEN_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** Bumped whenever an FCM record_created is handled; MaínActivity recolhe o contador. */
    val updated = MutableStateFlow(0L)
    val pendingId: String? get() = Local.prefs.getString("recordsDetailId", null)
    val pendingType: String? get() = Local.prefs.getString("recordsDetailType", null)
    val pendingTitle: String? get() = Local.prefs.getString("recordsDetailTitle", null)

    data class RecordData(
        val recordType: String,
        val recordId: String,
        val teamId: String,
        val title: String,
        val createdAt: Long,
        val createdBy: String,
    )

    fun ensureChannel(c: Context) {
        val ch = NotificationChannel(CHANNEL, "Ocorrências e Rondas", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Novos registros criados na sua equipe"
            setSound(null, null)
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun activeTeam(c: Context): String = Local.prefs.getString("recordsActiveTeam", null)?.takeIf { it.isNotBlank() } ?: Local.teamId

    fun setActiveTeam(c: Context, teamId: String) {
        Local.prefs.edit().putString("recordsActiveTeam", teamId).apply()
    }

    private fun count(teamId: String, key: String): Int = Local.prefs.getInt("records$key:$teamId", 0).coerceAtLeast(0)

    fun occurrences(teamId: String): Int = count(teamId, "Occ")
    fun rounds(teamId: String): Int = count(teamId, "Round")
    fun total(teamId: String): Int = occurrences(teamId) + rounds(teamId)

    fun lastSeenAt(teamId: String, module: String): Long = Local.prefs.getLong("recordsLast:$module:$teamId", 0L)

    /**
     * Adota o estado canônico do servidor no cache local (boot, troca de equipe e
     * reinstalação). Nunca retrocede: lastSeen local apenas avança até o do servidor.
     * Os contadores exibidos passam a refletir o servidor. Retorna os módulos em que o
     * lastSeen local ficou à frente do servidor (drift offline) para reconciliação via
     * markRecordsSeen.
     */
    fun adoptCanonical(c: Context, teamId: String, state: ReadState): List<String> {
        val prefs = Local.prefs
        prefs.edit().putInt("recordsOcc:$teamId", state.unreadIncidents.coerceAtLeast(0))
            .putInt("recordsRound:$teamId", state.unreadRounds.coerceAtLeast(0)).apply()
        val push = mutableListOf<String>()
        val edit = prefs.edit()
        for (module in listOf(MODULE_OCCURRENCE, MODULE_ROUND)) {
            val serverAt = if (module == MODULE_OCCURRENCE) state.lastSeenIncidentAt else state.lastSeenRoundAt
            val local = prefs.getLong("recordsLast:$module:$teamId", 0L)
            val (effective, needsBackfill) = seenPolicy(local, serverAt)
            edit.putLong("recordsLast:$module:$teamId", effective)
            if (needsBackfill) push.add(module)
        }
        edit.apply()
        refresh(c, teamId)
        return push
    }

    /**
     * Marca o módulo como visto (cache local + estado canônico no servidor). seenThrough é o
     * createdAt do registro mais recente efetivamente carregado; 0 (lista vazia) mantém o
     * estado anterior no servidor (nada é marcado indevidamente). Nunca retrocede: se um
     * valor maior já foi gravado, o novo apenas avança.
     */
    suspend fun markSeen(c: Context, teamId: String, module: String, seenThrough: Long) {
        val previous = lastSeenAt(teamId, module)
        val target = nextSeen(previous, seenThrough)
        Local.prefs.edit().apply {
            if (module == MODULE_OCCURRENCE) putInt("recordsOcc:$teamId", 0).putLong("recordsLast:$module:$teamId", target)
            else putInt("recordsRound:$teamId", 0).putLong("recordsLast:$module:$teamId", target)
        }.apply()
        refresh(c, teamId)
        try {
            Backend.markRecordsSeen(teamId, module, target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sem rede: o cache local preserva o avanço e a próxima readState reconcilia.
        }
    }

    /** Decodes and dedupes an FCM record_created; counts it for its own team. */
    @Synchronized
    fun handle(c: Context, data: Map<String, String>): RecordData? {
        val record = decode(data, Local.uid) ?: return null
        if (!dedupe(record.recordId, record.createdAt)) return null
        val prefix = if (record.recordType == MODULE_OCCURRENCE) "recordsOcc" else "recordsRound"
        val current = if (record.recordType == MODULE_OCCURRENCE) occurrences(record.teamId) else rounds(record.teamId)
        Local.prefs.edit()
            .putInt("$prefix:${record.teamId}", current + 1)
            .putString("recordsDetailType", record.recordType)
            .putString("recordsDetailTitle", record.title)
            .putString("recordsDetailId", record.recordId)
            .apply()
        if (record.teamId == activeTeam(c)) refresh(c, record.teamId)
        updated.value = System.currentTimeMillis()
        return record
    }

    /** Ficha existente compartilhada com o setor do usuário: notifica e abre a ficha diretamente. */
    fun handleShared(c: Context, data: Map<String, String>) {
        val recordId = data["recordId"]?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) } ?: return
        val teamId = data["teamId"]?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) } ?: return
        val module = if (data["recordType"] == MODULE_ROUND) MODULE_ROUND else MODULE_OCCURRENCE
        val title = data["title"]?.trim()?.take(80).orEmpty().ifBlank { "Registro operacional" }
        val sharedBy = data["sharedBy"]?.trim()?.take(40).orEmpty().ifBlank { "Outro setor" }
        ensureChannel(c)
        Local.prefs.edit()
            .putString("recordsDetailType", module)
            .putString("recordsDetailTitle", title)
            .putString("recordsDetailId", recordId)
            .apply()
        val updatedRecord = data["type"] == "record_updated"
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(if (updatedRecord) "FICHA ATUALIZADA" else "FICHA COMPARTILHADA")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (updatedRecord) "$sharedBy atualizou uma ficha compartilhada: $title" else "$sharedBy compartilhou com seu setor: $title"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open(c, if (module == MODULE_ROUND) "rounds" else "occurrences", recordId))
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { NotificationManagerCompat.from(c).notify(ID + 1, notification) }
        }
        updated.value = System.currentTimeMillis()
    }

    fun decode(data: Map<String, String>, currentDeviceId: String, now: Long = System.currentTimeMillis()): RecordData? {
        if (!Operational.validRecordPayload(data, currentDeviceId, now)) return null
        return RecordData(
            recordType = if (data["recordType"] == MODULE_ROUND) MODULE_ROUND else MODULE_OCCURRENCE,
            recordId = data["recordId"]!!,
            teamId = data["teamId"]!!,
            title = data["title"]!!.trim(),
            createdAt = data["createdAt"]!!.toLong(),
            createdBy = data["createdBy"]!!,
        )
    }

    private fun dedupe(recordId: String, createdAt: Long): Boolean {
        val now = System.currentTimeMillis()
        val prefs = Local.prefs
        val seen = runCatching { JSONObject(prefs.getString("recordsSeen", "{}")!!) }.getOrDefault(JSONObject())
        val stale = seen.keys().asSequence().filter { now - seen.optLong(it) > SEEN_WINDOW_MS }.toList()
        stale.forEach { seen.remove(it) }
        if (seen.has(recordId)) return false
        if (seen.length() >= MAX_SEEN) {
            seen.keys().asSequence().minByOrNull { seen.optLong(it) }?.let { seen.remove(it) }
        }
        seen.put(recordId, createdAt)
        prefs.edit().putString("recordsSeen", seen.toString()).apply()
        return true
    }

    // --- Notificação única e agregada (id fixo), com badge de contador ---
    private fun refresh(c: Context, teamId: String) {
        ensureChannel(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val occ = occurrences(teamId)
        val round = rounds(teamId)
        val total = occ + round
        if (total <= 0) {
            nm.cancel(ID)
            return
        }
        val detailType = Local.prefs.getString("recordsDetailType", null)
        val detailTitle = Local.prefs.getString("recordsDetailTitle", null)
        val detailId = Local.prefs.getString("recordsDetailId", null)
        val builder = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_alert)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setNumber(total)
            .setContentIntent(open(c, if (total == 1) if (detailType == MODULE_ROUND) "rounds" else "occurrences" else if (occ >= round) "occurrences" else "rounds", detailId))
        if (total == 1 && detailType != null && detailTitle != null) {
            val isRound = detailType == MODULE_ROUND
            builder.setContentTitle(if (isRound) "NOVA RONDA" else "NOVA OCORRÊNCIA")
                .setContentText(detailTitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detailTitle))
        } else {
            builder.setContentTitle(if (total == 1) "1 registro novo" else "$total registros novos")
                .setContentText(unreadText(occ, round))
                .setStyle(NotificationCompat.BigTextStyle().bigText(unreadText(occ, round)))
        }
        try {
            nm.notify(ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun open(c: Context, module: String, recordId: String?): PendingIntent {
        val intent = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("operis_module", module)
            if (recordId != null) putExtra("operis_record_id", recordId)
        }
        return PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Formatação pura e testável do badge exibido no menu / tela inicial. */
object Badges {
    fun label(count: Int): String? = when {
        count <= 0 -> null
        count > 99 -> "99+"
        else -> count.toString()
    }
}

/** Ponte para o deep link da notificação agregada: extrai extras e os mantém até a UI consumir. */
object RecordRoute {
    private val p get() = Local.prefs
    val deepLink = MutableStateFlow(0L)
    fun stage(c: Context, intent: Intent) {
        val module = intent.getStringExtra("operis_module")
        val recordId = intent.getStringExtra("operis_record_id")
        intent.removeExtra("operis_module")
        intent.removeExtra("operis_record_id")
        if (module != null) {
            p.edit().putString("deepModule", module).putString("deepRecord", recordId).apply()
            deepLink.value = System.currentTimeMillis()
        }
    }
    fun take(): Pair<String?, String?> {
        val module = p.getString("deepModule", null)
        val recordId = p.getString("deepRecord", null)
        p.edit().remove("deepModule").remove("deepRecord").apply()
        return module to recordId
    }
}

/** Texto resumido dos não lidos: "X ocorrências • Y rondas". */
internal fun unreadText(occurrences: Int, rounds: Int): String = buildList {
    if (occurrences > 0) add(if (occurrences == 1) "1 ocorrência" else "$occurrences ocorrências")
    if (rounds > 0) add(if (rounds == 1) "1 ronda" else "$rounds rondas")
}.joinToString(" • ")

/**
 * Política de reconciliação do estado de leitura (puro/testável). O servidor é a fonte de
 * verdade; o cache local nunca retrocede. Retorna (lastSeen efetivo, precisaEmpurrarServidor).
 * - Servidor mais à frente -> adota o servidor.
 * - Local mais à frente (drift offline) -> mantém o local e sinaliza reconciliação.
 * - Servidor desconhecido (null, sem estado ainda) -> mantém o local puro.
 */
internal fun seenPolicy(previous: Long, server: Long?): Pair<Long, Boolean> {
    val effective = maxOf(previous, server ?: 0L)
    return effective to (server != null && previous > server)
}

/** Próximo lastSeen após marcar como visto (nunca retrocede; 0 não avança nada). */
internal fun nextSeen(previous: Long, seenThrough: Long): Long =
    maxOf(previous, seenThrough.coerceAtLeast(0))
