package com.headunit.launcher

/**
 * The charger connectors the app knows about, in one place, so the setting that picks them, the filter that uses them and the
 * subtitle that shows them can't drift apart.
 *
 * Names are the app's own English labels ("CCS", "Type 2"…), which are also what gets saved in the preference and what the
 * place cards print. [of] maps whatever a source calls a connector onto one of them: TomTom's connectorType
 * ("Combo CCS 2", "Chademo"), OpenStreetMap's socket tag suffix ("type2_combo", "chademo") and its human labels all land on
 * the same name.
 */
object Plugs {
    /** Every connector, in the order the picker shows them: the fast ones first, then the slow ones. */
    val ALL = listOf("CCS", "CCS1", "CHAdeMO", "NACS", "Tesla", "Type 2", "Type 1", "Schuko", "3-pin")

    /** A source's connector name as one of [ALL], or null when it isn't a connector the app knows. */
    fun of(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        // (a name that is already one of ours is taken as it is, so the saved value round-trips)
        ALL.firstOrNull { it.equals(t, true) }?.let { return it }
        return when {
            // Tesla's own plug is NACS; TomTom calls the older Superchargers "Tesla"
            t.contains("NACS", true) -> "NACS"
            t.contains("Combo", true) || t.contains("CCS", true) ->
                if (t.contains("Type1", true) || t.contains("CCS1", true)) "CCS1" else "CCS"
            t.contains("Chademo", true) -> "CHAdeMO"
            t.contains("Tesla", true) -> "Tesla"
            t.contains("Type2", true) || t.contains("Type 2", true) -> "Type 2"
            t.contains("Type1", true) || t.contains("Type 1", true) -> "Type 1"
            t.contains("Schuko", true) -> "Schuko"
            t.contains("bs1363", true) || t.contains("3-pin", true) || t.contains("3 pin", true) -> "3-pin"
            else -> null
        }
    }

    /** The connectors picked in Settings ("CCS|Type 2" as saved), or an empty set when the driver hasn't picked any (any plug). */
    fun picked(prefs: Prefs): Set<String> =
        prefs.s(PreferenceKeys.EV_PLUGS, "").split('|').mapNotNull { of(it) }.toSet()

    /**
     * How many sockets of that connector an OpenStreetMap charger has from its socket value: "1", "2"… and "yes" all count as
     * one, while "no" and "0" mean it hasn't got that plug (the same rule the country pack build applies).
     */
    internal fun socketCount(value: String): Int {
        val v = value.trim()
        if (v.isEmpty() || v.equals("no", true) || v == "0") return 0
        return v.toIntOrNull() ?: 1
    }

    /**
     * The connectors an OpenStreetMap charger carries, from its socket tags ("socket:type2_combo" is "CCS"), in [ALL]'s order and
     * once each. Empty when nobody mapped them, which the caller keeps as "connectors unknown" rather than "none of yours".
     */
    fun sockets(tags: Map<String, String>): List<String> = ALL.filter { plug ->
        tags.any { (key, value) ->
            // ("socket:type2:output" and "socket:type2:current" describe a socket, they are not one)
            key.startsWith("socket:") && key.count { it == ':' } == 1 && socketCount(value) > 0 && of(key.substringAfter(':')) == plug
        }
    }

    /** [picked] as a saved preference value. */
    internal fun save(picked: Set<String>): String = ALL.filter { it in picked }.joinToString("|")

    /** Saves [picked] for the charging list, which reads it from the same preference. */
    fun set(prefs: Prefs, picked: Set<String>) { prefs.put(PreferenceKeys.EV_PLUGS, save(picked)) }

    /** Whether a charger with [plugs] is worth showing for [picked]: yes when it fits, and when nothing is known about it. */
    fun matches(plugs: List<String>, picked: Set<String>): Boolean = matchQuality(plugs, picked) > 0

