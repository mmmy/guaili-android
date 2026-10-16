package com.gouge.guaili.widget

import com.gouge.guaili.data.GuailiDeviceTime
import org.junit.Assert.*
import org.junit.Test

class PullbackWidgetFreshnessTest {
    private val receipt = GuailiDeviceTime(1_000_000L, 100_000L, 1)

    @Test fun normalBackgroundSamplingIsNotLateAfterTheMarketEvidenceExpires() {
        assertFalse(pullbackWidgetReceiptOverdue(receipt, receipt.copy(elapsedMillis = receipt.elapsedMillis + 45_000)))
        assertFalse(pullbackWidgetReceiptOverdue(receipt, receipt.copy(elapsedMillis = receipt.elapsedMillis + 15 * 60_000)))
        assertTrue(pullbackWidgetReceiptOverdue(receipt, receipt.copy(elapsedMillis = receipt.elapsedMillis + 17 * 60_000)))
    }

    @Test fun persistedMonotonicReceiptRejectsRebootRollbackAndUnknownWithoutUsingWallClock() {
        assertFalse(pullbackWidgetReceiptOverdue(receipt, receipt.copy(wallMillis = Long.MAX_VALUE)))
        assertTrue(pullbackWidgetReceiptOverdue(receipt, receipt.copy(bootCount = 2)))
        assertTrue(pullbackWidgetReceiptOverdue(receipt, receipt.copy(elapsedMillis = 99_999)))
        assertTrue(pullbackWidgetReceiptOverdue(receipt.copy(bootCount = null), receipt))
        assertTrue(pullbackWidgetReceiptOverdue(null, receipt))
    }

    @Test fun identicalReadsCoalesceReceiptWritesButAdvanceVerificationAtOneMinute() {
        assertTrue(shouldPersistPullbackReceipt(null, receipt))
        assertFalse(shouldPersistPullbackReceipt(receipt, receipt.copy(elapsedMillis = receipt.elapsedMillis + 10_000)))
        assertTrue(shouldPersistPullbackReceipt(receipt, receipt.copy(elapsedMillis = receipt.elapsedMillis + 60_000)))
    }
}
