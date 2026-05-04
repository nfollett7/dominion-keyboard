package com.follett.keyboard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.follett.keyboard.data.model.KeystrokeLog
import com.follett.keyboard.data.model.SessionLog

/**
 * KeyboardDatabase — Room database for all keyboard input logging.
 *
 * Stores:
 *  - SessionLog: Each keyboard session (open/close events)
 *  - KeystrokeLog: Every individual key press, word, dictation, translation
 *
 * Uses a singleton pattern to prevent multiple database instances.
 */
@Database(
    entities = [
        SessionLog::class,
        KeystrokeLog::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KeyboardDatabase : RoomDatabase() {

    abstract fun sessionLogDao(): SessionLogDao
    abstract fun keystrokeLogDao(): KeystrokeLogDao

    companion object {
        private const val DATABASE_NAME = "dominion_keyboard.db"

        @Volatile
        private var INSTANCE: KeyboardDatabase? = null

        fun getInstance(context: Context): KeyboardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KeyboardDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
