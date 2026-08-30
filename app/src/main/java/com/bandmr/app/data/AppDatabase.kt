package com.bandmr.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

    @Delete
    suspend fun delete(song: Song)

    // 컬럼별 UPDATE — get→update(copy) 방식 대비. 여러 설정을 겹쳐 저장할 때
    // 먼저 쓴 필드가 날아가는 lost-update를 원천 차단한다
    @Query("UPDATE songs SET stemGainsPacked = :packed, muteMask = :mask WHERE id = :id")
    suspend fun updateStemLevels(id: Long, packed: Long, mask: Int)

    @Query("UPDATE songs SET semitones = :semitones WHERE id = :id")
    suspend fun updateSemitones(id: Long, semitones: Int)

    @Query("UPDATE songs SET speed = :speed WHERE id = :id")
    suspend fun updateSpeed(id: Long, speed: Float)

    @Query("UPDATE songs SET loopStartMs = :startMs, loopEndMs = :endMs WHERE id = :id")
    suspend fun updateLoop(id: Long, startMs: Long?, endMs: Long?)

    @Query("UPDATE songs SET separatedTier = :tier, stemsDir = :dir WHERE id = :id")
    suspend fun updateSeparation(id: Long, tier: String, dir: String)

    /**
     * 모든 곡의 분리 표시를 내린다(스템 전체 삭제 시). 한 문장으로 끝내므로 곡 수만큼
     * UPDATE를 돌리지 않는다. [updateSeparation]은 non-null만 받아 해제에 쓸 수 없다.
     */
    @Query("UPDATE songs SET separatedTier = NULL, stemsDir = NULL WHERE separatedTier IS NOT NULL")
    suspend fun clearAllSeparation()
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
