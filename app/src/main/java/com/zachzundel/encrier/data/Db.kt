package com.zachzundel.encrier.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tapes")
data class TapeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
) {
    companion object {
        const val DEFAULT_ID = 1L
    }
}

@Entity(tableName = "lines")
data class LineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Double,       // fractional ordering; insert = midpoint
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val tapeId: Long = TapeEntity.DEFAULT_ID,
)

/** A human-corrected recognition: ink + what the model offered + the truth. */
@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineId: Long,
    val strokesJson: String,    // full ink snapshot at correction time
    val candidatesJson: String, // what recognition offered
    val correctedText: String,  // what the user said it should be
    val createdAt: Long,
)

@Entity(tableName = "strokes", indices = [Index("lineId")])
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineId: Long,
    val ord: Int,          // stroke order within line
    val pointsJson: String, // [[x,y,t],...] in LINE-RELATIVE coordinates
    val addedAt: Long,
)

@Entity(tableName = "items", indices = [Index(value = ["lineId"], unique = true)])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineId: Long,
    val text: String,
    val candidatesJson: String, // full ranked list from last recognition
    val parentId: Long? = null,
    val status: String,         // OPEN | DONE | DROPPED
    val createdAt: Long,        // the item's listed date; nudgeable via the panel
    val completedAt: Long? = null,
    val droppedAt: Long? = null,
) {
    companion object {
        const val OPEN = "OPEN"
        const val DONE = "DONE"
        const val DROPPED = "DROPPED"
    }
}

data class LineRow(
    val id: Long,
    val seq: Double,
    val createdAt: Long,
    val firstInkAt: Long?, // MIN(strokes.addedAt) — drives day markers
)

@Dao
interface TapeDao {
    @Insert suspend fun insertLine(l: LineEntity): Long
    @Query("DELETE FROM lines WHERE id = :id") suspend fun deleteLine(id: Long)
    @Query(
        "DELETE FROM lines WHERE id NOT IN (SELECT DISTINCT lineId FROM strokes) " +
            "AND id NOT IN (SELECT lineId FROM items)"
    )
    suspend fun gcEmptyLines()
    @Query("DELETE FROM strokes WHERE lineId NOT IN (SELECT id FROM lines)")
    suspend fun gcOrphanStrokes()
    @Query("DELETE FROM items WHERE lineId NOT IN (SELECT id FROM lines)")
    suspend fun gcOrphanItems()
    @Query("SELECT EXISTS(SELECT 1 FROM lines WHERE id = :id)")
    suspend fun lineExists(id: Long): Boolean
    @Query("UPDATE lines SET seq = :seq WHERE id = :id")
    suspend fun updateLineSeq(id: Long, seq: Double)
    @Query("SELECT MAX(seq) FROM lines WHERE tapeId = :tapeId")
    suspend fun maxSeq(tapeId: Long): Double?

    @Query(
        "SELECT l.id, l.seq, l.createdAt, " +
            "(SELECT MIN(s.addedAt) FROM strokes s WHERE s.lineId = l.id) AS firstInkAt " +
            "FROM lines l WHERE l.tapeId = :tapeId ORDER BY l.seq"
    )
    fun observeLines(tapeId: Long): Flow<List<LineRow>>

    @Query("SELECT * FROM tapes ORDER BY createdAt")
    fun observeTapes(): Flow<List<TapeEntity>>
    @Insert suspend fun insertTape(t: TapeEntity): Long

    @Insert suspend fun insertCorrection(c: CorrectionEntity): Long

    @Query(
        "SELECT i.* FROM items i JOIN lines l ON l.id = i.lineId " +
            "WHERE l.tapeId = :tapeId"
    )
    fun observeItemsForTape(tapeId: Long): Flow<List<ItemEntity>>

    @Insert suspend fun insertStroke(s: StrokeEntity): Long
    @Query("DELETE FROM strokes WHERE id = :id") suspend fun deleteStroke(id: Long)
    @Query("SELECT * FROM strokes WHERE lineId IN (:lineIds) ORDER BY lineId, ord")
    suspend fun strokesFor(lineIds: List<Long>): List<StrokeEntity>
    @Query("SELECT COUNT(*) FROM strokes WHERE lineId = :lineId")
    suspend fun strokeCount(lineId: Long): Int
    @Query("SELECT COALESCE(MAX(ord), -1) FROM strokes WHERE lineId = :lineId")
    suspend fun maxOrd(lineId: Long): Int
    @Query("SELECT MIN(addedAt) FROM strokes WHERE lineId = :lineId")
    suspend fun firstInkAt(lineId: Long): Long?

    @Query("DELETE FROM strokes WHERE lineId = :lineId")
    suspend fun deleteStrokesForLine(lineId: Long)

    @Insert suspend fun insertItem(i: ItemEntity): Long
    @Update suspend fun updateItem(i: ItemEntity)
    @Query("DELETE FROM items WHERE id = :id") suspend fun deleteItem(id: Long)
    @Query("SELECT * FROM items WHERE lineId = :lineId")
    suspend fun itemForLine(lineId: Long): ItemEntity?
    @Query("SELECT * FROM items") fun observeItems(): Flow<List<ItemEntity>>
}

@Database(
    entities = [
        TapeEntity::class,
        LineEntity::class,
        StrokeEntity::class,
        ItemEntity::class,
        CorrectionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class InkDb : RoomDatabase() {
    abstract fun dao(): TapeDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tapes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("INSERT INTO tapes (id, name, createdAt) VALUES (1, 'default', 0)")
                db.execSQL("ALTER TABLE lines ADD COLUMN tapeId INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS corrections (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "lineId INTEGER NOT NULL, strokesJson TEXT NOT NULL, " +
                        "candidatesJson TEXT NOT NULL, correctedText TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }
    }
}
