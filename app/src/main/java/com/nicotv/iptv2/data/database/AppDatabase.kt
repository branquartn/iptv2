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
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iptv2_database"
                ).fallbackToDestructiveMigration()
                    // ⚠️ `synchronous = NORMAL` (30/08/2026) : avec le mode WAL
                    // — celui que Room active par défaut — SQLite n'a plus
                    // besoin de forcer une synchronisation disque à chaque
                    // commit, ce qui est le gros du coût d'écriture d'un
                    // catalogue de ~215 000 lignes. Compromis assumé et adapté
                    // ICI : la seule perte possible est celle des toutes
                    // dernières écritures en cas de coupure de courant ou de
                    // crash SYSTÈME (un crash de l'app, lui, ne perd rien) — or
                    // cette base n'est qu'un CACHE de la playlist, entièrement
                    // reconstructible en la rechargeant. À ne pas reprendre tel
                    // quel dans une base contenant des données non
                    // reproductibles.
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA synchronous = NORMAL")
                        }
                    })
                    .build().also { INSTANCE = it }
            }
    }
}
