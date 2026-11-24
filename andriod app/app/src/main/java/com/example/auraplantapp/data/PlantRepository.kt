package com.example.auraplantapp.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * The single entry point to stored data. Sensor/AI code calls the on...() functions;
 * the UI collects todayRecord() and month().
 *
 * The day resets at local midnight (12 am), following the phone's time zone.
 * A Clock is injected so tests can control the time.
 */
class PlantRepository(
    private val dao: DailyRecordDao,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    companion object {
        /** Light level (1-5) at or above which the "enough light" task counts as done. */
        const val LIGHT_OK_LEVEL = 3
    }

    private fun today(): Long = LocalDate.now(clock).toEpochDay()

    private suspend fun onToday(block: suspend (Long) -> Unit) {
        val day = today()
        dao.ensureRow(DailyRecord(epochDay = day))
        block(day)
    }

    // ---- Events from sensors / AI ----

    /** One watering: task done, water level +1 (max MAX_STATUS_LEVEL). */
    suspend fun onWatered() = onToday { dao.addWater(it, MAX_STATUS_LEVEL) }

    /** Marks the light task done directly, without a level. */
    suspend fun onLightOk() = onToday { dao.markLightOk(it) }

    /** Saves the latest light reading (0-5); the task is done once it reaches LIGHT_OK_LEVEL. */
    suspend fun onLightLevel(level: Int) = onToday {
        val clamped = level.coerceIn(0, MAX_STATUS_LEVEL)
        dao.setLightLevel(it, clamped, clamped >= LIGHT_OK_LEVEL)
    }

    suspend fun onPositiveMessage(score: Float) =
        onToday { dao.markPositiveMessage(it, score) }

    suspend fun onPat() = onToday { dao.addPat(it) }

    suspend fun onDrop() = onToday { dao.addDrop(it) }

    /**
     * Main path for the recorded-quote flow: record -> transcribe -> AI positivity check.
     * Saves the transcription and the AI result together. Blank text is ignored.
     * A positive result marks the positive-message task done; a later negative one
     * doesn't undo it.
     */
    suspend fun onRecordedQuoteAnalysed(text: String, positive: Boolean, score: Float) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onToday { dao.saveRecordedQuoteResult(it, trimmed, positive, score) }
        }
    }

    /**
     * Fallback for when transcription worked but the AI check failed: saves only the
     * transcription of the quote the user recorded today (trimmed).
     * Blank text is ignored; recording again the same day replaces the earlier one.
     */
    suspend fun onQuoteTranscribed(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) onToday { dao.setRecordedQuote(it, trimmed) }
    }

    // ---- Reads for the UI ----

    /** Emits the current day's epochDay now, and again just after each midnight. */
    private fun dateTicker(): Flow<Long> = flow {
        while (true) {
            val now = LocalDateTime.now(clock)
            emit(now.toLocalDate().toEpochDay())
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis() + 500)
        }
    }.distinctUntilChanged()

    /** Today's record; switches to the new day automatically after midnight. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun todayRecord(): Flow<DailyRecord?> =
        dateTicker().flatMapLatest { dao.observeDay(it) }

    /** All records in a month, for the calendar. Days with no row have no entry. */
    fun month(month: YearMonth): Flow<List<DailyRecord>> =
        dao.observeRange(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
}