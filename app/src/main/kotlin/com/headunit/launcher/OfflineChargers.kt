package com.headunit.launcher

import android.content.Context
import kotlin.math.max
import kotlin.math.min
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Chargers for the country you drive in, kept on the device, so the charging list (and the connectors picked in Settings) work with
 * no signal at all.
 *
 * The file is the pack's `chargers.tsv`, made by the country pack builds ([.github/workflows/build-country-packs.yml]) and published
 * with the rest of the pack: one line per charging point, sorted by its zoom-15 tile (`%05d%05d`, the same scheme as cameras.tsv), so
 * a lookup reads a few tiles instead of the whole country:
 *
 *     tile \t name \t lat \t lon \t street \t kW \t CCS|Type 2 \t points
 */
object OfflineChargers {
    private fun file(ctx: Context, pack: PackRegion) = File(ctx.filesDir, "chargers_pack-${pack.id}.tsv")

    fun installed(ctx: Context, pack: PackRegion): Boolean = file(ctx, pack).length() > 0

    // ---- lookup

    /**
     * Chargers within [radius] metres of [c], nearest first, as the online search returns them. Only the tiles the circle touches are
     * scanned within the lookup budget. The caller can shortlist by connector before displaying the results.
     */
    fun near(ctx: Context, c: LL, radius: Int, pack: PackRegion): List<Nearby> =
        nearOf(linesIn(file(ctx, pack), tilesIn(boxOf(c, radius))), c, radius)

    private fun nearOf(lines: Sequence<String>, c: LL, radius: Int): List<Nearby> {
        val out = ArrayList<Nearby>()
        for (l in lines) {
            val ch = charger(l) ?: continue
            val d = Geo.dist(c, ch.place.pos)
            if (d > radius) continue
            out += ch.copy(dist = d)
        }
        return out.sortedBy { it.dist }
    }

    /**
     * The chargers worth listing, sorted by distance: cap only after sorting, since route lookups yield candidates in tile order. A
     * charger whose connectors are known to differ is left out; one whose connectors nobody mapped is always kept (with no connector
     * picked every charger fits, so nothing is left out).
     */
    internal fun shortlist(candidates: List<Nearby>, picked: Set<String>): List<Nearby> {
        val nearest = candidates.sortedBy { it.dist }
        val fit = nearest.filter { Plugs.matchQuality(it.plugs, picked) > 0 }
        val confirmed = fit.asSequence().filter { Plugs.matchQuality(it.plugs, picked) == 2 }.take(60).toList()
        return (confirmed + fit.take(40)).distinctBy { it.place.pos }.sortedBy { it.dist }
    }

    private const val ROUTE_RADIUS = 2_000

    /** Search the tiles along the requested route window, rather than a capped rectangle around it. */
    fun nearRoute(ctx: Context, r: Route, progress: Double, ahead: Double, behind: Double, pack: PackRegion, detour: (Double) -> Int): List<Nearby> =
        nearRouteOf(linesIn(file(ctx, pack), routeTiles(r, progress, ahead, behind).sorted().asSequence()), r, progress, ahead, progress - behind, detour)

    /** The stretch that counts: from [behind] to the lookahead, so a road that comes back later can't hide a charger ahead now. */
    internal fun nearRouteOf(lines: Sequence<String>, r: Route, progress: Double, ahead: Double, behind: Double = progress - 100, detour: (Double) -> Int): List<Nearby> {
        val from = behind; val to = progress + ahead
        val out = ArrayList<Nearby>()
        for (line in lines) {
            val ch = charger(line) ?: continue
            val (offset, offRoad) = RouteExtras.project(r, ch.place.pos, ROUTE_RADIUS.toDouble(), from, to) ?: continue
            if (offset !in from..to) continue
            out += ch.copy(dist = offset - progress, detourMin = detour(offRoad), ahead = true)
        }
        return out
    }

    /**
     * One stored line as a charger (distance 0: the caller works it out), or null when it is not one. A file built with a different
     * column layout (an older or broken build) is refused rather than read as a charger with the wrong connectors.
     */
    private fun charger(l: String): Nearby? {
        val cols = l.split('\t')
        if (cols.size < 8) return null
        val tile = cols[0]
        if (tile.length != 10 || tile.any { it !in '0'..'9' }) return null
        val la = cols[2].toDoubleOrNull() ?: return null
        val lo = cols[3].toDoubleOrNull() ?: return null
        // (a saved place carries its folder or a GPX link where the power goes: it is not a charger)
        if (cols[5].toIntOrNull() == null || cols[6].contains(':') || cols[4].startsWith("folder:")) return null
        val kw = cols[5].toIntOrNull() ?: return null
        // (a connector the app doesn't know is dropped: keeping it as written would count as "connectors known, none of yours")
        val plugs = cols[6].split('|').mapNotNull { Plugs.of(it) }.distinct()
        val points = cols[7].toIntOrNull() ?: 0
        // (wording kept the same as the online card: "350 kW · CCS, Type 2", "8 points")
        val extra = ArrayList<String>()
        if (kw > 0) extra += "$kw kW"
        if (plugs.isNotEmpty()) extra += plugs.take(3).joinToString(", ")
        if (points > 0) extra += "$points points"
        return Nearby(Place(cols[1], cols[4], LL(la, lo)), 0.0, extra.joinToString(" · "), plugs = plugs)
    }

