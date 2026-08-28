package com.bandmr.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY addedAt DESC")
    suspend fun getAllOnce(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: Long): Flow<Song?>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: Long): Song?

    @Insert
    suspend fun insert(song: Song): Long

    @Update
    suspend fun update(song: Song)

    @Delete
    suspend fun delete(song: Song)
}

@Database(entities = [Song::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN speed REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN loopStartMs INTEGER")
                db.execSQL("ALTER TABLE songs ADD COLUMN loopEndMs INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE songs ADD COLUMN stemGainsPacked INTEGER NOT NULL DEFAULT ${Stem.DEFAULT_PACKED}",
                )
                db.query("SELECT id, muteMask FROM songs").use { c ->
                    val idIdx = c.getColumnIndex("id")
                    val maskIdx = c.getColumnIndex("muteMask")
                    while (c.moveToNext()) {
                        val id = c.getLong(idIdx)
                        val packed = Stem.packedFromMuteMask(c.getInt(maskIdx))
                        db.execSQL("UPDATE songs SET stemGainsPacked = $packed WHERE id = $id")
                    }
                }
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bandmr.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
