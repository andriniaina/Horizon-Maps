package com.headunit.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import java.io.File
import java.io.RandomAccessFile
import org.junit.Test

/**
 * The chargers kept on the device: the pack format, search tiles and candidate ordering.
 */
class OfflineChargersTest {

    /** A short straight route through the test positions. */
    private fun routeAcrossTown() =
        Route(listOf(LL(-0.01, -0.01), LL(0.02, 0.02)), doubleArrayOf(0.0, 4_300.0), emptyList(), 4_300.0, 0.0, 0.0)

    @Test fun aStoredLineStartsWithItsTileAndHasNoTabsInsideTheFields() {
        val line = OfflineChargers.line("Tesla\tSupercharger", "Schaan", LL(47.16520, 9.50765), 125, listOf("CCS", "Type 2"), 10)
        assertEquals("the columns are tab separated, so no field may hold one", 8, line.split('\t').size)
        assertTrue("the tile sorts the file", line.matches(Regex("^\\d{10}\t.*")))
        assertEquals(OfflineChargers.tile(LL(47.16520, 9.50765)), line.substringBefore('\t'))
        assertEquals("Tesla Supercharger\t47.16520\t9.50765\tSchaan\t125\tCCS|Type 2\t10", line.substringAfter('\t'))
    }

    @Test fun noConnectorsAndNoPowerIsStillALine() {
        // (a charger mapped with nothing but its position: it must still be kept, or it would vanish from the list)
        val line = OfflineChargers.line("Charging point", "", LL(51.5, -0.12), 0, emptyList(), 0)
        assertEquals("Charging point\t51.50000\t-0.12000\t\t0\t\t0", line.substringAfter('\t'))
    }

    @Test fun theTileIsTheSameSchemeAsTheOtherSortedFiles() {
        // (zoom 15: 32768 tiles across the world, laid out and flattened exactly as cameras.tsv and lamps.tsv do it)
        assertEquals("1089716373", OfflineChargers.tile(LL(51.5, -0.12)))
        assertEquals("1150317249", OfflineChargers.tile(LL(47.16520, 9.50765)))
    }

    @Test fun aTownsSearchReadsOnlyItsNeighbouringTiles() {
        // (the bug this came from: a 6 km search in Paris spans 16 x 16 tiles, far more than the old 40-tile cap, and the whole
        // search came back empty because the cap was hit)
        val tiles = OfflineChargers.tilesShown(LL(48.8566, 2.3522), 6000)
        assertTrue("Paris 6 km needs hundreds of tiles, and all of them are read: ${tiles.size}", tiles.size > 200)
        assertTrue(OfflineChargers.tile(LL(48.8566, 2.3522)) in tiles)
    }

    @Test fun routeWindowIncludesAnIntersectingSegmentWithBothVerticesOutside() {
        val route = Route(listOf(LL(0.0, 0.0), LL(0.0, 1.0)), doubleArrayOf(0.0, 111_000.0), emptyList(), 111_000.0, 0.0, 0.0)
        val tiles = OfflineChargers.routeTiles(route, 20_000.0, 60_000.0, 2_000.0)
        fun encodedTile(pos: LL): Int {
            val key = OfflineChargers.tile(pos)
            return (key.take(5).toInt() shl 15) or key.takeLast(5).toInt()
        }
        assertTrue(encodedTile(LL(0.0, 0.5)) in tiles)
        assertTrue(encodedTile(route.pts.first()) !in tiles && encodedTile(route.pts.last()) !in tiles)
        val within = OfflineChargers.line("Within", "", LL(0.0, 0.5), 0, emptyList(), 0)
        val beyond = OfflineChargers.line("Beyond", "", LL(0.0, 0.73), 0, emptyList(), 0)
        val results = OfflineChargers.nearRouteOf(sequenceOf(within, beyond), route, 20_000.0, 60_000.0) { 3 }
        assertEquals(listOf("Within"), results.map { it.place.name })
        assertEquals(3, results.single().detourMin)
        assertTrue(results.single().ahead)
    }

    @Test fun tileLookupHandlesLongUnicodeRowsAndAnUnterminatedFinalRow() {
        val a = "0000100001\t" + "é ".repeat(10_000)
        val b = "0000200002\tCharge"
        val c = "0000200002\tSecond"
        val f = File.createTempFile("charger-lookup", ".tsv")
        try {
            f.writeText("$a\n$b\n$c")
            RandomAccessFile(f, "r").use { raf ->
                assertEquals(listOf(a), OfflineChargers.readTile(raf, "0000100001").toList())
                assertEquals(listOf(b, c), OfflineChargers.readTile(raf, "0000200002").toList())
                assertTrue(OfflineChargers.readTile(raf, "0000300003").none())
            }
        } finally { f.delete() }
    }

    @Test fun manifestSelectsOnlyTheListedAssetsInOrder() {
        val hash = "a".repeat(64)
        assertEquals(listOf("chargers.tsv" to hash), OfflineChargers.parseManifest("$hash  chargers.tsv\n"))
        assertEquals(listOf("chargers.tsv.part00" to hash, "chargers.tsv.part01" to hash),
            OfflineChargers.parseManifest("$hash  chargers.tsv.part00\n$hash  chargers.tsv.part01\n"))
    }

    @Test(expected = IOException::class) fun manifestRejectsMissingPartNumbers() {
        val hash = "a".repeat(64)
        OfflineChargers.parseManifest("$hash  chargers.tsv.part00\n$hash  chargers.tsv.part02\n")
    }