    private class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    private fun boxOf(c: LL, radius: Int): Box {
        val dLat = radius / 110_574.0
        val edgeLat = (kotlin.math.abs(c.lat) + dLat).coerceAtMost(90.0)
        val dLon = (radius / (111_320.0 * Math.cos(Math.toRadians(edgeLat)))).coerceAtMost(180.0)
        return Box(c.lat - dLat, c.lat + dLat, c.lon - dLon, c.lon + dLon)
    }

    private fun tilesIn(box: Box): Sequence<Int> = sequence {
        val west = tx(box.minLon); val east = tx(box.maxLon)
        for (y in ty(box.maxLat)..ty(box.minLat)) {
            for (x in west..east) yield((y shl 15) or x)
            if (box.minLon < -180) for (x in tx(box.minLon + 360)..32767) yield((y shl 15) or x)
            if (box.maxLon > 180) for (x in 0..tx(box.maxLon - 360)) yield((y shl 15) or x)
        }
    }

    internal fun routeTiles(r: Route, progress: Double, ahead: Double, behind: Double): Set<Int> {
        val from = progress - behind
        val to = progress + ahead
        val tiles = HashSet<Int>()
        // Carry a sample across short segments. Every point of the clipped route is at most 1 km
        // along the geometry from a sample, so a 3 km box covers the 2 km search corridor.
        var remaining = 0.0
        for (i in 1 until r.pts.size) {
            val start = r.cum[i - 1]; val end = r.cum[i]
            if (start > to) break
            if (end < from || end <= start) continue
            val a = r.pts[i - 1]; val b = r.pts[i]
            val first = (max(from, start) - start) / (end - start)
            val last = (min(to, end) - start) / (end - start)
            if (first > last) continue
            val lonDelta = (b.lon - a.lon + 540.0) % 360.0 - 180.0
            // Use a conservative length for interpolation, including sparse/high-latitude segments.
            val length = Math.hypot((b.lat - a.lat) * 111_320.0, lonDelta * 111_320.0)
            if (length == 0.0) continue
            val span = (last - first) * length
            while (remaining <= span) {
                val t = first + remaining / length
                val lon = (a.lon + lonDelta * t + 540.0) % 360.0 - 180.0
                tiles.addAll(tilesIn(boxOf(LL(a.lat + (b.lat - a.lat) * t, lon), 3_000)))
                remaining += 1_000
            }
            remaining -= span
        }
        return tiles
    }

    /**
     * The stored chargers in [tiles], read one tile at a time: the file is sorted by tile, so each is a binary search and a few
     * sequential lines. [budgetMs] keeps a long corridor (a 60 km route at 2 km wide, or a town at rush-hour density) from eating
     * the whole search timeout; the search then returns what it read.
     */
    private fun linesIn(f: File, tiles: Sequence<Int>, budgetMs: Long = 2_000L): Sequence<String> = sequence {
        if (!f.isFile) return@sequence
        val deadline = System.currentTimeMillis() + budgetMs
        RandomAccessFile(f, "r").use { raf ->
            val buffer = ByteArray(8192)
            for (tile in tiles) {
                if (System.currentTimeMillis() > deadline) break
                yieldAll(readTile(raf, tileKey(tile), buffer))
            }
        }
    }

    internal fun tilesShown(c: LL, radius: Int): List<String> = tilesIn(boxOf(c, radius)).map(::tileKey).toList()

    private fun tx(lon: Double) = Math.floor((lon + 180.0) / 360.0 * 32768.0).toInt().coerceIn(0, 32767)

