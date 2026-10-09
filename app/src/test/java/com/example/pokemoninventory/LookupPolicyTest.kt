package com.example.pokemoninventory

import org.junit.Assert.*
import org.junit.Test

class LookupPolicyTest {
    @Test fun seventhRequestWaitsForRollingWindow() {
        val times = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L)
        assertEquals(61_100L, LookupPolicy.nextRequestAt(7_000, times, 0))
        assertEquals(61_100L, LookupPolicy.nextRequestAt(61_100, times, 0))
    }

    @Test fun dailyQuotaAndServerCooldownSurviveRestartAndClockRollback() {
        val requests = (1..100).map { it * 61_000L }
        assertEquals(requests.first() + LookupPolicy.DAY + 100,
            LookupPolicy.nextRequestAt(7_000_000, requests, 0))
        assertEquals(200_000L, LookupPolicy.nextRequestAt(100_000, emptyList(), 200_000))
        assertTrue(LookupPolicy.nextRequestAt(0, List(6) { 1_000L }, 0) > 60_000)
    }

    @Test fun retryBackoffIsBoundedAndBarcodeKeysPreserveIdentity() {
        assertEquals(30_000L, LookupPolicy.retryAt(0, 1))
        assertEquals(3_600_000L, LookupPolicy.retryAt(0, 100))
        assertEquals(LookupPolicy.barcodeKey("012345678905"), LookupPolicy.barcodeKey("0012345678905"))
        assertNotEquals(LookupPolicy.barcodeKey("012345678905"), LookupPolicy.barcodeKey("112345678905"))
    }

    @Test fun upceExpansionMatchesUpcaWhileOtherScannerFormatsRemainUntouched() {
        assertEquals("012000003455", LookupPolicy.expandUpce("01234505"))
        assertEquals("012300000455", LookupPolicy.expandUpce("01234535"))
        assertEquals("012340000055", LookupPolicy.expandUpce("01234545"))
        assertEquals("012345000065", LookupPolicy.expandUpce("01234565"))
        assertEquals("1234567", LookupPolicy.expandUpce("1234567"))
    }
}
