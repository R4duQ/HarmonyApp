package com.harmony.core.ui.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InternetRecoveryTest {
    @Test fun enablesOnlyAfterFiveSecondsAndIgnoresDuplicateCapabilities() = runTest {
        val raw = MutableSharedFlow<Boolean>(extraBufferCapacity = 5)
        val states = mutableListOf<InternetState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { raw.withInternetRecovery().collect { states += it } }
        raw.emit(false); runCurrent(); assertFalse(states.last().ready)
        raw.emit(true); runCurrent(); assertEquals(5, states.last().secondsUntilReady)
        advanceTimeBy(4_000); raw.emit(true); runCurrent()
        assertFalse(states.last().ready)
        advanceTimeBy(999); runCurrent(); assertFalse(states.last().ready)
        advanceTimeBy(1); runCurrent(); assertTrue(states.last().ready)
    }
    @Test fun losingInternetCancelsRecoveryAndRequiresANewFiveSeconds() = runTest {
        val raw = MutableSharedFlow<Boolean>(extraBufferCapacity = 5)
        var state = InternetState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { raw.withInternetRecovery().collect { state = it } }
        raw.emit(true); runCurrent(); advanceTimeBy(2_000)
        raw.emit(false); runCurrent(); assertEquals(InternetState(), state)
        advanceTimeBy(10_000); runCurrent(); assertFalse(state.ready)
        raw.emit(true); runCurrent(); advanceTimeBy(4_999); runCurrent(); assertFalse(state.ready)
        advanceTimeBy(1); runCurrent(); assertTrue(state.ready)
        raw.emit(false); runCurrent(); assertFalse(state.ready)
    }
}
