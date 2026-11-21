package com.example.auraplantapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// version 2: added waterLevel and lightLevel to daily_record
// version 3: added quoteText to daily_record
// version 4: quoteText renamed to recordedQuoteText
@Database(entities = [DailyRecord::class], version = 4, exportSchema = false)
abstract class PlantDatabase : RoomDatabase() {

    abstract fun dailyRecordDao(): DailyRecordDao

    companion object {
        @Volatile
        private var INSTANCE: PlantDatabase? = null

        fun get(context: Context): PlantDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PlantDatabase::class.java,
                    "plant.db"
                )
                    // Development only: wipes data on schema change. Replace with
                    // a proper Migration (and remove this line) before the final demo.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}