package com.zachzundel.encrier

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.zachzundel.encrier.data.InkDb
import com.zachzundel.encrier.ink.Recognition
import com.zachzundel.encrier.ui.TapeSession
import java.io.File

object Graph {
    lateinit var db: InkDb
        private set
    lateinit var recognition: Recognition
        private set
    lateinit var session: TapeSession
        private set

    fun init(context: Context) {
        migrateDbName(context)
        db = Room.databaseBuilder(context, InkDb::class.java, "encrier.db")
            .addMigrations(MIGRATION_1_2)
            .build()
        recognition = Recognition()
        session = TapeSession(context)
    }

    private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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

    // One-time rename from the spec-era working title "InkTask"; keeps existing data.
    private fun migrateDbName(context: Context) {
        val old = context.getDatabasePath("inktask.db")
        val new = context.getDatabasePath("encrier.db")
        if (old.exists() && !new.exists()) {
            old.renameTo(new)
            File(old.path + "-wal").renameTo(File(new.path + "-wal"))
            File(old.path + "-shm").renameTo(File(new.path + "-shm"))
        }
    }
}

class EncrierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
