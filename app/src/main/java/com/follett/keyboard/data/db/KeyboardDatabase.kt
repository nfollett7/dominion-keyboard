package com.follett.keyboard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.follett.keyboard.data.model.InputCapture
import com.follett.keyboard.data.model.KeystrokeLog
import com.follett.keyboard.data.model.SessionLog

/**
 * KeyboardDatabase — Room database for all keyboard data.
 *
 * Stores:
 *  - SessionLog: Each keyboard session (open/close events)
 *  - KeystrokeLog: Individual key presses (legacy, being phased out)
 *  - InputCapture: Full message captures for the digital twin
 *
 * Uses a singleton pattern to prevent multiple database instances.
 */
@Database(
    entities = [
        SessionLog::class,
        KeystrokeLog::class,
        InputCapture::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KeyboardDatabase : RoomDatabase() {

    abstract fun sessionLogDao(): SessionLogDao
    abstract fun keystrokeLogDao(): KeystrokeLogDao
    abstract fun inputCaptureDao(): InputCaptureDao

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
