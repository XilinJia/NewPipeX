package org.schabi.newpipe.settings.preferencesearch

import android.text.TextUtils
import android.util.Pair
import org.apache.commons.text.similarity.FuzzyScore
import org.schabi.newpipe.settings.preferencesearch.PreferenceSearchConfiguration.PreferenceSearchFunction
import java.util.*
import java.util.function.Function
import java.util.function.ToDoubleFunction
import java.util.stream.Collectors
import java.util.stream.Stream

class PreferenceFuzzySearchFunction : PreferenceSearchFunction {
    override fun search(allAvailable: Stream<PreferenceSearchItem>, keyword: String): Stream<PreferenceSearchItem> {
        val maxScore = (keyword.length + 1) * 3 - 2 // First can't get +2 bonus score

        return allAvailable // General search
            // Check all fields if anyone contains something that kind of matches the keyword
            .map { item: PreferenceSearchItem -> FuzzySearchGeneralDTO(item, keyword) }
            .filter { dto: FuzzySearchGeneralDTO -> dto.score / maxScore >= 0.3f }
            .map { obj: FuzzySearchGeneralDTO -> obj.item } // Specific search - Used for determining order of search results
            // Calculate a score based on specific search fields
            .map { item: PreferenceSearchItem -> FuzzySearchSpecificDTO(item, keyword) }
            .sorted(Comparator.comparingDouble { obj: FuzzySearchSpecificDTO -> obj.score }
                .reversed())
            .map { obj: FuzzySearchSpecificDTO -> obj.item } // Limit the amount of search results
            .limit(20)
    }

    internal class FuzzySearchGeneralDTO(val item: PreferenceSearchItem, keyword: String) {
        val score: Float = if (item != null) FUZZY_SCORE.fuzzyScore(
            TextUtils.join(";", item.allRelevantSearchFields),
            keyword).toFloat() else 0.0f
    }

    internal class FuzzySearchSpecificDTO(val item: PreferenceSearchItem, keyword: String) {
        val score: Double

        init {
            this.score = WEIGHT_MAP.entries.stream()
                .map { entry: Map.Entry<Function<PreferenceSearchItem, String>, Float> ->
                    Pair(if (item != null) entry.key.apply(item) else "", entry.value)
                }
                .filter { pair: Pair<String, Float> -> !pair.first.isEmpty() }
                .collect(Collectors.averagingDouble(
                    ToDoubleFunction { pair: Pair<String, Float> ->
                        (FUZZY_SCORE.fuzzyScore(pair.first, keyword) * pair.second).toDouble()
                    }))
        }

        companion object {
            private val WEIGHT_MAP: Map<Function<PreferenceSearchItem, String>, Float> =
                java.util.Map.of( // The user will most likely look for the title -> prioritize it
                    Function { obj: PreferenceSearchItem -> obj.title },
                    1.5f,  // The summary is also important as it usually contains a larger desc
                    // Example: Searching for '4k' → 'show higher resolution' is shown
                    Function { obj: PreferenceSearchItem -> obj.summary },
                    1f,  // Entries are also important as they provide all known/possible values
                    // Example: Searching where the resolution can be changed to 720p
                    Function { obj: PreferenceSearchItem -> obj.entries },
                    1f
                )
        }
    }

    companion object {
        private val FUZZY_SCORE = FuzzyScore(Locale.ROOT)
    }
}