    /**
     * How well a charger fits [picked]: 2 when one of its connectors is picked (or nothing is picked, so nothing is filtered),
     * 1 when its connectors aren't known, 0 when it has none of them. A charger whose plugs nobody mapped is kept: hiding it would
     * tell a CCS-only driver there's no charger when the truth is nobody mapped it.
     */
    fun matchQuality(plugs: List<String>, picked: Set<String>): Int {
        if (picked.isEmpty()) return 2
        val known = plugs.mapNotNull(::of)
        return when {
            known.any { it in picked } -> 2
            known.isEmpty() -> 1
            else -> 0
        }
    }

    /** Pick a confirmed match before an unverified charger, then prefer the shorter detour. Never pick a known mismatch. */
    internal fun bestStop(candidates: Sequence<Nearby>, picked: Set<String>): Nearby? = candidates
        .map { it to matchQuality(it.plugs, picked) }
        .filter { (_, quality) -> quality > 0 }
        .minWithOrNull(compareByDescending<Pair<Nearby, Int>> { it.second }
            .thenBy { (stop, _) -> stop.detourMin.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .thenBy { it.first.dist })?.first

    /**
     * The band a charger should leave the battery in when you get there: 15% to 25% of the car's usable battery. Reaching one at
     * 15% is still comfortable, and going below it is what a driver wants to avoid, so the stop is as late as it safely can be.
     */
    const val ARRIVE_LOW_PCT = 15.0
    const val ARRIVE_HIGH_PCT = 25.0

    /**
     * The battery left when you reach a charger [distM] ahead, driving from [startPct]: [kwhPerKm] is what the car uses and
     * [batteryKwh] its usable size. Negative once the charge can't reach it.
     */
    internal fun pctOnArrival(distM: Double, startPct: Double, kwhPerKm: Double, batteryKwh: Double): Double =
        if (batteryKwh <= 0.0 || kwhPerKm <= 0.0) startPct else startPct - distM / 1000.0 * kwhPerKm / batteryKwh * 100.0

    /**
     * How close a charger comes to the arrival band, 0 when it lands inside it and grows with the distance outside, so the
     * outermost charger still inside the band wins a tie and a charger far outside it is never preferred to a nearer-to-band one.
     */
    internal fun bandGap(pct: Double): Double = when {
        pct < ARRIVE_LOW_PCT -> ARRIVE_LOW_PCT - pct
        pct > ARRIVE_HIGH_PCT -> pct - ARRIVE_HIGH_PCT
        else -> 0.0
    }

    /**
     * The charger to stop at when the stop should be as late as the battery safely allows: the one furthest along the route that
     * still leaves [ARRIVE_LOW_PCT]..[ARRIVE_HIGH_PCT], confirmed connectors first, then unknown ones; a known mismatch is never
     * picked. When no charger lands in the band, the one nearest to it is taken (further along wins at equal gap), and with no
     * arrival figure at all it falls back to [bestStop].
     */
    internal fun latestStop(candidates: Sequence<Nearby>, picked: Set<String>, startPct: Double, kwhPerKm: Double, batteryKwh: Double): Nearby? {
        val scored = candidates
            .filter { it.detourMin >= 0 }
            .mapNotNull { n ->
                val quality = matchQuality(n.plugs, picked)
                if (quality == 0) null
                else Stop(n, quality, bandGap(pctOnArrival(n.dist, startPct, kwhPerKm, batteryKwh)), n.dist)
            }
        // (confirmed connectors first, then the smallest gap to the band, then the furthest along the route)
        return scored.minWithOrNull(compareByDescending<Stop> { it.quality }.thenBy { it.gap }.thenByDescending { it.dist })?.place
    }

    private class Stop(val place: Nearby, val quality: Int, val gap: Double, val dist: Double)

    /** 2 when [results] hold a charger with a picked connector (or nothing is picked), 1 when only unknown ones, else 0. */
    internal fun resultQuality(results: List<Nearby>, picked: Set<String>): Int {
        var best = 0
        for (result in results) {
            val quality = matchQuality(result.plugs, picked)
            if (quality == 2) return quality
            best = maxOf(best, quality)
        }
        return best
    }

}
