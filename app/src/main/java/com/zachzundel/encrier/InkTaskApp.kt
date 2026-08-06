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

    fun init(context: Context) {
        db = Room.databaseBuilder(context, InkDb::class.java, "inktask.db").build()
        recognition = Recognition()
    }
}

class InkTaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
