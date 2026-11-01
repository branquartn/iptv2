package com.nicotv.iptv2.account

import com.nicotv.iptv2.data.PlaylistSourcePrefs
import com.nicotv.iptv2.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.first

/**
 * Aligne les profils locaux sur les codes Xtream attribués par l'admin :
 * - crée / met à jour un profil par code (nom = celui donné par l'admin) ;
 * - supprime les profils dont le code a disparu côté serveur ;
 * - supprime les profils saisis à la main avant le login (l'app est privée, plus de saisie locale).
 */
class RemoteSources(
    private val api: AccountApi,
    private val session: SessionManager,
    private val repo: PlaylistRepository,
    private val sourcePrefs: PlaylistSourcePrefs
) {
    enum class Status { OK, UNAUTHORIZED, OFFLINE }

    /** Appelé quand le profil ACTIF change côté admin (identifiants ou contenu autorisé) : l'app recharge en fond. */
    var onActiveProfileChanged: ((profileId: Long, profileName: String) -> Unit)? = null

    /** [force] false : ne fait rien si une synchro a eu lieu il y a moins de [minAgeMs]. */
    suspend fun sync(force: Boolean = true, minAgeMs: Long = 5 * 60_000L): Status {
        if (!session.isLoggedIn()) return Status.UNAUTHORIZED
        if (!force && System.currentTimeMillis() - session.getLastSyncAt() < minAgeMs) return Status.OK

        when (api.refreshToken()) {
            is AccountApi.Fetch.Unauthorized -> { session.clear(); return Status.UNAUTHORIZED }
            else -> Unit   // échec réseau : on tente quand même la liste avec le jeton actuel
        }
        val remote = when (val r = api.xtreamAccounts()) {
            is AccountApi.Fetch.Ok -> r.value
            is AccountApi.Fetch.Unauthorized -> { session.clear(); return Status.UNAUTHORIZED }
            is AccountApi.Fetch.Failure -> return Status.OFFLINE   // hors-ligne : on garde ce qu'on a
        }

        val map = session.getRemoteMap()
        val local = repo.getProfiles().first()
        val remoteIds = remote.map { it.id }.toSet()

        // 1) profils sans lien avec un code du serveur (anciens profils locaux) ou dont le code a disparu
        val keepLocalIds = map.filterKeys { it in remoteIds }.values.toSet()
        local.filter { it.id !in keepLocalIds }.forEach { repo.deleteProfile(it.id) }
        map.keys.retainAll(remoteIds)

        // 2) création / mise à jour
        val localById = repo.getProfiles().first().associateBy { it.id }
        val flags = session.getContentFlagsMap()
        val activeId = sourcePrefs.getActiveProfileId()
        var activeChanged: Pair<Long, String>? = null
        remote.forEach { acc ->
            val existingId = map[acc.id]
            val cur = existingId?.let { localById[it] }
            var localId = cur?.id ?: 0L
            var credsChanged = false
            if (cur == null) {
                localId = repo.saveXtreamProfile(acc.label, acc.host, acc.username, acc.password)
                map[acc.id] = localId
            } else if (cur.name != acc.label || cur.xtreamHost != acc.host ||
                cur.xtreamUsername != acc.username || cur.xtreamPassword != acc.password) {
                repo.saveXtreamProfile(acc.label, acc.host, acc.username, acc.password, existingId = cur.id)
                credsChanged = cur.xtreamHost != acc.host || cur.xtreamUsername != acc.username || cur.xtreamPassword != acc.password
            }
            val newFlags = acc.films to acc.series
            val flagsChanged = flags[localId]?.let { it != newFlags } ?: false
            flags[localId] = newFlags
            if (localId == activeId && (credsChanged || flagsChanged)) activeChanged = localId to acc.label
        }
        flags.keys.retainAll(map.values.toSet())
        session.saveContentFlagsMap(flags)
        session.saveRemoteMap(map)
        session.setLastSyncAt(System.currentTimeMillis())
        activeChanged?.let { onActiveProfileChanged?.invoke(it.first, it.second) }
        return Status.OK
    }

    /** Efface la session et tous les profils (déconnexion). */
    suspend fun logout() {
        repo.getProfiles().first().forEach { repo.deleteProfile(it.id) }
        sourcePrefs.setActiveProfileId(null)
        session.clear()
    }
}
