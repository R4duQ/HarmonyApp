package com.harmony.data.shuffle

import android.util.Log
import com.harmony.domain.shuffle.brain.ShuffleBrainLog
import com.harmony.domain.shuffle.brain.ShuffleBrainLogFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The brain's diagnostic table in Logcat: `adb logcat -s SHUFFLE_BRAIN`.
 * One Log call per line, since Logcat cuts long entries.
 */
@Singleton
class AndroidShuffleBrainLog @Inject constructor() : ShuffleBrainLog {
    override fun write(lines: List<String>) {
        lines.forEach { Log.i(ShuffleBrainLogFormatter.TAG, it) }
    }
}
