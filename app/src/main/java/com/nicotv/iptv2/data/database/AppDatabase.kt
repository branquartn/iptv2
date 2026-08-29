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
    version = 6,
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
                    .build().also { INSTANCE = it }
            }
    }
}
