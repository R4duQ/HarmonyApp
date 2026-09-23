package com.harmony.playback.service.session

/**
 * Knows whether an out-of-process media browser — Android Auto, in practice —
 * is bound to [com.harmony.playback.service.PlaybackService] right now.
 *
 * Why this exists: the service's session is shared by the phone UI and the
 * car, but the only lifecycle signal the service used was a PHONE one —
 * swiping Harmony out of recents. That tore the session down (player stopped,
 * queue cleared, foreground dropped, stopSelf) even while the car was the
 * one using it. It also never actually finished: Harmony's own in-app
 * MediaController stays bound, so stopSelf() cannot destroy a bound service,
 * and the service was left alive but gutted — no queue, not in the
 * foreground, in a process the OS was then free to freeze or kill.
 *
 * Android Auto binds with the legacy MediaBrowserService action; the in-app
 * controller binds with Media3's own action. So the legacy action's binding
 * is the signal for "the car is attached".
 *
 * Tracking it needs [onRebind]. The system caches a service's binder per
 * intent: once every client of an intent has unbound, the NEXT bind (the car
 * reconnecting after an unplug) gets that cached binder without calling
 * onBind again — and calls onRebind only if onUnbind returned true. Hence
 * [onUnbind] returns true for the legacy action; without that the service
 * would see the first connection but be blind to every reconnection.
 *
 * Plain Kotlin on purpose: the service forwards its binding callbacks here,
 * which keeps the decision testable on the JVM.
 */
class BrowserBindingTracker(private val legacyBrowserAction: String) {

    /** True while at least one legacy (car) browser is bound. */
    var carBrowserBound: Boolean = false
        private set

    /**
     * Service.onBind. [bound] is whether a binder was actually handed out —
     * a null binder means the browser was refused and is not attached.
     */
    fun onBind(action: String?, bound: Boolean) {
        if (action == legacyBrowserAction && bound) carBrowserBound = true
    }

    /** Service.onRebind: a client came back after [onUnbind] returned true. */
    fun onRebind(action: String?) {
        if (action == legacyBrowserAction) carBrowserBound = true
    }

    /**
     * Service.onUnbind: every client of [action] has gone. Returns what the
     * service should return from onUnbind — true for the legacy action so the
     * next car connection arrives as [onRebind] instead of silently.
     */
    fun onUnbind(action: String?): Boolean {
        if (action != legacyBrowserAction) return false
        carBrowserBound = false
        return true
    }

    /** What swiping the app out of recents on the phone should do. */
    fun onTaskRemoved(): TaskRemovedAction =
        if (carBrowserBound) TaskRemovedAction.KEEP_SESSION else TaskRemovedAction.SHUT_DOWN

    enum class TaskRemovedAction {
        /**
         * The car is attached: a gesture on the phone's screen must not end
         * the session the car is using. Save the position, change nothing.
         */
        KEEP_SESSION,

        /**
         * Phone only: stop, save, and let the service really be destroyed —
         * which requires dropping the app's own controller binding too.
         */
        SHUT_DOWN,
    }
}
