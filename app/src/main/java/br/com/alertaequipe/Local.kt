package br.com.alertaequipe

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID

data class Panic(val id: String, val team: String, val sender: String, val name: String, val time: Long) {
    fun json() = JSONObject().put("id",id).put("team",team).put("sender",sender).put("name",name).put("time",time).toString()
    companion object {
        fun decode(s: String?): Panic? {
            if (s == null) return null
            return runCatching {
                val j = JSONObject(s)
                Panic(j.getString("id"),j.getString("team"),j.getString("sender"),j.getString("name"),j.getLong("time"))
            }.getOrNull()
        }
    }
}
object Local {
    lateinit var prefs: SharedPreferences
    val current = MutableStateFlow<Panic?>(null)
    fun init(c: Context) {
        prefs = c.getSharedPreferences("device",Context.MODE_PRIVATE)
        Panic.decode(prefs.getString("active",null))?.let {
            if (System.currentTimeMillis()-it.time in 0..119_999) {
                current.value = it
                prefs.edit().putBoolean("audioWarning",true).apply()
            }
        }
        // Serviço não é reiniciado automaticamente: um alerta antigo não deve tocar após reboot.
    }
    val deviceId get() = prefs.getString("deviceId","")!!
    val uid get() = prefs.getString("uid","")!!
    val teamId get() = prefs.getString("teamId","")!!
    val name get() = prefs.getString("name","")!!
    val operationalFunction get() = prefs.getString("operationalFunction","")!!
    val registered get() = deviceId.isNotEmpty() && uid.isNotEmpty()
    // Server membership cache. UI selection never grants receive authorization.
    fun belongsToTeam(team: String): Boolean =
        if (prefs.contains("membershipIds")) team in (prefs.getStringSet("membershipIds", emptySet()) ?: emptySet())
        else false
    fun saveMemberships(teams: List<TeamMembership>) {
        prefs.edit().putStringSet("membershipIds", teams.map { it.teamId }.toSet()).apply()
    }
    fun installationId(): String {
        val saved = prefs.getString("installationId", "")!!
        if (saved.isNotBlank()) return saved
        return UUID.randomUUID().toString().also { prefs.edit().putString("installationId", it).commit() }
    }
    fun register(id: String, userUid: String, team: String, name: String, operationalFunction: String? = null) {
        val edit = prefs.edit().putString("deviceId",id).putString("uid",userUid)
            .putString("teamId",team).putString("name",name)
        if (!operationalFunction.isNullOrBlank()) edit.putString("operationalFunction", operationalFunction)
        edit.commit()
    }
    fun clearAccount() { prefs.edit().clear().commit() }
    @Synchronized fun receive(a: Panic): Boolean {
        val now = System.currentTimeMillis()
        val seen = runCatching { JSONObject(prefs.getString("seenJson","{}")!!) }.getOrDefault(JSONObject())
        val stale = seen.keys().asSequence().filter { now-seen.optLong(it) > 120_000 }.toList()
        stale.forEach { seen.remove(it) }
        if (seen.has(a.id)) return false
        seen.put(a.id,now)
        prefs.edit().putString("seenJson",seen.toString()).putString("last",a.json()).commit()
        return true
    }
    val last get() = Panic.decode(prefs.getString("last",null))

    // O aviso de áudio é honesto enquanto reflete o estado atual: só é necessário quando
    // existe um alerta ativo/recente (janela de 120s, igual à flag de deduplicação) ou
    // quando o volume de alarme está zerado. Falhas transitórias passadas curam sozinhas.
    fun reconcileAudioWarning(alarmVolume: Int): Boolean {
        val active = Panic.decode(prefs.getString("active", null))
        val recent = active != null && System.currentTimeMillis() - active.time in 0..119_999
        val expected = audioWarningShouldWarn(recent, alarmVolume)
        val current = prefs.getBoolean("audioWarning", false)
        if (expected != current) {
            prefs.edit().putBoolean("audioWarning", expected).apply()
            return true
        }
        return false
    }
}

// Regra pura (testável) de quando o aviso de áudio deve permanecer ligado.
internal fun audioWarningShouldWarn(recentActivePanic: Boolean, alarmVolume: Int): Boolean =
    recentActivePanic || alarmVolume <= 0
