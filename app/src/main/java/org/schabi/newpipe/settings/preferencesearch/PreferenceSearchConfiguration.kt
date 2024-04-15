package org.schabi.newpipe.settings.preferencesearch

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import java.util.*
import java.util.stream.Stream

class PreferenceSearchConfiguration {
    var searcher: PreferenceSearchFunction = PreferenceFuzzySearchFunction()
        set(searcher) {
            field = Objects.requireNonNull(searcher)
        }

    val parserIgnoreElements: List<String> = java.util.List.of(PreferenceCategory::class.java.simpleName)
    val parserContainerElements: List<String> = java.util.List.of(
        PreferenceCategory::class.java.simpleName,
        PreferenceScreen::class.java.simpleName)


    fun interface PreferenceSearchFunction {
        fun search(allAvailable: Stream<PreferenceSearchItem>, keyword: String): Stream<PreferenceSearchItem>
    }
}
