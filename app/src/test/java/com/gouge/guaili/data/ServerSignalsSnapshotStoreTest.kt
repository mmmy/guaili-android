package com.gouge.guaili.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSignalsSnapshotStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun separateInstancesObserveSuccessFailureAndDisabledUpdates() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFolder().resolve("server_signals.preferences_pb")
        }
        val widget = ServerSignalsSnapshotStore(dataStore)
        val worker = ServerSignalsSnapshotStore(dataStore)
        val snapshot = ServerSignalsSnapshot(
            ServerSignalsResponse(true, "ready", 20_000L),
            19_000L, "http://server-a:3005/",
            GuailiServerClock(20_000L, 1_000L, 7, 100L),
        )
        widget.snapshots.test {
            assertNull(awaitItem())
            worker.save(snapshot)
            assertEquals(snapshot, awaitItem())
            assertEquals(snapshot, widget.readForBaseUrl("http://server-a:3005"))
            assertNull(widget.readForBaseUrl("http://server-b:3005"))

            val failure = ServerSignalsFailure("network failure", 21_000L, snapshot.baseUrl)
            worker.recordFailure(failure)
            assertEquals(failure, widget.readFailure())
            assertEquals(snapshot, widget.read())
            expectNoEvents()

            val disabled = snapshot.copy(
                response = snapshot.response.copy(enabled = false, status = "disabled", results = emptyList()),
                updatedAt = 22_000L,
            )
            worker.save(disabled)
            assertEquals(disabled, awaitItem())
            assertNull(widget.readFailure())
        }
    }

    @Test fun monotonicTimeSurvivesWallClockChangesAndRejectsReboots() {
        val snapshot = ServerSignalsSnapshot(
            ServerSignalsResponse(true, "ready", 10_000L),
            10_000L, "http://server",
            GuailiServerClock(10_000L, 1_000L, 7, 100L),
        )
        val assessment = assessServerSignalsTime(snapshot, GuailiDeviceTime(90_000_000L, 4_000L, 7))
        assertTrue(assessment.available)
        assertEquals(13_000L, assessment.nowMillis)
        assertEquals(3_000L, assessment.cacheAgeMillis)
        assertFalse(assessServerSignalsTime(snapshot, GuailiDeviceTime(13_000L, 4_000L, 8)).available)
        assertFalse(assessServerSignalsTime(snapshot.copy(serverClock = null), GuailiDeviceTime(13_000L, 4_000L, 7)).available)
    }
}
