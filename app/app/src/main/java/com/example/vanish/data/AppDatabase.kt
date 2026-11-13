
package com.example.vanish.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.RoomDatabase.JournalMode
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, SeenId::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "vanish.db"
            )
                .setJournalMode(JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("PRAGMA secure_delete = ON").use {
                            it.moveToFirst()
                        }

                        db.query("PRAGMA secure_delete").use { cursor ->
                            if (cursor.moveToFirst()) {
                                android.util.Log.i(
                                    "VanishDB",
                                    "secure_delete=${cursor.getInt(0)}"
                                )
                            }
                        }
                    }
                })
                .build()
    }
}
