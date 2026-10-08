package com.flareaward.serendip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CaptureEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SerendipDatabase : RoomDatabase() {

    abstract fun captureEventDao(): CaptureEventDao

    companion object {
        private const val NAME = "serendip.db"

        fun create(context: Context): SerendipDatabase =
            Room.databaseBuilder(context.applicationContext, SerendipDatabase::class.java, NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
