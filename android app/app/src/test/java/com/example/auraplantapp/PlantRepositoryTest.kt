package com.example.auraplantapp.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** A clock whose time can be moved during a test. */
class TestClock(var now: Instant, private val zone: ZoneId = ZoneId.of("Asia/Singapore")) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
    override fun instant(): Instant = now
}

// Put this file under app/src/androidTest/java/com/example/auraplantapp/data/
@RunWith(AndroidJUnit4::class)
class PlantRepositoryTest {

    private lateinit var db: PlantDatabase
    private lateinit var clock: TestClock
    private lateinit var repo: PlantRepository

    // 2026-11-22 10:00 Singapore time
    private val morning = Instant.parse("2026-11-22T02:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java).build()
        clock = TestClock(morning)
        repo = PlantRepository(db.dailyRecordDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun watering_marks_task_done() = runBlocking {
        repo.onWatered()
        val record = repo.todayRecord().first()!!
        assertTrue(record.wateredDone)
        assertFalse(record.lightDone)
        assertEquals(1, record.tasksCompleted)
    }

    @Test
    fun water_level_rises_and_caps_at_max() = runBlocking {
        repo.onWatered()
        assertEquals(1, repo.todayRecord().first()!!.waterLevel)
        repeat(7) { repo.onWatered() }
        assertEquals(MAX_STATUS_LEVEL, repo.todayRecord().first()!!.waterLevel)
    }

    @Test
    fun light_task_done_only_when_level_is_enough() = runBlocking {
        repo.onLightLevel(PlantRepository.LIGHT_OK_LEVEL - 1)
        var record = repo.todayRecord().first()!!
        assertEquals(PlantRepository.LIGHT_OK_LEVEL - 1, record.lightLevel)
        assertFalse(record.lightDone)

        repo.onLightLevel(4)
        record = repo.todayRecord().first()!!
        assertEquals(4, record.lightLevel)
        assertTrue(record.lightDone)

        // The task stays done for the day even if the light drops again.
        repo.onLightLevel(1)
        record = repo.todayRecord().first()!!
        assertEquals(1, record.lightLevel)
        assertTrue(record.lightDone)
    }

    @Test
    fun light_level_is_clamped_to_scale() = runBlocking {
        repo.onLightLevel(99)
        assertEquals(MAX_STATUS_LEVEL, repo.todayRecord().first()!!.lightLevel)
    }

    @Test
    fun recorded_quote_transcription_is_saved_trimmed_and_blank_is_ignored() = runBlocking {
        repo.onQuoteTranscribed("  Small steps grow strong roots.  ")
        assertEquals("Small steps grow strong roots.", repo.todayRecord().first()!!.recordedQuoteText)

        repo.onQuoteTranscribed("   ")
        assertEquals("Small steps grow strong roots.", repo.todayRecord().first()!!.recordedQuoteText)

        // Recording again the same day replaces the earlier transcription.
        repo.onQuoteTranscribed("A new day, a new leaf.")
        assertEquals("A new day, a new leaf.", repo.todayRecord().first()!!.recordedQuoteText)
    }

    @Test
    fun recorded_quote_saves_text_and_positive_result_together() = runBlocking {
        repo.onRecordedQuoteAnalysed("  I am growing every day.  ", positive = true, score = 0.9f)
        val record = repo.todayRecord().first()!!
        assertEquals("I am growing every day.", record.recordedQuoteText)
        assertTrue(record.positiveMessageDone)
        assertEquals(0.9f, record.sentimentScore!!, 0.001f)
        assertEquals(1, record.tasksCompleted)
    }

    @Test
    fun negative_recorded_quote_saves_text_but_not_the_task() = runBlocking {
        repo.onRecordedQuoteAnalysed("Today was awful.", positive = false, score = 0.1f)
        var record = repo.todayRecord().first()!!
        assertEquals("Today was awful.", record.recordedQuoteText)
        assertFalse(record.positiveMessageDone)
        assertEquals(0.1f, record.sentimentScore!!, 0.001f)
        assertEquals(0, record.tasksCompleted)

        // A later positive quote completes the task and replaces the text.
        repo.onRecordedQuoteAnalysed("Tomorrow will be better.", positive = true, score = 0.8f)
        record = repo.todayRecord().first()!!
        assertEquals("Tomorrow will be better.", record.recordedQuoteText)
        assertTrue(record.positiveMessageDone)

        // A later negative quote does not undo it.
        repo.onRecordedQuoteAnalysed("Ugh.", positive = false, score = 0.2f)
        record = repo.todayRecord().first()!!
        assertTrue(record.positiveMessageDone)
        assertEquals(0.2f, record.sentimentScore!!, 0.001f)
    }

    @Test
    fun blank_recorded_quote_is_ignored_entirely() = runBlocking {
        repo.onRecordedQuoteAnalysed("   ", positive = true, score = 0.9f)
        assertNull(repo.todayRecord().first())
    }

    @Test
    fun events_do_not_overwrite_each_other() = runBlocking {
        repo.onWatered()
        repo.onPositiveMessage(0.8f)
        repo.onPat()
        repo.onPat()
        repo.onDrop()
        val record = repo.todayRecord().first()!!
        assertTrue(record.wateredDone)
        assertTrue(record.positiveMessageDone)
        assertEquals(0.8f, record.sentimentScore!!, 0.001f)
        assertEquals(2, record.patCount)
        assertEquals(1, record.dropCount)
    }

    @Test
    fun mood_follows_task_count() = runBlocking {
        assertEquals(DailyMood.SAD, (null as DailyRecord?).mood())
        repo.onWatered()
        assertEquals(DailyMood.OKAY, repo.todayRecord().first().mood())
        repo.onLightOk()
        assertEquals(DailyMood.OKAY, repo.todayRecord().first().mood())
        repo.onPositiveMessage(0.9f)
        assertEquals(DailyMood.HAPPY, repo.todayRecord().first().mood())
    }

    @Test
    fun new_day_starts_empty_and_old_day_is_kept() = runBlocking {
        // 11:59 pm Singapore on 22 Nov = 15:59 UTC
        clock.now = Instant.parse("2026-11-22T15:59:00Z")
        repo.onWatered()

        // 12:01 am Singapore on 23 Nov = 16:01 UTC
        clock.now = Instant.parse("2026-11-22T16:01:00Z")
        assertNull(repo.todayRecord().first())

        val nov = repo.month(java.time.YearMonth.of(2026, 11)).first()
        assertEquals(1, nov.size)
        assertTrue(nov.first().wateredDone)
    }
}