    @Test fun routeShortlistKeepsNearestChargersEvenWhenTheyAppearLastInTheFile() {
        val route = Route(listOf(LL(0.0, 0.0), LL(0.0, 1.0)), doubleArrayOf(0.0, 111_000.0), emptyList(), 111_000.0, 0.0, 0.0)
        val far = (100 downTo 1).map { OfflineChargers.line("Far $it", "", LL(0.0, 0.1 + it * 0.001), 0, listOf("CCS"), 0) }
        val near = OfflineChargers.line("Nearest match", "", LL(0.0, 0.02), 0, listOf("CCS"), 0)
        val unknown = OfflineChargers.line("Nearest unknown", "", LL(0.0, 0.01), 0, emptyList(), 0)
        val projected = OfflineChargers.nearRouteOf((far + near + unknown).asSequence(), route, 0.0, 60_000.0) { 3 }
        val shortlist = OfflineChargers.shortlist(projected, setOf("CCS"))
        assertEquals(listOf("Nearest unknown", "Nearest match"), shortlist.take(2).map { it.place.name })
        assertEquals(61, shortlist.size)
    }

    @Test fun aLaterReturnLegCannotHideAChargerAhead() {
        // (the road passes the same spot again further on: the chargers must be picked by the stretch being looked at, not by
        // whichever stretch of the whole route happens to be closest)
        val origin = LL(0.0, 0.0)
        val hauledAhead = LL(0.0, 0.60)
        val returnLeg = LL(0.01, 0.10)
        val firstLeg = Route(listOf(origin, hauledAhead), doubleArrayOf(0.0, 66_792.0), emptyList(), 66_792.0, 0.0, 0.0)
        val withReturn = Route(listOf(origin, hauledAhead, LL(0.01, 0.60), returnLeg), doubleArrayOf(0.0, 66_792.0, 67_898.0, 123_557.0), emptyList(), 123_557.0, 0.0, 0.0)
        val church = OfflineChargers.line("Church", "", returnLeg, 0, listOf("CCS"), 1)
        val pass = returnLeg.lon * 111_000.0
        assertEquals(listOf("Church"), OfflineChargers.nearRouteOf(sequenceOf(church), firstLeg, 0.0, 60_000.0) { 3 }.map { it.place.name })
        // (the same charger, listed before the road comes back: it is beside the road 11.1 km in, and the return leg is 123.6 km in)
        listOf(0.0, pass - 200.0, pass - 1.0, pass).forEach { behind ->
            assertTrue("a charger beside the road up to $behind m in is listed, even though the route comes back past it",
                OfflineChargers.nearRouteOf(sequenceOf(church), withReturn, 0.0, 60_000.0, behind) { 3 }.isNotEmpty())
        }
        // (past the point where it is beside the road, it is behind you, not ahead)
        listOf(pass + 500.0, pass + 2_000.0, 100_000.0).forEach { behind ->
            assertTrue("a charger $behind m in is behind you", OfflineChargers.nearRouteOf(sequenceOf(church), withReturn, 0.0, 60_000.0, behind) { 3 }.isEmpty())
        }
    }

    @Test fun aSavedPlaceIsNeverTakenForACharger() {
        // (opening a saved place looks up a charger with the same name nearby: a place tagged with its folder or a GPX link must not
        // come back with those tags read as power and connectors, which would hide it in the list and on its card)
        val lines = sequenceOf(
            // a real charger, with everything the app writes
            OfflineChargers.line("Looks right", "", LL(0.0, 0.0), 50, listOf("CCS"), 2),
            // a saved place: the columns after the position hold its folder and its GPX link instead of power and connectors
            OfflineChargers.tile(LL(0.0, 0.0)) + "\tGym\t0.00000\t0.00000\tfolder:Gym\tgpx:abc\tCCS\t",
            // a connector the app doesn't know says nothing, so the charger still shows (and has no connectors listed)
            OfflineChargers.tile(LL(0.0, 0.0)) + "\tUnknown plug\t0.00000\t0.00000\t\t0\thydrogen\t0",
            // not what the app writes at all
            "0000000000\tBroken\tzero\tzero\t\t0\tCCS\t0",
        )
        val found = OfflineChargers.nearRouteOf(lines, routeAcrossTown(), 0.0, 60_000.0) { 0 }
        assertEquals(listOf("Looks right", "Unknown plug"), found.map { it.place.name })
        assertEquals(listOf("CCS"), found.first().plugs)
        assertTrue("an unknown connector mustn't read as the car's", found.last().plugs.isEmpty())
    }

    @Test fun theShortlistKeepsChargersWithUnknownConnectorsPastTheCap() {
        val route = Route(listOf(LL(0.0, 0.0), LL(0.0, 1.0)), doubleArrayOf(0.0, 111_000.0), emptyList(), 111_000.0, 0.0, 0.0)
        // (only known to differ is left out: otherwise the display filter would empty a list that had chargers worth a look)
        val other = (1..100).map { OfflineChargers.line("Other $it", "", LL(0.0, 0.01 + it * 0.005), 0, listOf("CHAdeMO"), 0) }
        val unknown = OfflineChargers.line("Unknown plugs", "", LL(0.0, 0.6), 0, emptyList(), 0)
        val projected = OfflineChargers.nearRouteOf((other + unknown).asSequence(), route, 0.0, 70_000.0) { 3 }
        assertEquals(listOf("Unknown plugs"), OfflineChargers.shortlist(projected, setOf("CCS")).map { it.place.name })
        // (with no connector picked every charger fits, so the confirmed cap applies rather than the fallback one)
        assertEquals(60, OfflineChargers.shortlist(projected, emptySet()).size)
    }
}
