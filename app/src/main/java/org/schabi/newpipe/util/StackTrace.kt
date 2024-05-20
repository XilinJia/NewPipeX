package org.schabi.newpipe.util

import android.util.Log
import org.schabi.newpipe.BuildConfig

fun Logd(t: String, m: String) {
    if (BuildConfig.DEBUG) Log.d(t, m)
}

fun showStackTrace() {
    if (BuildConfig.DEBUG) {
        val stackTraceElements = Thread.currentThread().stackTrace
        stackTraceElements.forEach { element ->
            Log.d("showStackTrace", element.toString())
        }
    }
}