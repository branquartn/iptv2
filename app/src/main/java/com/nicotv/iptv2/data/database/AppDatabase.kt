package com.nicotv.iptv2.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
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

        // v2 -> v3 : ajout de movies.tmdbId (résolu à l'enrichissement TMDb, cf.
        // PlaylistRepository.enrichMovies) — migration réelle plutôt que
        // fallbackToDestructiveMigration ici, pour ne pas reperdre les profils
        // sauvegardés (playlist_profiles vit dans la même base) à chaque montée
        // de version du schéma catalogue.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN tmdbId INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iptv2_database"
                ).addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
