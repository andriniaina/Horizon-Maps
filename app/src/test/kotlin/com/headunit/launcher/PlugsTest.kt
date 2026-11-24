package com.headunit.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charger connectors picked in Settings, and which chargers they keep. The rule that matters most: a charger whose
 * connectors the data doesn't give must never be dropped, or a CCS-only driver is told there are no chargers when the truth
 * is that nobody mapped them.
 */
class PlugsTest {

    // ---- what a source calls a connector becomes one of ours

    @Test fun tomtomConnectorTypesAreRecognised() {
        assertEquals("CCS", Plugs.of("Combo CCS 2"))
        assertEquals("CHAdeMO", Plugs.of("Chademo"))
        assertEquals("Type 2", Plugs.of("Type2"))
        assertEquals("Type 1", Plugs.of("Type1"))
        assertEquals("Tesla", Plugs.of("Tesla Supercharger"))
    }

    @Test fun openStreetMapSocketTagsAreRecognised() {
        assertEquals("CCS", Plugs.of("type2_combo"))
        assertEquals("CCS1", Plugs.of("type1_combo"))
        assertEquals("CHAdeMO", Plugs.of("chademo"))
        assertEquals("Type 2", Plugs.of("type2"))
        assertEquals("Type 2", Plugs.of("type2_cable"))
        assertEquals("NACS", Plugs.of("nacs"))
        assertEquals("3-pin", Plugs.of("bs1363"))
        assertEquals("Schuko", Plugs.of("schuko"))
    }

    @Test fun ourOwnNamesRoundTrip() {
        // (what gets saved is looked up again, so a picked connector must read back as itself)
        for (p in Plugs.ALL) assertEquals(p, Plugs.of(p))
    }

    @Test fun nonsenseIsNotAConnector() {
        assertNull(Plugs.of(""))
        assertNull(Plugs.of("   "))
        assertNull(Plugs.of("hydrogen"))
    }

    // ---- the filter

    @Test fun nothingPickedKeepsEverything() {
        assertTrue(Plugs.matches(listOf("CCS"), emptySet()))
        assertTrue(Plugs.matches(emptyList(), emptySet()))
    }

    @Test fun aChargerWithThePickIsKept() {
        assertTrue(Plugs.matches(listOf("CCS", "Type 2"), setOf("CCS")))
        assertTrue(Plugs.matches(listOf("CCS", "Type 2"), setOf("Type 2", "CHAdeMO")))
    }

    @Test fun aChargerWithoutThePickIsDropped() {
        assertFalse(Plugs.matches(listOf("CHAdeMO"), setOf("CCS")))
        assertFalse(Plugs.matches(listOf("Type 2"), setOf("CCS", "NACS")))
    }

    @Test fun aChargerWithNoConnectorDataIsKept() {
        // this is the important one: unknown is not the same as "doesn't fit"
        assertTrue(Plugs.matches(emptyList(), setOf("CCS")))
        // a name the app doesn't recognise counts as no data either
        assertTrue(Plugs.matches(listOf("hydrogen"), setOf("CCS")))
    }

    @Test fun namesAreMatchedCaseInsensitively() {
        assertEquals("CCS", Plugs.of("ccs"))
        assertEquals("Type 2", Plugs.of("type 2"))
    }

    private val ccsStop = Nearby(Place("CCS station", "", LL(0.0, 0.0)), 3_000.0, "", detourMin = 6, plugs = listOf("CCS"))
    private val otherStop = ccsStop.copy(place = ccsStop.place.copy(name = "CHAdeMO station"), dist = 100.0, detourMin = 1, plugs = listOf("CHAdeMO"))
    private val unknownStop = ccsStop.copy(place = ccsStop.place.copy(name = "Unverified station"), dist = 200.0, detourMin = 2, plugs = emptyList())

    @Test fun confirmedStopBeatsShorterUnverifiedAndIncompatibleDetours() {
        assertEquals(ccsStop, Plugs.bestStop(sequenceOf(otherStop, unknownStop, ccsStop), setOf("CCS")))
    }

    @Test fun stopFallsBackToUnverifiedButNeverKnownMismatch() {
        assertEquals(unknownStop, Plugs.bestStop(sequenceOf(otherStop, unknownStop), setOf("CCS")))
        assertNull(Plugs.bestStop(sequenceOf(otherStop), setOf("CCS")))
    }

    @Test fun anyConnectorChoosesShortestDetourEvenWithoutPlugData() {
        assertEquals(unknownStop, Plugs.bestStop(sequenceOf(ccsStop, unknownStop), emptySet()))
        assertEquals(otherStop, Plugs.bestStop(sequenceOf(otherStop, ccsStop, unknownStop), emptySet()))
    }

    @Test fun nearbyStopWithoutDetourUsesDistanceWithinCompatibilityTier() {
        val close = ccsStop.copy(detourMin = -1)
        val farther = close.copy(dist = 4_000.0)
        assertEquals(close, Plugs.bestStop(sequenceOf(unknownStop, farther, close), setOf("CCS")))
    }

