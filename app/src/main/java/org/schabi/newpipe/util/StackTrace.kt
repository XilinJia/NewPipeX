package org.schabi.newpipe.util

fun printStackTrace() {
    val stackTraceElements = Thread.currentThread().stackTrace
    stackTraceElements.forEach { element ->
        println(element)
    }
}
