package com.nicotv.iptv2.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nicotv.iptv2.data.database.dao.ChannelDao
import com.nicotv.iptv2.data.database.dao.EpisodeDao
import com.nicotv.iptv2.data.database.dao.FavoriteDao
import com.nicotv.iptv2.data.database.dao.MovieDao
import com.nicotv.iptv2.data.database.dao.PlaylistProfileDao
import com.nicotv.iptv2.data.database.dao.SeriesDao
import com.nicotv.iptv2.data.database.dao.WatchHistoryDao
import com.nicotv.iptv2.data.database.entity.ChannelEntity
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.database.entity.MovieEntity
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import com.nicotv.iptv2.data.database.entity.WatchHistoryEntity

@Database(
    entities = [ChannelEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
                FavoriteEntity::class, WatchHistoryEntity::class, PlaylistProfileEntity::class],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun playlistProfileDao(): PlaylistProfileDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Pas de migration écrite à la main : fallbackToDestructiveMigration
        // recrée la base à chaque montée de version (perd les profils sauvegardés
        // une fois par bump de schéma, mais c'est le chemin vérifié/fiable de ce
        // projet — une migration ALTER TABLE mal alignée avec le schéma attendu
        // par Room fait planter l'app au démarrage, pire que perdre un profil).
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, "iptv2_database").also { INSTANCE = it }
            }

        // Une base de CATALOGUE par playlist (« iptv2_catalog_<id> ») : basculer d'une playlist à l'autre ne
        // retélécharge plus rien tant que le cache est récent. La base principale (« iptv2_database ») ne garde que
        // la table des profils. Même schéma (mêmes entités) pour toutes, donc aucune migration.
        private val catalogs = java.util.concurrent.ConcurrentHashMap<String, AppDatabase>()

        fun forProfile(context: Context, profileId: Long?): AppDatabase =
            if (profileId == null) getInstance(context)
            else catalogs.getOrPut("iptv2_catalog_$profileId") { build(context, "iptv2_catalog_$profileId") }

        /** Ferme et supprime le cache d'une playlist (profil retiré par l'admin). */
        fun deleteProfileCatalog(context: Context, profileId: Long) {
            val name = "iptv2_catalog_$profileId"
            catalogs.remove(name)?.close()
            context.applicationContext.deleteDatabase(name)
        }

        private fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .fallbackToDestructiveMigration()
                // ⚠️ `synchronous = NORMAL` : avec le mode WAL, SQLite n'a plus besoin de forcer une synchronisation
                // disque à chaque commit (gros du coût d'écriture d'un catalogue de ~215 000 lignes). La seule perte
                // possible est celle des toutes dernières écritures en cas de coupure/crash SYSTÈME : ces bases ne
                // sont qu'un CACHE de la playlist, entièrement reconstructible.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA synchronous = NORMAL")
                    }
                })
                .build()
    }
}
