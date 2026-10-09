package com.bandmr.app.ui.library

import com.bandmr.app.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    @Test
    fun blankQueryKeepsEverySong() {
        val songs = listOf(song(1, "가"), song(2, "나"))
        assertEquals(songs, songsMatchingQuery(songs, "  "))
    }

    @Test
    fun queryMatchesTitleIgnoringCaseAndSurroundingSpace() {
        val songs = listOf(
            song(1, "Monday Kiz"),
            song(2, "박재정"),
            song(3, "monday night"),
        )
        assertEquals(listOf(1L, 3L), songsMatchingQuery(songs, "  monday ").map { it.id })
    }

    @Test
    fun separatedCountIsAmongMatchesOnly() {
        val songs = listOf(
            song(1, "Monday", separated = true),
            song(2, "Monday live", separated = false),
            song(3, "다른 곡", separated = true),
        )
        val matched = songsMatchingQuery(songs, "monday")
        assertEquals(2, matched.size)
        assertEquals(1, matched.count { it.isSeparated })
    }

    private fun song(id: Long, title: String, separated: Boolean = false) = Song(
        id = id,
        title = title,
        uri = "file://x",
        durationMs = 1_000,
        separatedTier = if (separated) "4s-balanced" else null,
        stemsDir = if (separated) "/stems/$id" else null,
    )
}
