package com.headunit.launcher

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * The places that choose a charger run in the app (Android), so they can't be called from a JVM test: this guards the wiring, and
 * [PlugsTest] and [OfflineChargersTest] check the rule itself. A charger the data says has another connector must never be chosen,
 * a charger nobody mapped can be, and changing the connectors in Settings has to be noticed by the route you're on.
 */
class ChargerPreferenceWiringTest {

    private fun source(name: String): String = File("src/main/kotlin/com/headunit/launcher/$name").readText()

    @Test fun bothAutomaticStopsAskTheConnectorAwarePicker() {
        val engine = source("NavEngine.kt")
        // (the one-tap stop from the stops box, and the charging stop suggested on the route options)
        assertTrue("the one-tap stop still picks the best place itself", engine.contains("Plugs.bestStop(ahead.asSequence(), picked)"))
        assertTrue("the suggested charging stop ignores the connectors", engine.contains("val usable = out.asSequence().filter { it.detourMin >= 0 }") &&
            engine.contains("Plugs.bestStop(usable, picked)"))
        assertTrue("the suggested stop is chosen before the connectors are read", engine.indexOf("val picked = Plugs.picked(prefs)") < engine.indexOf("Plugs.bestStop(usable, picked)"))
        // (charging late ranks by the battery left on arrival, but only inside the connectors the car has)
        assertTrue("charging late ignores the connectors", engine.contains("Plugs.latestStop(ahead.asSequence(), picked,") &&
            engine.contains("Plugs.latestStop(usable, picked,"))
    }

    @Test fun chargingLateCanBeTurnedOffAndMovesTheStop() {
        val engine = source("NavEngine.kt")
        assertTrue("nothing reads the late-charging setting", engine.contains("private fun lateCharge(): Boolean = prefs.b(PreferenceKeys.EV_LATE_CHARGE, false)"))
        assertTrue("the suggested stop never charges late", engine.contains("if (late) Plugs.latestStop("))
        assertTrue("the one-tap stop never charges late", engine.indexOf("if (lateCharge()) Plugs.latestStop(") > 0)
        assertTrue("a lookup started before the switch changed can still publish", engine.contains("&& late == lateCharge()"))
        val settings = File("src/main/kotlin/com/headunit/launcher/ui/SettingsScreen.kt").readText()
        val at = settings.indexOf("PreferenceKeys.EV_LATE_CHARGE")
        assertTrue("the switch is not in Settings", at > 0)
        val row = settings.substring(at, minOf(settings.length, at + 700))
        assertTrue("flipping the switch leaves the old stop on the route", row.contains("engine.refreshEvPlan()"))
        assertTrue("the switch doesn't say what it does", row.contains("15–25%"))
    }

    @Test fun theSuggestedStopIsLookedAtAgainWhenTheConnectorsChange() {
        val engine = source("NavEngine.kt")
        assertTrue("a connector change leaves the old charging stop on screen", engine.contains("fun refreshEvPlan()"))
        assertTrue("looking again goes through the charger-aware search", engine.contains("if (prefs.b(PreferenceKeys.SMARTFUEL, true)) route?.let(::suggestFuel)"))
        assertTrue("a lookup started before the change can still publish its stop", engine.contains("if (route === r && stillTheSame()) fuelSuggestion = best"))
        val settings = File("src/main/kotlin/com/headunit/launcher/ui/SettingsScreen.kt").readText()
        assertTrue("picking connectors doesn't refresh the route's charging stop", settings.contains("Plugs.set(prefs, picked)") && settings.contains("engine.refreshEvPlan()"))
        assertTrue("the open charging list keeps the connectors it was searched with", settings.contains("engine.loadNearby(\"charging\", false)"))
    }

    @Test fun theElectricCarSettingsThatPickTheStopRefreshIt() {
        val settings = File("src/main/kotlin/com/headunit/launcher/ui/SettingsScreen.kt").readText()
        // (efficiency and usable battery decide where the battery runs low, so the suggested stop moves with them)
        for (key in listOf("EVMPK", "EV_BATTERY_KWH")) {
            val at = settings.indexOf("PreferenceKeys.$key")
            assertTrue("$key is not in the electric-car group", at >= 0)
            val row = settings.substring(at, minOf(settings.length, at + 700))
            assertTrue("changing $key leaves the old charging stop on the route", row.contains("engine.refreshEvPlan()"))
        }
    }

    @Test fun theChargerListFiltersWithTheKindItSearchedFor() {
        val engine = source("NavEngine.kt")
        // (filtering by whatever was in the box by the time results arrive could drop chargers, or keep them, for the wrong search)
        assertTrue(engine.contains("nearby = chargerFilter(kind, sortNearby(list)).take(15)"))
        assertTrue(engine.contains("private fun chargerFilter(kind: String?, l: List<Nearby>): List<Nearby> {"))
        assertTrue("chargers known to have another connector are listed", engine.contains("Plugs.matchQuality(it.plugs, picked) != 0"))
    }
}
