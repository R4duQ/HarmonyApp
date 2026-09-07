package com.harmony.core.media.scanner

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

internal object BackgroundPriorityDispatchers {

    private val threadFactory = ThreadFactory { runnable ->
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply {
            name = "harmony-bg-scan"
            isDaemon = true
        }
    }

    val analysis: CoroutineDispatcher =
        Executors.newFixedThreadPool(2, threadFactory).asCoroutineDispatcher()
}