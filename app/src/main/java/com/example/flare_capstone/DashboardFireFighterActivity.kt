package com.example.flare_capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.flare_capstone.databinding.ActivityDashboardFireFighterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class DashboardFireFighterActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "FF-Notif"
        const val NOTIF_REQ_CODE = 9001

        // One channel per type:
        const val CH_FIRE  = "ff_fire"
        const val CH_OTHER = "ff_other"
        const val CH_SMS   = "ff_sms"

        // Legacy single-channel id (we’ll delete it)
        const val OLD_CHANNEL_ID = "ff_incidents"
    }

    private lateinit var binding: ActivityDashboardFireFighterBinding
    private lateinit var database: FirebaseDatabase
    private lateinit var prefs: SharedPreferences

    private var stationPrefix: String? = null
    private var baseNode: String? = null

    // Dedupe + lifecycle
    private val shownKeys = mutableSetOf<String>()               // "$path::$id"
    private val liveListeners = mutableListOf<Pair<Query, ChildEventListener>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDashboardFireFighterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_firefighter) as NavHostFragment
        binding.bottomNavigationFirefighter.setupWithNavController(navHostFragment.navController)

        database = FirebaseDatabase.getInstance()
        prefs = getSharedPreferences("ff_notifs", MODE_PRIVATE)

        val email = FirebaseAuth.getInstance().currentUser?.email?.lowercase()
        stationPrefix = when (email) {
            "mabiniff001@gmail.com"     -> "Mabini"
            "lafilipinaff001@gmail.com" -> "LaFilipina"
            "canocotanff001@gmail.com"  -> "Canocotan"
            else -> null
        }
        baseNode = stationPrefix?.let { "${it}FireStation" }
        Log.d(TAG, "email=$email stationPrefix=$stationPrefix baseNode=$baseNode")

        createNotificationChannels()      // Fire/Other custom, SMS default sound
        maybeRequestPostNotifPermission()

        if (baseNode != null && stationPrefix != null) {
            val base = baseNode!!
            val pfx  = stationPrefix!!
            listenOne("$base/${pfx}FireReport",     "New FIRE report")
            listenOne("$base/${pfx}OtherEmergency", "New OTHER emergency")
            listenOne("$base/${pfx}SmsReport",      "New SMS emergency")
        } else {
            Log.w(TAG, "No station mapped; not attaching Firebase listeners.")
        }

        // Handle tap from a notification when app is cold-started
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    /* -------------------- Firebase → Notification (once-per-id) -------------------- */

    private fun listenOne(path: String, title: String) {
        val ref = database.getReference(path)
        Log.d(TAG, "listenOne attach path=$path")

        // 1) Initial snapshot: find latest ongoing timestamp (don’t notify for these).
        ref.limitToLast(200).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var baseTsMs = 0L
                var existingOngoing = 0
                for (c in snapshot.children) {
                    if (statusIsOngoing(c)) {
                        existingOngoing++
                        val ts = readTimestampMillis(c) ?: 0L
                        if (ts > baseTsMs) baseTsMs = ts
                    }
                }
                Log.d(TAG, "[$path] initial done; ongoing=$existingOngoing; baseTs=$baseTsMs")
                attachRealtime(ref, path, title, baseTsMs)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "[$path] initial cancelled: ${error.message} (fallback baseTs=0)")
                attachRealtime(ref, path, title, 0L)
            }
        })
    }

    private fun attachRealtime(ref: DatabaseReference, path: String, title: String, baseTsMs: Long) {
        val q = ref.limitToLast(200)
        val l = object : ChildEventListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                val id = snap.key ?: return
                val key = "$path::$id"
                val st = snap.child("status").getValue(String::class.java)
                val ts = readTimestampMillis(snap) ?: 0L
                Log.d(TAG, "ADD $key status=$st ts=$ts base=$baseTsMs shown=${alreadyShown(key)}")

                if (!statusIsOngoing(snap)) return
                if (ts <= baseTsMs) { Log.d(TAG, "→ skip ADD (old vs baseTs)"); return }
                if (alreadyShown(key)) { Log.d(TAG, "→ skip ADD (already shown)"); return }

                showIncidentNotification(title, snap, path)
                markShown(key)
            }

            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildChanged(snap: DataSnapshot, prev: String?) {
                val id = snap.key ?: return
                val key = "$path::$id"
                val st  = snap.child("status").getValue(String::class.java)
                Log.d(TAG, "CHG $key status=$st shown=${alreadyShown(key)}")

                if (!statusIsOngoing(snap)) return
                if (alreadyShown(key)) { Log.d(TAG, "→ skip CHG (already shown)"); return }

                showIncidentNotification(title, snap, path)
                markShown(key)
            }

            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "[$path] realtime cancelled: ${error.message}")
            }
        }

        q.addChildEventListener(l)
        liveListeners += (q to l)
        Log.d(TAG, "[$path] realtime attached (limitToLast=200)")
    }

    /* -------------------- Notification builder -------------------- */

    private fun channelForPath(path: String): String = when {
        path.endsWith("FireReport")      -> CH_FIRE
        path.endsWith("OtherEmergency")  -> CH_OTHER
        path.endsWith("SmsReport")       -> CH_SMS
        else                             -> CH_OTHER
    }

    private fun sourceForPath(path: String): String = when {
        path.endsWith("FireReport")      -> "FIRE"
        path.endsWith("OtherEmergency")  -> "OTHER"
        path.endsWith("SmsReport")       -> "SMS"
        else                             -> "OTHER"
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showIncidentNotification(title: String, snap: DataSnapshot, path: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled at OS level")
            return
        }

        val id = snap.key ?: return
        val exactLocation = snap.child("exactLocation").getValue(String::class.java)
            ?: snap.child("location").getValue(String::class.java)
            ?: "Unknown location"
        val message = "Station: $stationPrefix • $exactLocation"

        val channelId = channelForPath(path)
        val srcStr = sourceForPath(path)

        // Build an intent that carries WHICH incident to open
        val intent = Intent(this, DashboardFireFighterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification", true)
            putExtra("select_source", srcStr) // "FIRE" | "OTHER" | "SMS"
            putExtra("select_id", id)         // Firebase child key
        }

        // Use a unique requestCode per incident so extras are not reused
        val reqCode = ("$path::$id").hashCode()

        val pending = PendingIntent.getActivity(
            this, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)

        // Pre-O fallback for sounds:
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            when (channelId) {
                CH_FIRE  -> { builder.setSound(rawSoundUri(R.raw.fire_alert));  builder.setVibrate(longArrayOf(0, 600, 200, 600, 200, 600)) }
                CH_OTHER -> { builder.setSound(rawSoundUri(R.raw.other_alert)); builder.setVibrate(longArrayOf(0, 400, 150, 400)) }
                CH_SMS   -> { builder.setDefaults(NotificationCompat.DEFAULT_SOUND); builder.setVibrate(longArrayOf(0, 250, 120, 250, 120, 250)) }
            }
        }

        try {
            val notifId = ("$path::$id").hashCode()
            NotificationManagerCompat.from(this).notify(notifId, builder.build())
            Log.d(TAG, "NOTIFY id=$notifId ch=$channelId title=$title msg=$message")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted: ${e.message}")
        }
    }

    /* -------------------- Channels (Fire/Other custom; SMS default) -------------------- */

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // Delete legacy single channel if it exists
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL_ID) }

        // Always delete & recreate to apply new sounds
        recreateChannel(
            id = CH_FIRE,
            name = "Firefighter • FIRE",
            soundUri = rawSoundUri(R.raw.fire_alert),     // custom
            useDefault = false
        )
        recreateChannel(
            id = CH_OTHER,
            name = "Firefighter • OTHER",
            soundUri = rawSoundUri(R.raw.other_alert),    // custom
            useDefault = false
        )
        recreateChannel(
            id = CH_SMS,
            name = "Firefighter • SMS",
            soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), // system default
            useDefault = true
        )
    }

    private fun recreateChannel(id: String, name: String, soundUri: Uri?, useDefault: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // Remove existing channel to ensure sound updates take effect
        runCatching { nm.deleteNotificationChannel(id) }

        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        ch.enableVibration(true)

        val aa = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // On O+, sound is defined by the channel.
        if (useDefault) {
            val defaultUri = soundUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ch.setSound(defaultUri, aa)
        } else if (soundUri != null) {
            ch.setSound(soundUri, aa)
        }

        nm.createNotificationChannel(ch)
        Log.d(TAG, "channel created ($id) sound=${soundUri?.toString() ?: "default"}")
    }

    private fun rawSoundUri(@RawRes res: Int): Uri =
        Uri.parse("android.resource://$packageName/$res")

    /* -------------------- Status + timestamp helpers -------------------- */

    private fun statusIsOngoing(snap: DataSnapshot): Boolean {
        val raw = snap.child("status").getValue(String::class.java)?.trim() ?: return false
        val norm = raw.replace("-", "").lowercase()
        return norm == "ongoing"
    }

    private fun getLongRelaxed(node: DataSnapshot, key: String): Long? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    private fun getEpochFromDateTime(node: DataSnapshot): Long? {
        val dateStr = node.child("date").getValue(String::class.java)?.trim()
        val timeStr = node.child("time").getValue(String::class.java)?.trim()
        if (dateStr.isNullOrEmpty() || timeStr.isNullOrEmpty()) return null
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            fmt.timeZone = java.util.TimeZone.getDefault()
            fmt.parse("$dateStr $timeStr")?.time
        } catch (_: Exception) { null }
    }

    private fun readTimestampMillis(node: DataSnapshot): Long? {
        val raw = getLongRelaxed(node, "acceptedAt")
            ?: getLongRelaxed(node, "timeStamp")
            ?: getLongRelaxed(node, "timestamp")
            ?: getLongRelaxed(node, "time")
            ?: getEpochFromDateTime(node)
            ?: return null
        val ms = if (raw in 1..9_999_999_999L) raw * 1000 else raw
        return if (ms > 0) ms else null
    }

    /* -------------------- Dedupe -------------------- */

    private fun alreadyShown(key: String): Boolean =
        shownKeys.contains(key) || prefs.getBoolean(key, false)

    private fun markShown(key: String) {
        shownKeys.add(key)
        prefs.edit().putBoolean(key, true).apply()
        Log.d(TAG, "markShown $key")
    }

    // Optional helper to reset dedupe
    private fun debugClearDedupe() {
        Log.w(TAG, "debugClearDedupe called: clearing all stored keys")
        prefs.edit().clear().apply()
        shownKeys.clear()
    }

    /* -------------------- Permissions -------------------- */

    private fun maybeRequestPostNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "notif permission granted=$granted")
            if (!granted) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ_CODE)
        }
    }

    /* -------------------- Deliver selection to Home fragment -------------------- */

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("from_notification", false) == true) {
            val srcStr = intent.getStringExtra("select_source")
            val id     = intent.getStringExtra("select_id")
            if (!srcStr.isNullOrBlank() && !id.isNullOrBlank()) {
                Log.d(TAG, "deliverSelectionToHome src=$srcStr id=$id")
                deliverSelectionToHome(srcStr, id)
            }
        }
    }

    private fun deliverSelectionToHome(srcStr: String, id: String) {
        // Send to the fragment (it listens for "select_incident")
        supportFragmentManager.setFragmentResult(
            "select_incident",
            android.os.Bundle().apply {
                putString("source", srcStr)
                putString("id", id)
            }
        )
    }

    /* -------------------- Cleanup -------------------- */

    override fun onDestroy() {
        super.onDestroy()
        liveListeners.forEach { (q, l) -> q.removeEventListener(l) }
        liveListeners.clear()
    }
}
