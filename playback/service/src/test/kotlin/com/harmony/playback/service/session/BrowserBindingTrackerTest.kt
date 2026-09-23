package com.harmony.playback.service.session

import com.harmony.playback.service.session.BrowserBindingTracker.TaskRemovedAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives [BrowserBindingTracker] through the binding sequences the system
 * produces when the phone connects to, leaves and reconnects to the car.
 *
 * [FakeBindingHost] models the platform's per-intent binding rules (the part
 * of ActivityManager these bugs live in): the first bind of an intent calls
 * onBind and caches the binder; later binds reuse it; onUnbind runs when the
 * intent's last client leaves; and a later bind calls onRebind only if
 * onUnbind returned true. [ModelService] forwards those callbacks exactly as
 * PlaybackService does.
 */
class BrowserBindingTrackerTest {

    @Test
    fun `first connection - car attached, swiping the phone app keeps the session`() {
        val host = FakeBindingHost(ModelService())
        host.bind(APP_CONTROLLER)   // Harmony's own MediaController (process start)
        host.bind(CAR)              // Android Auto opens its browser

        assertEquals(TaskRemovedAction.KEEP_SESSION, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `phone only - swiping the app shuts the session down`() {
        val host = FakeBindingHost(ModelService())
        host.bind(APP_CONTROLLER)

        assertEquals(TaskRemovedAction.SHUT_DOWN, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `car unplugged - swiping the app shuts the session down again`() {
        val host = FakeBindingHost(ModelService())
        host.bind(APP_CONTROLLER)
        host.bind(CAR)
        host.unbind(CAR)

        assertEquals(TaskRemovedAction.SHUT_DOWN, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `reconnection to the same service instance is seen through onRebind`() {
        val host = FakeBindingHost(ModelService())
        host.bind(APP_CONTROLLER)   // keeps this service instance alive across the unplug
        host.bind(CAR)
        host.unbind(CAR)            // unplug
        host.bind(CAR)              // plug back in

        assertEquals(1, host.service.onBindCalls[CAR])
        assertEquals(1, host.service.onRebindCalls[CAR])
        assertEquals(TaskRemovedAction.KEEP_SESSION, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `without asking for onRebind the reconnection would be invisible`() {
        // The platform default (onUnbind returning false): the reconnecting
        // car gets the cached binder and the service is never told. This is
        // why BrowserBindingTracker.onUnbind returns true for the car action.
        val host = FakeBindingHost(ModelService(honourRebindRequest = false))
        host.bind(APP_CONTROLLER)
        host.bind(CAR)
        host.unbind(CAR)
        host.bind(CAR)

        assertEquals(0, host.service.onRebindCalls[CAR] ?: 0)
        assertEquals(TaskRemovedAction.SHUT_DOWN, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `reconnection after the service was destroyed starts clean`() {
        val host = FakeBindingHost(ModelService())
        host.bind(CAR)              // Auto cold-starts the service, no app controller
        host.unbind(CAR)            // unplug: last client gone, not started -> destroyed
        assertEquals(true, host.destroyed)

        val fresh = FakeBindingHost(ModelService())
        fresh.bind(CAR)
        assertEquals(1, fresh.service.onBindCalls[CAR])
        assertEquals(TaskRemovedAction.KEEP_SESSION, fresh.service.tracker.onTaskRemoved())
    }

    @Test
    fun `a refused browser is not treated as attached`() {
        val host = FakeBindingHost(ModelService(sessionAvailable = false))
        host.bind(APP_CONTROLLER)
        host.bind(CAR)

        assertEquals(TaskRemovedAction.SHUT_DOWN, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `car stays attached until the last legacy browser leaves`() {
        // Auto and, say, System UI's resumption browser bind the same intent;
        // onUnbind only fires once both are gone.
        val host = FakeBindingHost(ModelService())
        host.bind(APP_CONTROLLER)
        host.bind(CAR)
        host.bind(CAR)
        host.unbind(CAR)
        assertEquals(TaskRemovedAction.KEEP_SESSION, host.service.tracker.onTaskRemoved())

        host.unbind(CAR)
        assertEquals(TaskRemovedAction.SHUT_DOWN, host.service.tracker.onTaskRemoved())
    }

    @Test
    fun `Media3 controllers never count as a car`() {
        // The in-app controller plus any other Media3 client (the action a
        // Media3 MediaController binds with) — none of them is the car.
        val host = FakeBindingHost(ModelService())
        host.bind(APP_CONTROLLER)
        host.bind(APP_CONTROLLER)
        host.unbind(APP_CONTROLLER)

        assertEquals(TaskRemovedAction.SHUT_DOWN, host.service.tracker.onTaskRemoved())
    }

    /** Forwards binding callbacks the way PlaybackService does. */
    private class ModelService(
        private val sessionAvailable: Boolean = true,
        private val honourRebindRequest: Boolean = true,
    ) {
        val tracker = BrowserBindingTracker(CAR)
        val onBindCalls = mutableMapOf<String, Int>()
        val onRebindCalls = mutableMapOf<String, Int>()

        fun onBind(action: String): Any? {
            onBindCalls.merge(action, 1, Int::plus)
            val binder = if (sessionAvailable || action == APP_CONTROLLER) Any() else null
            tracker.onBind(action, bound = binder != null)
            return binder
        }

        fun onRebind(action: String) {
            onRebindCalls.merge(action, 1, Int::plus)
            tracker.onRebind(action)
        }

        fun onUnbind(action: String): Boolean {
            val wantsRebind = tracker.onUnbind(action)
            return honourRebindRequest && wantsRebind
        }
    }

    /** The platform's per-intent binding bookkeeping, for one service instance. */
    private class FakeBindingHost(val service: ModelService) {
        private class IntentBinding {
            var received = false
            var binder: Any? = null
            var clients = 0
            var doRebind = false
        }

        private val bindings = mutableMapOf<String, IntentBinding>()
        var destroyed = false
            private set

        fun bind(action: String) {
            check(!destroyed)
            val b = bindings.getOrPut(action) { IntentBinding() }
            b.clients++
            if (!b.received) {
                b.binder = service.onBind(action)
                b.received = true
            } else if (b.clients == 1 && b.doRebind) {
                b.doRebind = false
                service.onRebind(action)
            }
        }

        fun unbind(action: String) {
            val b = checkNotNull(bindings[action])
            b.clients--
            if (b.clients == 0) b.doRebind = service.onUnbind(action)
            if (bindings.values.all { it.clients == 0 }) destroyed = true
        }
    }

    private companion object {
        const val CAR = "android.media.browse.MediaBrowserService"
        const val APP_CONTROLLER = "androidx.media3.session.MediaLibraryService"
    }
}
