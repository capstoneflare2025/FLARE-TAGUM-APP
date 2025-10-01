package com.example.flare_capstone

import android.os.AsyncTask
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class FetchBarangayAddressTask(
    private val activity: Any, // FireLevelActivity or OtherEmergencyActivity
    private val latitude: Double,
    private val longitude: Double
) : AsyncTask<Void, Void, String?>() {

    override fun doInBackground(vararg params: Void?): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?" +
                        "lat=$latitude&lon=$longitude&format=json&addressdetails=1&zoom=18"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // REQUIRED by Nominatim: identify your app and provide a way to contact you
                setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: anfredalbit20@example.com)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-PH,en;q=0.8")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = conn.responseCode
            val body = readStreamAsString(
                if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            )
            if (code !in 200..299) {
                Log.e("FetchBarangayAddress", "HTTP $code from Nominatim: $body")
                return null
            }

            val json = JSONObject(body)
            val addr = json.optJSONObject("address") ?: return null
            Log.d("FetchBarangayAddress", "Full address JSON: $addr")

            fun firstNonEmpty(vararg keys: String): String? {
                for (k in keys) {
                    val v = addr.optString(k).trim()
                    if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) return v
                }
                return null
            }

            // Try common PH fields
            val houseNo = firstNonEmpty("house_number")
            val road = firstNonEmpty("road", "residential", "path", "pedestrian")
            val neighRaw = firstNonEmpty(
                "neighbourhood", "suburb", "quarter", "hamlet",
                "locality", "residential", "block"
            )
            val barangay = firstNonEmpty(
                "barangay", "suburb", "neighbourhood", "village", "city_district", "district"
            )
            val city = firstNonEmpty("city", "town", "municipality") ?: firstNonEmpty("county")

            val purok = extractPurok(neighRaw)
            val neigh = purok ?: neighRaw

            val parts = mutableListOf<String>()
            val roadLine = listOfNotNull(houseNo, road).joinToString(" ").trim()
            if (roadLine.isNotEmpty()) parts += roadLine
            if (!neigh.isNullOrBlank()) parts += neigh
            if (!barangay.isNullOrBlank()) parts += barangay
            if (!city.isNullOrBlank()) parts += city

            parts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: city
        } catch (e: Exception) {
            Log.e("FetchBarangayAddress", "Exception: ${e.message}", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    override fun onPostExecute(result: String?) {
        super.onPostExecute(result)
        when (activity) {
            is FireLevelActivity -> activity.handleFetchedAddress(result)
            is OtherEmergencyActivity -> activity.handleFetchedAddress(result)
            else -> Log.e("FetchBarangayAddress", "Unsupported activity")
        }
    }

    private fun extractPurok(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val regex = Regex("""(?i)(Purok\s+\w+(?:\s+\w+)?)""")
        return regex.find(input)?.value
    }

    private fun readStreamAsString(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream)).use { br ->
            var line = br.readLine()
            while (line != null) {
                sb.append(line)
                line = br.readLine()
            }
        }
        return sb.toString()
    }
}
