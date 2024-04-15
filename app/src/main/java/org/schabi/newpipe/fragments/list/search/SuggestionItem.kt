package org.schabi.newpipe.fragments.list.search

class SuggestionItem(@JvmField val fromHistory: Boolean, @JvmField val query: String) {
    override fun equals(o: Any?): Boolean {
        if (o is SuggestionItem) return query == o.query
        return false
    }

    override fun hashCode(): Int {
        return query.hashCode()
    }

    override fun toString(): String {
        return "[$fromHistory→$query]"
    }
}
