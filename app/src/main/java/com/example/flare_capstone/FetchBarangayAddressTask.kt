package com.example.flare_capstone

import android.os.AsyncTask
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FetchBarangayAddressTask(
    private val activity: Any, // FireLevelActivity, OtherEmergencyActivity, or EmergencyMedicalServicesActivity
    private val latitude: Double,
    private val longitude: Double
) : AsyncTask<Void, Void, String?>() {

    override fun doInBackground(vararg params: Void?): String? {
        // 1) Overpass: containing admin relations
        val rels = queryOverpassRelations(latitude, longitude)
        var barangay = pickBarangayFromRelations(rels)?.let { normalizeBarangay(it) }
        var city     = pickCityFromRelations(rels)?.let { normalizeCity(it) }

        // 2) If barangay still null, look for nearby barangay-ish features
        if (barangay == null) {
            val nearby = queryOverpassNearbyBarangayish(latitude, longitude, 1200) // meters
            barangay = pickNearestBarangayish(nearby, latitude, longitude)?.let { normalizeBarangay(it) }
        }

        // 2.5) Extra fallback: try to get barangay from Nominatim (suburb/city_district/village)
        if (barangay == null) {
            barangay = queryBarangayFromNominatim(latitude, longitude)?.let { normalizeBarangay(it) }
        }

        // 3) City strict fallback
        if (city == null) {
            city = queryCityFromNominatim(latitude, longitude)?.let { normalizeCity(it) }
        }

        // 4) Try to resolve Purok/Zone/Sitio near the point
        var purok = queryPurokFromNominatim(latitude, longitude)?.let { normalizePurok(it) }
        if (purok == null) {
            val nearbyPurok = queryOverpassNearbyPurokish(latitude, longitude, 1000)
            purok = pickNearestPurokish(nearbyPurok, latitude, longitude)?.let { normalizePurok(it) }
        }

        // 5) De-dup barangay vs city
        val bClean = barangay?.replace(Regex("(?i)^Barangay\\s+"), "")?.trim()
        val cClean = city?.trim()
        val finalBarangay = if (!bClean.isNullOrEmpty() && !cClean.isNullOrEmpty() && bClean.equals(cClean, true)) null else barangay

        // 6) Compose result (if barangay missing, return null so caller can handle)
        if (finalBarangay.isNullOrBlank()) return null

        val parts = if (!purok.isNullOrBlank())
            listOfNotNull(purok, finalBarangay, city)
        else
            listOfNotNull(finalBarangay, city)

        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    override fun onPostExecute(result: String?) {
        super.onPostExecute(result)
        when (activity) {
            is FireLevelActivity -> activity.handleFetchedAddress(result)
            is OtherEmergencyActivity -> activity.handleFetchedAddress(result)
            is EmergencyMedicalServicesActivity -> activity.handleFetchedAddress(result) // <-- added
        }
    }

    // ---------- Overpass: containing relations ----------
    private fun queryOverpassRelations(lat: Double, lon: Double): JSONArray {
        val q = """
            [out:json][timeout:25];
            is_in($lat,$lon)->.a;
            rel.a["boundary"="administrative"];
            out tags;
        """.trimIndent()
        return overpassPost(q)
    }

    private fun pickBarangayFromRelations(elements: JSONArray): String? {
        var fallback: String? = null
        for (i in 0 until elements.length()) {
            val tags = elements.optJSONObject(i)?.optJSONObject("tags") ?: continue
            if (tags.optString("admin_level") == "10") {
                val name = bestName(tags) ?: continue
                val looksBrgy = tags.keys().asSequence().any { k ->
                    val v = tags.optString(k).lowercase()
                    k.lowercase().contains("barangay") || v.contains("barangay") || v.startsWith("brgy")
                } || tags.optString("admin_type:PH").equals("barangay", true)
                if (looksBrgy) return name
                if (fallback == null) fallback = name
            }
        }
        return fallback
    }

    private fun pickCityFromRelations(elements: JSONArray): String? {
        var byPlace: String? = null
        var byLevel: String? = null
        for (i in 0 until elements.length()) {
            val tags = elements.optJSONObject(i)?.optJSONObject("tags") ?: continue
            val place = tags.optString("place").lowercase()
            val level = tags.optString("admin_level")
            val name  = bestName(tags) ?: continue

            when (place) {
                "city" -> return name
                "municipality" -> if (byPlace == null) byPlace = name
                "town" -> if (byPlace == null) byPlace = name
            }
            if (level in listOf("7","8","6") && byLevel == null) byLevel = name
        }
        return byPlace ?: byLevel
    }

    // ---------- Overpass: nearby barangay-ish nodes/ways ----------
    private fun queryOverpassNearbyBarangayish(lat: Double, lon: Double, radiusM: Int): JSONArray {
        val q = """
            [out:json][timeout:25];
            (
              node(around:$radiusM,$lat,$lon)["admin_type:PH"="barangay"];
              way (around:$radiusM,$lat,$lon)["admin_type:PH"="barangay"];
              relation(around:$radiusM,$lat,$lon)["admin_type:PH"="barangay"];
              node(around:$radiusM,$lat,$lon)["boundary"="administrative"]["admin_level"="10"];
              way (around:$radiusM,$lat,$lon)["boundary"="administrative"]["admin_level"="10"];
              relation(around:$radiusM,$lat,$lon)["boundary"="administrative"]["admin_level"="10"];
            );
            out center tags;
        """.trimIndent()
        return overpassPost(q)
    }

    private fun pickNearestBarangayish(elements: JSONArray, lat: Double, lon: Double): String? {
        var bestName: String? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = bestName(tags) ?: continue

            val looksBrgy = tags.keys().asSequence().any { k ->
                val v = tags.optString(k).lowercase()
                k.lowercase().contains("barangay") || v.contains("barangay") || v.startsWith("brgy")
            } || tags.optString("admin_type:PH").equals("barangay", true) ||
                    (tags.optString("boundary").equals("administrative", true) && tags.optString("admin_level") == "10")

            if (!looksBrgy) continue

            val (eLat, eLon) = elementLatLon(el) ?: continue
            val d = haversine(lat, lon, eLat, eLon)
            if (d < bestD) { bestD = d; bestName = name }
        }
        return bestName
    }

    // ---------- Nominatim fallbacks ----------
    private fun queryBarangayFromNominatim(lat: Double, lon: Double): String? {
        val u = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1&zoom=18")
        var conn: HttpURLConnection? = null
        return try {
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: anfredalbit20@example.com)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-PH,en;q=0.8")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val addr = JSONObject(body).optJSONObject("address") ?: return null

            val candidates = listOf("barangay", "city_district", "suburb", "village", "neighbourhood")
                .mapNotNull { k -> addr.optString(k).takeIf { it.isNotBlank() } }

            candidates.firstOrNull { cand ->
                Regex("(?i)\\b(brgy\\.?|barangay)\\b").containsMatchIn(cand) ||
                        !Regex("(?i)\\b(purok|zone|sitio|phase|block)\\b").containsMatchIn(cand)
            }
        } catch (e: Exception) { null }
        finally { conn?.disconnect() }
    }

    private fun queryCityFromNominatim(lat: Double, lon: Double): String? {
        val u = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1&zoom=16")
        var conn: HttpURLConnection? = null
        return try {
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: anfredalbit20@example.com)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-PH,en;q=0.8")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val addr = JSONObject(body).optJSONObject("address") ?: return null
            listOf("city","town","municipality").firstNotNullOfOrNull { k ->
                addr.optString(k).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
        finally { conn?.disconnect() }
    }

    private fun queryPurokFromNominatim(lat: Double, lon: Double): String? {
        val u = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1&zoom=18")
        var conn: HttpURLConnection? = null
        return try {
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: anfredalbit20@example.com)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-PH,en;q=0.8")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val addr = JSONObject(body).optJSONObject("address") ?: return null

            val candidates = listOf("neighbourhood","suburb","quarter","village","hamlet","city_district")
                .mapNotNull { k -> addr.optString(k).takeIf { it.isNotBlank() } }

            candidates.firstOrNull { looksLikePurok(it) }
        } catch (e: Exception) { null }
        finally { conn?.disconnect() }
    }

    private fun queryOverpassNearbyPurokish(lat: Double, lon: Double, radiusM: Int): JSONArray {
        val q = """
            [out:json][timeout:25];
            (
              node(around:$radiusM,$lat,$lon)["place"~"^(neighbourhood|suburb)$"]["name"~"(?i)\\b(purok|zone|sitio)\\b"];
              way (around:$radiusM,$lat,$lon)["place"~"^(neighbourhood|suburb)$"]["name"~"(?i)\\b(purok|zone|sitio)\\b"];
              relation(around:$radiusM,$lat,$lon)["place"~"^(neighbourhood|suburb)$"]["name"~"(?i)\\b(purok|zone|sitio)\\b"];
            );
            out center tags;
        """.trimIndent()
        return overpassPost(q)
    }

    private fun pickNearestPurokish(elements: JSONArray, lat: Double, lon: Double): String? {
        var bestName: String? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = bestName(tags) ?: continue
            if (!looksLikePurok(name)) continue
            val (eLat, eLon) = elementLatLon(el) ?: continue
            val d = haversine(lat, lon, eLat, eLon)
            if (d < bestD) { bestD = d; bestName = name }
        }
        return bestName
    }

    // ---------- shared helpers ----------
    private fun overpassPost(query: String): JSONArray {
        val mirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        for (ep in mirrors) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: anfredalbit20@example.com)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connectTimeout = 15000
                    readTimeout = 20000
                }
                val body = "data=" + URLEncoder.encode(query, "UTF-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val txt  = BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                )).use { it.readText() }
                if (code in 200..299) {
                    return JSONObject(txt).optJSONArray("elements") ?: JSONArray()
                } else {
                    Log.e("Overpass", "HTTP $code from $ep: $txt")
                }
            } catch (e: Exception) {
                Log.e("Overpass", "Error with $ep: ${e.message}")
            } finally { conn?.disconnect() }
        }
        return JSONArray()
    }

    private fun bestName(tags: JSONObject): String? =
        tags.optString("name:en").takeIf { it.isNotBlank() }
            ?: tags.optString("official_name").takeIf { it.isNotBlank() }
            ?: tags.optString("name").takeIf { it.isNotBlank() }

    private fun looksLikePurok(name: String): Boolean =
        Regex("""(?i)\b(purok|zone|sitio)\b""").containsMatchIn(name.trim())

    private fun elementLatLon(el: JSONObject): Pair<Double, Double>? {
        val lat = when {
            el.has("lat") -> el.optDouble("lat", Double.NaN)
            el.has("center") -> el.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
            else -> Double.NaN
        }
        val lon = when {
            el.has("lon") -> el.optDouble("lon", Double.NaN)
            el.has("center") -> el.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
            else -> Double.NaN
        }
        return if (lat.isFinite() && lon.isFinite()) lat to lon else null
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon/2)*Math.sin(dLon/2)
        return 2*R*Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    }

    private fun normalizeBarangay(raw: String): String =
        if (Regex("""(?i)^(brgy\.?|barangay)\b""").containsMatchIn(raw.trim())) raw.trim()
        else "Barangay ${raw.trim()}"

    private fun normalizeCity(raw: String): String =
        if (Regex("""(?i)\bcity\b""").containsMatchIn(raw.trim())) raw.trim()
        else "${raw.trim()} City"

    private fun normalizePurok(raw: String): String {
        val t = raw.trim()
        return if (Regex("""(?i)\b(purok|zone|sitio)\b""").containsMatchIn(t)) t else "Purok $t"
    }
}
