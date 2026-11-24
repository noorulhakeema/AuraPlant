package com.example.auraplantapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyRecordDao {

    /** Creates the day's row if it doesn't exist; never overwrites an existing row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureRow(record: DailyRecord)

    /** One watering: marks the task done and raises the water level by 1, up to max. */
    @Query(
        """UPDATE daily_record
           SET wateredDone = 1, waterLevel = MIN(waterLevel + 1, :max)
           WHERE epochDay = :day"""
    )
    suspend fun addWater(day: Long, max: Int)

    /** Marks the light task done without touching the level. */
    @Query("UPDATE daily_record SET lightDone = 1 WHERE epochDay = :day")
    suspend fun markLightOk(day: Long)

    /**
     * Saves the latest light reading. The task becomes done when [enough] is true
     * and stays done for the rest of the day, even if the light drops again.
     */
    @Query(
        """UPDATE daily_record
           SET lightLevel = :level,
               lightDone = CASE WHEN :enough THEN 1 ELSE lightDone END
           WHERE epochDay = :day"""
    )
    suspend fun setLightLevel(day: Long, level: Int, enough: Boolean)

    @Query(
        """UPDATE daily_record
           SET positiveMessageDone = 1, sentimentScore = :score
           WHERE epochDay = :day"""
    )
    suspend fun markPositiveMessage(day: Long, score: Float)

    @Query("UPDATE daily_record SET patCount = patCount + 1 WHERE epochDay = :day")
    suspend fun addPat(day: Long)

    @Query("UPDATE daily_record SET dropCount = dropCount + 1 WHERE epochDay = :day")
    suspend fun addDrop(day: Long)

    @Query("UPDATE daily_record SET recordedQuoteText = :text WHERE epochDay = :day")
    suspend fun setRecordedQuote(day: Long, text: String)

    /**
     * Saves a recorded quote's transcription together with the AI's verdict in one write.
     * The score is always saved (latest one wins); the positive-message task becomes done
     * when [positive] is true and stays done for the rest of the day.
     */
    @Query(
        """UPDATE daily_record
           SET recordedQuoteText = :text,
               sentimentScore = :score,
               positiveMessageDone = CASE WHEN :positive THEN 1 ELSE positiveMessageDone END
           WHERE epochDay = :day"""
    )
    suspend fun saveRecordedQuoteResult(day: Long, text: String, positive: Boolean, score: Float)

    /** Live-updating record for one day (null if nothing has happened that day). */
    @Query("SELECT * FROM daily_record WHERE epochDay = :day")
    fun observeDay(day: Long): Flow<DailyRecord?>

    /** Live-updating records for a date range, e.g. a whole month for the calendar. */
    @Query("SELECT * FROM daily_record WHERE epochDay BETWEEN :from AND :to")
    fun observeRange(from: Long, to: Long): Flow<List<DailyRecord>>
}