package com.example.pokemoninventory

import android.content.Context
import androidx.room.Room
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InventoryEngineTest {
    private lateinit var context: Context
    private lateinit var db: InventoryDatabase
    private lateinit var engine: InventoryEngine
    private val databaseName = "inventory-test-${UUID.randomUUID()}.db"
    private var now = 1_000_000L
    private val a = "012345678905"
    private val b = "123456789012"
    private val product = ProductLookupResult("Booster box", listOf("https://example.com/product.png"))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        open()
        engine.initialize(emptyList())
    }

    private fun open() {
        db = Room.databaseBuilder(context, InventoryDatabase::class.java, databaseName).allowMainThreadQueries().build()
        engine = InventoryEngine(db, context.filesDir) { now }
    }

    private fun restart() {
        db.close()
        open()
        engine.initialize(emptyList())
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun duplicateScansDuringLookupAndImagesOnlyIncreaseQuantity() {
        engine.scan(a)
        val batch = engine.claimLookups()
        repeat(4) { engine.scan(a) }
        assertEquals(1, db.store().jobs().size)
        assertTrue(engine.claimLookups().isEmpty())
        engine.completeLookups(batch, LookupBatchResult(mapOf(a to product)))
        val images = engine.claimImages(2)
        repeat(3) { engine.scan(a) }
        assertEquals(8, engine.snapshot().single().quantity)
        assertEquals("Loading image…", engine.snapshot().single().status)
        assertTrue(engine.claimImages(2).isEmpty())
        assertTrue(engine.claimLookups().isEmpty())
        assertEquals(1, db.store().requests().size)
        engine.completeImage(images.single(), null)
        assertEquals("Waiting for image", engine.snapshot().single().status)
    }

    @Test fun queuedAndInterruptedWorkAndRateReservationsSurviveReopening() {
        engine.scan(a)
        engine.scan(b)
        val batch = engine.claimLookups()
        assertEquals(2, batch.size)
        restart()
        assertEquals(2, engine.snapshot().size)
        assertEquals(1, db.store().requests().size)
        val recovered = engine.claimLookups()
        assertEquals(2, recovered.size)
        assertTrue(recovered.all { it.attempts == 2 })
        engine.completeLookups(recovered, LookupBatchResult(mapOf(a to product, b to null)))
        engine.claimImages(2)
        restart()
        assertTrue(engine.claimLookups().isEmpty())
        assertEquals(1, engine.claimImages(2).size)
        assertEquals("No match · retry available", engine.snapshot().first { it.upc == b }.status)
    }

    @Test fun confirmedMissIsCachedAcrossRemovalAndRestartUntilExplicitRetry() {
        engine.scan(a)
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(a to null)))
        engine.remove(engine.snapshot().single().id)
        restart()
        engine.scan(a)
        assertTrue(engine.claimLookups().isEmpty())
        assertEquals("No match · retry available", engine.snapshot().single().status)
        engine.retry(engine.snapshot().single().id)
        assertEquals(1, engine.claimLookups().size)
    }

    @Test fun retriesKeepCooldownAndNeverCacheTransientFailuresAsMisses() {
        engine.scan(a)
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(retryAfter = now + 120_000))
        restart()
        assertNull(db.store().product(LookupPolicy.barcodeKey(a)))
        now += 119_999
        assertTrue(engine.claimLookups().isEmpty())
        now++
        assertEquals(1, engine.claimLookups().size)
    }

    @Test fun seventhBatchWaitsAfterRestartThenProcessesTwoUpcs() {
        repeat(6) { index ->
            val code = "123456789" + index.toString().padStart(3, '0')
            engine.scan(code)
            engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(code to null)))
        }
        engine.scan(a); engine.scan(b)
        restart()
        assertTrue(engine.claimLookups().isEmpty())
        now += 60_100
        assertEquals(2, engine.claimLookups().size)
    }

    @Test fun lateResultPreservesManualNameAndQuantityAndDoesNotRestoreRemovedItem() {
        engine.scan(a); engine.scan(b)
        val batch = engine.claimLookups()
        val item = engine.snapshot().first { it.upc == a }
        engine.editName(item.id, "My custom name")
        engine.changeQuantity(item.id, 4)
        engine.remove(engine.snapshot().first { it.upc == b }.id)
        engine.completeLookups(batch, LookupBatchResult(mapOf(a to product, b to product)))
        assertEquals(1, engine.snapshot().size)
        assertEquals("My custom name", engine.snapshot().single().name)
        assertEquals(5, engine.snapshot().single().quantity)
        assertNotNull(db.store().product(LookupPolicy.barcodeKey(b)))
    }

    @Test fun completedCacheAndImagesAreReusedForEquivalentBarcodeAfterRestart() {
        engine.scan(a)
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(a to product)))
        val job = engine.claimImages(2).single()
        val file = File(context.filesDir, "cached-test.png").apply { writeBytes(byteArrayOf(1)) }
        engine.completeImage(job, file.name)
        engine.remove(engine.snapshot().single().id)
        restart()
        engine.scan("0$a")
        assertEquals("Booster box", engine.snapshot().single().name)
        assertEquals(file.name, engine.snapshot().single().imageFileName)
        assertTrue(engine.claimLookups().isEmpty())
        assertTrue(engine.claimImages(2).isEmpty())
        file.delete()
    }

    @Test fun imageFailuresAreBoundedAndRepeatScansDoNotRestartExhaustedWork() {
        engine.scan(a)
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(a to product)))
        repeat(5) { index ->
            engine.completeImage(engine.claimImages(2).single(), null)
            now = LookupPolicy.retryAt(now, index + 1)
        }
        engine.scan(a)
        restart()
        assertEquals("Image unavailable · retry available", engine.snapshot().single().status)
        assertTrue(engine.claimImages(2).isEmpty())
        assertTrue(engine.claimLookups().isEmpty())
        engine.retry(engine.snapshot().single().id)
        assertEquals(1, engine.claimImages(2).size)
        assertEquals(1, db.store().requests().size)
    }

    @Test fun removalAndReAddDuringImageDownloadDoNotStartAnotherDownload() {
        engine.scan(a)
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(a to product)))
        val image = engine.claimImages(2).single()
        engine.remove(engine.snapshot().single().id)
        engine.scan(a)
        assertTrue(engine.claimImages(2).isEmpty())
        assertTrue(engine.claimLookups().isEmpty())
        engine.completeImage(image, null)
        assertEquals(1, db.store().jobs().size)
    }

    @Test fun startupDropsInterruptedJobsForRemovedProducts() {
        engine.scan(a)
        engine.claimLookups()
        engine.remove(engine.snapshot().single().id)
        restart()
        assertTrue(engine.snapshot().isEmpty())
        assertTrue(db.store().jobs().isEmpty())
        assertEquals(1, db.store().requests().size)
    }

    @Test fun failedInFlightWorkDoesNotRetryAfterRemoval() {
        engine.scan(a)
        val lookup = engine.claimLookups()
        engine.remove(engine.snapshot().single().id)
        engine.completeLookups(lookup, LookupBatchResult())
        assertTrue(db.store().jobs().isEmpty())
        engine.scan(a)
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(a to product)))
        val image = engine.claimImages(2).single()
        engine.remove(engine.snapshot().single().id)
        engine.completeImage(image, null)
        assertTrue(db.store().jobs().isEmpty())
    }

    @Test fun legacyImageIsRetainedInCacheAfterMetadataLookupAndReAdd() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM settings")
        val file = File(context.filesDir, "legacy-image.png").apply { writeBytes(byteArrayOf(1)) }
        engine.initialize(listOf(InventoryItem(a, "Legacy name", 2, file.name)))
        engine.completeLookups(engine.claimLookups(), LookupBatchResult(mapOf(a to product)))
        assertTrue(engine.claimImages(2).isEmpty())
        engine.remove(engine.snapshot().single().id)
        restart()
        engine.scan(a)
        assertEquals(file.name, engine.snapshot().single().imageFileName)
        assertTrue(engine.claimImages(2).isEmpty())
        file.delete()
    }

    @Test fun legacyInventoryImportsOnceAndTransactionsDoNotLoseQueueEntries() {
        // Simulate a database before the one-time migration marker was written.
        db.openHelper.writableDatabase.execSQL("DELETE FROM settings")
        val old = listOf(InventoryItem(a, "Edited legacy name", 3), InventoryItem("NO UPC", "Manual", 2))
        engine.initialize(old)
        restart()
        engine.initialize(old)
        assertEquals(2, engine.snapshot().size)
        assertEquals(5, engine.snapshot().sumOf { it.quantity })
        assertEquals(1, db.store().jobs().size)
        runCatching {
            db.runInTransaction {
                engine.scan(b)
                error("Simulated failure before transaction commit")
            }
        }
        assertFalse(engine.snapshot().any { it.upc == b })
        assertNull(db.store().job(LookupPolicy.barcodeKey(b), "lookup"))
    }

    @Test fun batchResultsAreMatchedByBarcodeAndMalformedResponsesAreRetryable() {
        val reversed = """{"code":"OK","items":[{"upc":"$b","title":"B"},{"ean":"0$a","title":"A"}]}"""
        val result = ProductLookup.parseBatch(listOf(a, b), reversed)
        assertEquals("A", result[a]?.title)
        assertEquals("B", result[b]?.title)
        val partial = ProductLookup.parseBatch(listOf(a, b), """{"code":"OK","items":[{"upc":"$b","title":"B"}]}""")
        assertNull(partial[a])
        assertNotNull(partial[b])
        assertTrue(runCatching { ProductLookup.parseBatch(listOf(a), """{"code":"TOO_FAST"}""") }.isFailure)
        assertTrue(runCatching { ProductLookup.parseBatch(listOf(a), """{"code":"OK"}""") }.isFailure)
    }
}
