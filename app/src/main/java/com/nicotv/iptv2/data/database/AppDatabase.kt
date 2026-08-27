package com.nicotv.iptv2.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nicotv.iptv2.data.database.dao.DownloadDao
import com.nicotv.iptv2.data.database.dao.EpisodeDao
import com.nicotv.iptv2.data.database.dao.FavoriteDao
import com.nicotv.iptv2.data.database.dao.MovieDao
import com.nicotv.iptv2.data.database.dao.NewDetectEpisodeDao
import com.nicotv.iptv2.data.database.dao.SeenEpisodeDao
import com.nicotv.iptv2.data.database.dao.SeenMovieDao
import com.nicotv.iptv2.data.database.dao.SeenSeriesDao
import com.nicotv.iptv2.data.database.dao.SeriesDao
import com.nicotv.iptv2.data.database.dao.SeriesFavoriteDao
import com.nicotv.iptv2.data.database.dao.WatchHistoryDao
import com.nicotv.iptv2.data.database.entity.DownloadEntity
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.database.entity.MovieEntity
import com.nicotv.iptv2.data.database.entity.NewDetectEpisodeEntity
import com.nicotv.iptv2.data.database.entity.SeenEpisodeEntity
import com.nicotv.iptv2.data.database.entity.SeenMovieEntity
import com.nicotv.iptv2.data.database.entity.SeenSeriesEntity
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import com.nicotv.iptv2.data.database.entity.SeriesFavoriteEntity
import com.nicotv.iptv2.data.database.entity.WatchHistoryEntity

@Database(
    entities = [MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
                FavoriteEntity::class, WatchHistoryEntity::class, SeriesFavoriteEntity::class,
                SeenEpisodeEntity::class, SeenMovieEntity::class, SeenSeriesEntity::class,
                NewDetectEpisodeEntity::class, DownloadEntity::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun seriesFavoriteDao(): SeriesFavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun seenEpisodeDao(): SeenEpisodeDao
    abstract fun seenMovieDao(): SeenMovieDao
    abstract fun seenSeriesDao(): SeenSeriesDao
    abstract fun newDetectEpisodeDao(): NewDetectEpisodeDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // v5 → v6 : ajout de movies.addedAt (badge « NOUVEAU »).
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v6 → v7 : table des favoris séries.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series_favorites " +
                    "(seriesId INTEGER NOT NULL PRIMARY KEY, addedAt INTEGER NOT NULL)"
                )
            }
        }

        // v7 → v8 : table des épisodes vus (marquage permanent indépendant de l'historique de reprise).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS seen_episodes " +
                    "(fileKey TEXT NOT NULL PRIMARY KEY, watchedAt INTEGER NOT NULL)"
                )
            }
        }

        // v8 → v9 : table des films vus.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS seen_movies " +
                    "(historyKey TEXT NOT NULL PRIMARY KEY, watchedAt INTEGER NOT NULL)"
                )
            }
        }

        // v9 → v10 : badge NOUVEAU pour les séries (comme isNewItem() côté PWA,
        // symétrique au fix films de cette session) — séries « ouvertes » et
        // épisodes connus au moment de l'ouverture (seen.episodes, détection
        // NOUVEAU uniquement — DISTINCT de seen_episodes/epseen qui est le
        // marquage « vu jusqu'au bout »).
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS seen_series " +
                    "(name TEXT NOT NULL PRIMARY KEY, seenAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS seen_episodes_new " +
                    "(fileKey TEXT NOT NULL PRIMARY KEY, seenAt INTEGER NOT NULL)"
                )
            }
        }

        // v10 → v11 : table des téléchargements locaux (mode avion).
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS downloads (" +
                    "key TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, seriesId INTEGER NOT NULL, " +
                    "title TEXT NOT NULL, episodeTitle TEXT NOT NULL, seasonNumber INTEGER NOT NULL, " +
                    "episodeNumber INTEGER NOT NULL, posterUrl TEXT NOT NULL, sourceUrl TEXT NOT NULL, " +
                    "localPath TEXT NOT NULL, state TEXT NOT NULL, osDownloadId INTEGER NOT NULL, " +
                    "bytesDownloaded INTEGER NOT NULL, bytesTotal INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iptv_database"
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