    // ---- charging late: the furthest charger that still leaves 15–25% on arrival

    // a car whose 100% is 300 km: 60 kWh at 0.2 kWh/km, so 1% is 3 km
    private val cons = 0.2
    private val batt = 60.0

    private fun stopAt(km: Double, plugs: List<String> = listOf("CCS"), detour: Int = 3) =
        Nearby(Place("at $km km", "", LL(0.0, 0.0)), km * 1000.0, "", detourMin = detour, ahead = true, plugs = plugs)

    @Test fun batteryOnArrivalComesFromTheCarsOwnFigures() {
        assertEquals(80.0, Plugs.pctOnArrival(0.0, 80.0, cons, batt), 1e-9)
        assertEquals(60.0, Plugs.pctOnArrival(60_000.0, 80.0, cons, batt), 1e-9)
        assertEquals(5.0, Plugs.pctOnArrival(225_000.0, 80.0, cons, batt), 1e-9)
        assertEquals(80.0, Plugs.pctOnArrival(60_000.0, 80.0, 0.0, batt), 1e-9)
    }

    @Test fun theBandIsFifteenToTwentyFiveWithNoGapInside() {
        assertEquals(0.0, Plugs.bandGap(15.0), 1e-9)
        assertEquals(0.0, Plugs.bandGap(20.0), 1e-9)
        assertEquals(0.0, Plugs.bandGap(25.0), 1e-9)
        assertEquals(4.0, Plugs.bandGap(11.0), 1e-9)
        assertEquals(9.0, Plugs.bandGap(34.0), 1e-9)
    }

    @Test fun theStopIsTheFurthestChargerStillInsideTheBand() {
        // 80% start: 165 km ahead is 25% left, 195 km is 15%, 210 km would be 10%
        val near = stopAt(40.0)
        val inBand = stopAt(180.0)
        val farthestInBand = stopAt(190.0)
        val tooFar = stopAt(210.0)
        assertEquals(farthestInBand, Plugs.latestStop(sequenceOf(near, inBand, farthestInBand, tooFar), setOf("CCS"), 80.0, cons, batt))
    }

    @Test fun aChargerOutsideTheBandLosesToTheNearestOneInsideIt() {
        // 150 km is 30% left (5 points too early), 168 km is 24% (inside) and 230 km is 3.3% (21.7 points late)
        val tooEarly = stopAt(30.0)
        val aBitEarly = stopAt(150.0)
        val inside = stopAt(168.0)
        assertEquals(inside, Plugs.latestStop(sequenceOf(tooEarly, aBitEarly, inside, stopAt(230.0)), setOf("CCS"), 80.0, cons, batt))
    }

    @Test fun withNothingInsideTheBandTheClosestToItIsTaken() {
        // 80% start, so 120 km is 40% (17 points early) and 240 km is 0% (15 points late): the late one is closer to the band
        val early = stopAt(120.0)
        val empty = stopAt(240.0)
        assertEquals(empty, Plugs.latestStop(sequenceOf(early, empty), setOf("CCS"), 80.0, cons, batt))
    }

    @Test fun aConfirmedConnectorComesBeforeAnUnknownOneEvenIfSlightlyLater() {
        val confirmed = stopAt(185.0)
        val unknown = stopAt(190.0, plugs = emptyList())
        assertEquals(confirmed, Plugs.latestStop(sequenceOf(confirmed, unknown), setOf("CCS"), 80.0, cons, batt))
    }

    @Test fun aKnownMismatchIsNeverPickedEvenWhenItIsTheFurthest() {
        val chademo = stopAt(190.0, plugs = listOf("CHAdeMO"))
        val ccs = stopAt(150.0)
        assertEquals(ccs, Plugs.latestStop(sequenceOf(ccs, chademo), setOf("CCS"), 80.0, cons, batt))
        assertNull(Plugs.latestStop(sequenceOf(chademo), setOf("CCS"), 80.0, cons, batt))
    }

    @Test fun anyConnectorStillStopsAtTheFurthestInTheBand() {
        val near = stopAt(50.0, plugs = emptyList())
        val far = stopAt(190.0, plugs = emptyList())
        assertEquals(far, Plugs.latestStop(sequenceOf(near, far), emptySet(), 80.0, cons, batt))
    }

    @Test fun aLowerStartPctBringsTheBandCloser() {
        // 30% start: 15 km ahead is 25%, 45 km is 15%; the 190 km charger is long out of reach
        val answer = Plugs.latestStop(sequenceOf(stopAt(20.0), stopAt(44.0), stopAt(190.0)), setOf("CCS"), 30.0, cons, batt)
        assertEquals(44.0, answer!!.dist / 1000.0, 1e-9)
    }

    // ---- saving

    @Test fun savingKeepsThePickerOrderAndDropsUnknowns() {
        assertEquals("CCS|Type 2", Plugs.save(setOf("Type 2", "CCS")))
        assertEquals("CCS", Plugs.save(setOf("CCS", "hydrogen")))
        assertEquals("", Plugs.save(emptySet()))
    }
}
