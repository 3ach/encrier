package com.zachzundel.encrier

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.zachzundel.encrier.data.InkDb
import com.zachzundel.encrier.ink.Recognition

object Graph {
    lateinit var db: InkDb
        private set
    lateinit var recognition: Recognition
        private set
    lateinit var session: TapeSession
        private set

    fun init(context: Context) {
        db = Room.databaseBuilder(context, InkDb::class.java, "encrier.db")
            .addMigrations(InkDb.MIGRATION_1_2, InkDb.MIGRATION_2_3)
            .build()
        recognition = Recognition(context)
        session = TapeSession(context)
    }

}

class EncrierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
