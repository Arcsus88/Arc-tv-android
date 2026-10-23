package com.arcsus.arcdisney.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun titleMatchesWholeWordsInOrder() {
        assertTrue(titleMatches("Up.2009.1080p.BluRay.x264", "Up"))
        assertFalse(titleMatches("Pick.Up.2014.1080p", "Up 2009"))
        assertTrue(titleMatches("WALL-E.2008.720p.BrRip", "WALL·E"))
        assertTrue(titleMatches("Lilo.and.Stitch.2002.1080p", "Lilo & Stitch"))
        assertFalse(titleMatches("The.Wolf.of.Wall.Street.2013", "WALL·E"))
    }

    @Test
    fun yearMatchesWithinOne() {
        assertTrue(yearMatches("The Lion King (1994) 720p", 1994))
        assertTrue(yearMatches("The.Lion.King.1995.1080p", 1994))
        assertFalse(yearMatches("The.Lion.King.2019.1080p", 1994))
        assertFalse(yearMatches("The.Lion.King.1080p", 1994))
    }

    @Test
    fun namedAsNeedsTheTitleFirstThenTheYear() {
        assertTrue(namedAs("[YTS.MX] Aladdin (1992) 1080p BRRip", "Aladdin", 1992))
        assertTrue(namedAs("Disney Movie - Amy 1981", "Amy", 1981))
        assertTrue(namedAs("Kidnapped  (Action 1960)  Peter Finch  720p", "Kidnapped", 1960))
        assertTrue(namedAs("Alice In Wonderland - Johnny Depp 2010 Eng Subs 1080p", "Alice in Wonderland", 2010))
        assertTrue(namedAs("The.Lion.King.2.Simbas.Pride.1998.1080p", "The Lion King II: Simba's Pride", 1998))
        assertTrue(namedAs("Zombies.2018.1080p.WEB", "Z-O-M-B-I-E-S", 2018))
        assertTrue(namedAs("Fantasia 2000 (1999) 720p BrRip", "Fantasia 2000", 2000))
        assertFalse(namedAs("Tiger.3.2023.1080p", "Tiger", 2024))
        assertFalse(namedAs("Under.Wraps.2.2022.1080p", "Under Wraps", 2021))
        assertFalse(namedAs("The.Elephant.Queen.2019.1080p", "Elephant", 2020))
        assertFalse(namedAs("Care.Bears.2014.720p", "Bears", 2014))
        assertFalse(namedAs("Return.to.Halloweentown.2006.1080p", "Halloweentown", 1998))
        assertFalse(namedAs("The Prime Of Miss Jean Brodie (1969) Maggie Smith", "Smith!", 1969))
        assertFalse(namedAs("Old Yeller 1957 Savage Sam 1963 DVDRip", "Savage Sam", 1963))
        assertFalse(namedAs("The.Lion.King.2019.1080p", "The Lion King", 1994))
    }

    @Test
    fun filtersCamsPacksExtrasAndTv() {
        assertTrue(isCam("Moana.2.2024.HDCAM.x264"))
        assertTrue(isCam("Wish.2023.TS.XviD"))
        assertFalse(isCam("Lady.and.the.Tramp.II.Scamps.Adventure.2001"))
        assertTrue(isPack("Toy Story Collection 1-4 1080p"))
        assertTrue(isPack("Pixar 25 Movies Pack"))
        assertTrue(isExtras("Frozen.2013.Bonus.Features.1080p"))
        assertTrue(tvShaped("Andor.S02E03.1080p"))
        assertFalse(tvShaped("Frozen.2013.1080p.BluRay"))
    }

    @Test
    fun streamScorePrefersSeeded1080pOverHuge4k() {
        val web1080 = Release("Encanto.2021.1080p.WEB-DL.x264", "a".repeat(40), 2 * gib, 60)
        val remux4k = Release("Encanto.2021.2160p.UHD.BluRay.REMUX.HDR", "b".repeat(40), 55 * gib, 80)
        val dub = Release("Encanto.2021.1080p.MULTi.WEB-DL", "c".repeat(40), 2 * gib, 60)
        assertTrue(streamScore(web1080) > streamScore(remux4k))
        assertTrue(streamScore(web1080) > streamScore(dub))
    }

    @Test
    fun rankPutsSeededFirst() {
        val dead = Release("Cars.2006.2160p.WEB-DL", "d".repeat(40), 4 * gib, 0)
        val alive = Release("Cars.2006.720p.BRRip", "e".repeat(40), 1 * gib, 3)
        assertEquals(listOf(alive, dead), rankReleases(listOf(dead, alive)))
    }

    @Test
    fun magnetCarriesHashNameAndTrackers() {
        val m = Release("Up (2009) 1080p", "1957368624b6ee89737f9876f9dc3a8cd794705a").magnet
        assertTrue(m.startsWith("magnet:?xt=urn:btih:1957368624B6EE89737F9876F9DC3A8CD794705A&dn=Up%20%282009%29%201080p"))
        assertEquals(Release.TRACKERS.size, Regex("&tr=").findAll(m).count())
    }

    @Test
    fun searchMatchesTitleAndYearIgnoringAccents() {
        val film = Film(id = 1, title = "Maléfique", year = "2014")
        assertTrue(film.matches(searchWords("male")))
        assertTrue(film.matches(searchWords("2014")))
        assertFalse(film.matches(searchWords("frozen")))
        assertTrue(film.matches(emptyList()))
    }
}