    private fun ty(lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * 32768.0).toInt().coerceIn(0, 32767)
    }

    private fun tileKey(tile: Int) = String.format(Locale.ROOT, "%05d%05d", tile ushr 15, tile and 32767)

    internal fun readTile(raf: RandomAccessFile, key: String, buffer: ByteArray = ByteArray(8192)): Sequence<String> = sequence {
        raf.seek(lowerBound(raf, key, buffer))
        while (raf.filePointer < raf.length()) {
            val row = raf.readLine() ?: break
            if (!row.startsWith("$key\t")) break
            yield(row.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8))
        }
    }

    /** Both search bounds remain row boundaries, including when the probe lands in a long row. */
    private fun lowerBound(raf: RandomAccessFile, target: String, buffer: ByteArray): Long {
        var lo = 0L
        var hi = raf.length()
        val key = ByteArray(10)
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            var start = mid
            findStart@ while (start > lo) {
                val from = maxOf(lo, start - buffer.size)
                val n = (start - from).toInt()
                raf.seek(from)
                raf.readFully(buffer, 0, n)
                for (i in n - 1 downTo 0) {
                    if (buffer[i] == '\n'.code.toByte()) {
                        start = from + i + 1
                        break@findStart
                    }
                }
                start = from
            }
            raf.seek(start)
            val n = raf.read(key)
            if (n > 0 && String(key, 0, n, Charsets.US_ASCII) >= target) {
                hi = start
            } else {
                raf.seek(mid)
                var end = mid
                findEnd@ while (end < hi) {
                    val count = raf.read(buffer, 0, minOf(buffer.size.toLong(), hi - end).toInt())
                    if (count <= 0) break
                    for (i in 0 until count) if (buffer[i] == '\n'.code.toByte()) {
                        end += i + 1
                        break@findEnd
                    }
                    end += count
                }
                lo = end
            }
        }
        return lo
    }

    // ---- download

    /** Fetch the charger list published for the selected pack. */
    fun download(ctx: Context, pack: PackRegion): Int =
        fromPack(file(ctx, pack), pack.extrasUrl).takeIf { it > 0 }
            ?: throw IOException("No charger list published for ${pack.name}")

    /** Returns 0 only when the release has no published charger list. */
    private fun fromPack(dst: File, base: String): Int {
        // Prefer the manifest even if a legacy whole-file asset is still on the release.
        val assets = asset("$base/chargers.tsv.sha256") { input, size ->
            val text = input.readNBytesCompat(8193)
            if (text.size > 8192 || (size >= 0 && text.size.toLong() != size)) throw IOException("Incomplete or oversized charger manifest")
            parseManifest(text.toString(Charsets.UTF_8))
        }
        val tmp = File.createTempFile("chargers-", ".tmp", dst.parentFile)
        try {
            tmp.outputStream().buffered().use { out ->
                if (assets == null) {
                    // Older releases and manual uploads may have only the whole file.
                    asset("$base/chargers.tsv") { input, size ->
                        if (size <= 0 || input.copyTo(out) != size) throw IOException("Incomplete charger download")
                    } ?: return 0
                } else for ((name, hash) in assets) {
                    asset("$base/$name") { input, _ ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        input.copyTo(DigestOutputStream(out, digest), 64 * 1024)
                        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        if (actual != hash) throw IOException("Invalid charger asset: $name")
                    } ?: throw IOException("Missing charger asset: $name")
                }
            }
            val rows = count(tmp)
            if (rows == 0 || !tmp.renameTo(dst)) throw IOException("Couldn't install charger download")
            return rows
        } finally { tmp.delete() }
    }

    internal fun parseManifest(text: String): List<Pair<String, String>> {
        val entries = text.trim().lines()
        if (entries.size > 100) throw IOException("Too many charger assets")
        return entries.mapIndexed { index, line ->
            val match = Regex("([0-9a-f]{64})  (chargers\\.tsv(?:\\.part[0-9]{2})?)").matchEntire(line)
                ?: throw IOException("Invalid charger manifest")
            val (hash, name) = match.destructured
            val part = "chargers.tsv.part" + index.toString().padStart(2, '0')
            if (name != part && !(entries.size == 1 && name == "chargers.tsv")) throw IOException("Unexpected charger asset: $name")
            name to hash
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var used = 0
        while (used < limit) {
            val n = read(buffer, used, limit - used)
            if (n < 0) break
            used += n
        }
        return buffer.copyOf(used)
    }

    /** A missing asset is distinct from a failed fetch. */
    private fun <T> asset(url: String, read: (java.io.InputStream, Long) -> T): T? {
        val c = (URL(url).openConnection() as HttpURLConnection).also {
            it.connectTimeout = 20000; it.readTimeout = 120000; it.instanceFollowRedirects = true
        }
        try {
            val status = c.responseCode
            if (status == 404) return null
            if (status != 200) throw IOException("HTTP $status fetching charger data")
            return c.inputStream.use { read(it, c.contentLengthLong) }
        } finally { c.disconnect() }
    }

    /** One charger as a stored line: tile, name, lat, lon, street, kW, connectors, points. */
    internal fun line(name: String, sub: String, pos: LL, kw: Int, plugs: List<String>, points: Int): String =
        listOf(tile(pos), name.replace('\t', ' '), fmt(pos.lat), fmt(pos.lon), sub.replace('\t', ' '), kw.toString(),
            plugs.joinToString("|"), points.toString()).joinToString("\t")

    internal fun tile(pos: LL) = tileKey((ty(pos.lat) shl 15) or tx(pos.lon))

    fun delete(ctx: Context, pack: PackRegion) { file(ctx, pack).delete() }

    /** A count of the chargers in a stored file. */
    private fun count(f: File): Int = try { f.useLines { it.count() } } catch (e: Exception) { 0 }

    /** How many chargers a stored country has. */
    fun count(ctx: Context, pack: PackRegion): Int = count(file(ctx, pack))

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.5f", v)
}
