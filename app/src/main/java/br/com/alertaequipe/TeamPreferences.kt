package br.com.alertaequipe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Separate file/key: never overwrites Local.teamId, identity, token, or deduplication data. */
class TeamPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("team_selection", Context.MODE_PRIVATE)
    fun memberships(): List<TeamMembership> {
        if (!preferences.contains("memberships")) return emptyList()
        return runCatching {
            val json = JSONArray(preferences.getString("memberships", "[]"))
            (0 until json.length()).map { index ->
                val team = json.getJSONObject(index)
                TeamMembership(
                    team.getString("id"),
                    team.getString("name"),
                    team.optString("role", "member"),
                    team.optString("operationalFunction", "")
                )
            }
        }.getOrDefault(emptyList())
    }
    fun saveMemberships(teams: List<TeamMembership>) {
        val json = JSONArray()
        teams.forEach {
            json.put(
                JSONObject()
                    .put("id", it.teamId)
                    .put("name", it.displayName)
                    .put("role", it.role)
                    .put("operationalFunction", it.operationalFunction)
            )
        }
        preferences.edit().putString("memberships", json.toString()).apply()
        Local.saveMemberships(teams)
    }
    fun releaseSeen(): Int? = if (preferences.contains("lastSeenReleaseCode")) preferences.getInt("lastSeenReleaseCode", 0).takeIf { it > 0 } else null
    fun markReleaseSeen() { preferences.edit().putInt("lastSeenReleaseCode", BuildConfig.VERSION_CODE).apply() }

    /** The release policy cache stores every policy field plus a validity timestamp. */
    fun saveReleasePolicy(p: ReleasePolicy) {
        preferences.edit()
            .putInt("latestReleaseCode", p.latestVersionCode)
            .putString("latestReleaseName", p.latestVersionName)
            .putInt("minSupportedReleaseCode", p.minSupportedVersionCode)
            .putString("releaseStatus", p.status.name)
            .putString("releaseDownloadUrl", p.downloadUrl ?: "")
            .putString("releaseNotes", p.releaseNotes.joinToString("\n"))
            .putString("releaseSha256", p.sha256 ?: "")
            .putLong("releaseApkSize", p.apkSize ?: -1L)
            .putLong("releaseCachedAtMs", p.cachedAtMs)
            .apply()
    }
    fun releasePolicy(): ReleasePolicy? {
        if (!preferences.contains("latestReleaseCode")) return null
        val status = preferences.getString("releaseStatus", null)
            ?.let { s -> ReleasePolicyStatus.entries.firstOrNull { it.name == s } }
        return ReleasePolicy(
            latestVersionCode = preferences.getInt("latestReleaseCode", 0),
            latestVersionName = preferences.getString("latestReleaseName", "") ?: "",
            minSupportedVersionCode = preferences.getInt("minSupportedReleaseCode", 0),
            status = status ?: ReleasePolicyStatus.PUBLISHED,
            downloadUrl = preferences.getString("releaseDownloadUrl", "").takeIf { !it.isNullOrBlank() },
            releaseNotes = (preferences.getString("releaseNotes", "") ?: "").split("\n").filter { it.isNotBlank() },
            sha256 = preferences.getString("releaseSha256", "").takeIf { !it.isNullOrBlank() },
            apkSize = preferences.getLong("releaseApkSize", -1L).takeIf { it >= 1 },
            cachedAtMs = preferences.getLong("releaseCachedAtMs", 0L)
        )
    }

    fun selectedTeam(memberships: List<TeamMembership>): TeamMembership? {
        val saved = preferences.getString("selectedTeamId", null)
        val selected = TeamSelection.resolve(memberships, saved)
        if (selected != null && selected.teamId != saved) {
            preferences.edit().putString("selectedTeamId", selected.teamId).apply()
        }
        return selected
    }

    fun select(teamId: String, memberships: List<TeamMembership>): Boolean {
        if (memberships.none { it.teamId == teamId }) return false
        preferences.edit().putString("selectedTeamId", teamId).apply()
        return true
    }
}
