package com.zachzundel.encrier

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.zachzundel.encrier.data.InkDb
import com.zachzundel.encrier.ink.Recognition
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
            .addMigrations(InkDb.MIGRATION_1_2)
            .build()
        recognition = Recognition()
        session = TapeSession(context)
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
