package com.example.flare_capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.flare_capstone.databinding.ActivityDashboardBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class DashboardActivity : AppCompatActivity() {

    /* ---------------- View / State ---------------- */
    internal lateinit var binding: ActivityDashboardBinding
    private lateinit var database: DatabaseReference
    private lateinit var sharedPreferences: SharedPreferences
    private var currentUserName: String? = null
    private var user: User? = null
    private var unreadMessageCount: Int = 0

    /* ---------------- Location / Connectivity ---------------- */
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    /* ---------------- Firebase / Queries ---------------- */
    // ✅ Single station
    private val stationNode = "TagumCityCentralFireStation"
    private val responseListeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val unreadCounterListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    /* ---------------- Connectivity state gates ---------------- */
    private var isNetworkValidated = false
    private var isNetworkSlow = true
    private var isInitialFirebaseReady = false

    /* ---------------- Network Callback ---------------- */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                isNetworkValidated = false
                isNetworkSlow = true
                showLoadingDialog("Connecting… (waiting for internet)")
            }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread {
                isNetworkValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                isNetworkSlow = isSlow(caps)
                maybeHideLoading()
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                isNetworkValidated = false
                isNetworkSlow = true
                showLoadingDialog("No internet connection")
            }
        }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        } else {
            showLoadingDialog("Connecting…")
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        showLoadingDialog()

        sharedPreferences = getSharedPreferences("shown_notifications", MODE_PRIVATE)
        unreadMessageCount = sharedPreferences.getInt("unread_message_count", 0)
        updateInboxBadge(unreadMessageCount)

        database = FirebaseDatabase.getInstance().reference

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)

        createNotificationChannel()

        fetchCurrentUserName { name ->
            if (name != null) {
                currentUserName = name
                updateUnreadMessageCount()
                startRealtimeUnreadCounter()
                listenForResponseMessages()
                listenForStatusChanges()
            } else {
                startRealtimeUnreadCounter()
                Log.e("UserCheck", "Failed to get current user name. Notifications will not be triggered.")
            }
            isInitialFirebaseReady = true
            runOnUiThread { maybeHideLoading() }
        }

        binding.root.postDelayed({
            if (!isInitialFirebaseReady) showLoadingDialog("Still loading data… (slow internet)")
        }, 10_000)
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized && user != null) {
            updateUnreadMessageCount()
        }
        maybeHideLoading()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        responseListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
        responseListeners.clear()
        stopRealtimeUnreadCounter()
    }

    /* =========================================================
     * Connectivity / Loading helpers
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isSlow(caps: NetworkCapabilities): Boolean {
        val down = caps.linkDownstreamBandwidthKbps
        val up = caps.linkUpstreamBandwidthKbps
        if (down <= 0 || up <= 0) return true
        return down < 1500 || up < 512
    }

    private fun maybeHideLoading() {
        if (isNetworkValidated && !isNetworkSlow && isInitialFirebaseReady) {
            hideLoadingDialog()
        } else {
            val msg = when {
                !isNetworkValidated -> "Connecting… (waiting for internet)"
                isNetworkSlow -> "Slow internet connection"
                !isInitialFirebaseReady -> "Loading data…"
                else -> "Please wait"
            }
            showLoadingDialog(msg)
        }
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(R.layout.custom_loading_dialog, null)

            // 🔒 Hide the Close button for DashboardActivity
            dialogView.findViewById<TextView>(R.id.closeButton)?.apply {
                visibility = View.GONE
                isClickable = false
            }

            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }

        if (loadingDialog?.isShowing != true) loadingDialog?.show()

        // Ensure it stays hidden even on reused dialog instances
        loadingDialog?.findViewById<TextView>(R.id.closeButton)?.apply {
            visibility = View.GONE
            isClickable = false
        }

        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }


    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    /* =========================================================
     * Notifications
     * ========================================================= */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "default_channel",
                "General Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun triggerNotification(
        fireStationName: String?,
        message: String?,
        messageId: String,
        incidentId: String?,
        reporterName: String?,
        title: String,
        stationNodeParam: String // kept for intent extras; always TagumCityCentralFireStation
    ) {
        val notificationId = (stationNodeParam + "::" + messageId).hashCode()
        val reportNode = defaultReportNode() // points to AllReport/FireReport

        val resultIntent = Intent(this, FireReportResponseActivity::class.java).apply {
            putExtra("INCIDENT_ID", incidentId)
            putExtra("FIRE_STATION_NAME", fireStationName)
            putExtra("NAME", reporterName)
            putExtra("fromNotification", true)
            putExtra("STATION_NODE", stationNode)             // single node
            putExtra("REPORT_NODE", reportNode)               // AllReport/FireReport by default
        }

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@DashboardActivity, DashboardActivity::class.java))
            addNextIntent(resultIntent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, "default_channel")
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun triggerStatusChangeNotification(
        reportId: String,
        stationNodeParam: String,
        reporterName: String,
        status: String
    ) {
        val notificationId = (stationNodeParam + "::" + reportId).hashCode()

        val resultIntent = Intent(this, FireReportResponseActivity::class.java).apply {
            putExtra("REPORT_ID", reportId)
            putExtra("STATUS", status)
            putExtra("REPORTER_NAME", reporterName)
            putExtra("STATION_NODE", stationNode)
            putExtra("REPORT_NODE", defaultReportNode())
        }

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@DashboardActivity, DashboardActivity::class.java))
            addNextIntent(resultIntent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, "default_channel")
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("Status Update: $status")
            .setContentText("The status of your report has changed to $status.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    /* =========================================================
     * Firebase: Users / Messages
     * ========================================================= */
    private fun fetchCurrentUserName(callback: (String?) -> Unit) {
        val currentEmail = FirebaseAuth.getInstance().currentUser?.email ?: return callback(null)
        database.child("Users").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (userSnapshot in snapshot.children) {
                    val email = userSnapshot.child("email").getValue(String::class.java)?.trim()
                    val name = userSnapshot.child("name").getValue(String::class.java)?.trim()
                    if (email.equals(currentEmail.trim(), ignoreCase = true)) {
                        user = userSnapshot.getValue(User::class.java)
                        callback(name)
                        return
                    }
                }
                callback(null)
            }
            override fun onCancelled(error: DatabaseError) = callback(null)
        })
    }

    private fun updateUnreadMessageCount() {
        val myContact = user?.contact?.trim().orEmpty()
        val myName = currentUserName?.trim().orEmpty()

        if (myContact.isEmpty() && myName.isEmpty()) {
            unreadMessageCount = 0
            sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
            runOnUiThread { updateInboxBadge(unreadMessageCount) }
            return
        }

        // ✅ Read under TagumCityCentralFireStation/AllReport/ResponseMessage
        val baseRef = database.child(stationNode).child("AllReport").child("ResponseMessage")
        val query: Query = if (myContact.isNotEmpty()) {
            baseRef.orderByChild("contact").equalTo(myContact)
        } else {
            baseRef.orderByChild("reporterName").equalTo(myName)
        }

        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var total = 0
                snapshot.children.forEach { msg ->
                    val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                    if (!isRead) total++
                }
                unreadMessageCount = total
                sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
            override fun onCancelled(error: DatabaseError) {
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
        })
    }

    private fun listenForResponseMessages() {
        val prefs = getSharedPreferences("user_preferences", MODE_PRIVATE)
        if (!prefs.getBoolean("notifications_enabled", true)) return

        val myName = currentUserName?.trim().orEmpty()
        val myContact = user?.contact?.trim().orEmpty()
        if (myName.isEmpty() && myContact.isEmpty()) return

        // ✅ Listen under AllReport/ResponseMessage
        val baseRef = database.child(stationNode).child("AllReport").child("ResponseMessage")
        val query: Query = if (myContact.isNotEmpty()) {
            baseRef.orderByChild("contact").equalTo(myContact)
        } else {
            baseRef.orderByChild("reporterName").equalTo(myName)
        }

        val listener = object : ChildEventListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val messageId = snapshot.key ?: return
                val uniqueKey = "$stationNode::$messageId"

                val isRead = snapshot.child("isRead").getValue(Boolean::class.java) ?: false
                if (isRead || isNotificationShown(uniqueKey)) return

                val fireStationName = snapshot.child("fireStationName").getValue(String::class.java)
                val responseMessage = snapshot.child("responseMessage").getValue(String::class.java)
                val incidentId = snapshot.child("incidentId").getValue(String::class.java)
                val reporterName = snapshot.child("reporterName").getValue(String::class.java)

                // Only notify for my messages
                if (reporterName == myName) {
                    unreadMessageCount++
                    sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                    runOnUiThread { updateInboxBadge(unreadMessageCount) }

                    triggerNotification(
                        fireStationName = fireStationName,
                        message = responseMessage,
                        messageId = messageId,
                        incidentId = incidentId,
                        reporterName = reporterName,
                        title = "New Response from $fireStationName",
                        stationNodeParam = stationNode
                    )

                    markNotificationAsShown(uniqueKey)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                updateUnreadMessageCount()
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        query.addChildEventListener(listener)
        responseListeners += query to listener
    }

    private fun isNotificationShown(key: String): Boolean =
        sharedPreferences.getBoolean(key, false)

    private fun markNotificationAsShown(key: String) {
        sharedPreferences.edit().putBoolean(key, true).apply()
    }

    /* =========================================================
     * Firebase: Status Changes (watch FireReport under AllReport)
     * ========================================================= */
    private fun listenForStatusChanges() {
        val myName = currentUserName?.trim().orEmpty()
        if (myName.isEmpty()) return

        val baseRef = database.child(stationNode).child("AllReport").child("FireReport")

        baseRef.addChildEventListener(object : ChildEventListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val reportId = snapshot.key ?: return
                val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                val status = snapshot.child("status").getValue(String::class.java)

                if (reporterName == myName && status == "Ongoing") {
                    triggerStatusChangeNotification(reportId, stationNode, reporterName, status ?: "Ongoing")
                }
            }

            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val reportId = snapshot.key ?: return
                val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                val status = snapshot.child("status").getValue(String::class.java)

                if (reporterName == myName && status == "Ongoing") {
                    triggerStatusChangeNotification(reportId, stationNode, reporterName, status ?: "Ongoing")
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /* =========================================================
     * Utilities
     * ========================================================= */
    private fun defaultReportNode(): String {
        // When opening the conversation view from a notification,
        // default to FireReport under AllReport (your reader can switch as needed).
        return "AllReport/FireReport"
    }

    private fun updateInboxBadge(count: Int) {
        val badge = binding.bottomNavigation.getOrCreateBadge(R.id.inboxFragment)
        badge.isVisible = count > 0
        badge.number = count
        badge.maxCharacterCount = 3
    }

    private fun startRealtimeUnreadCounter() {
        if (unreadCounterListeners.isNotEmpty()) return

        val myContact = user?.contact?.trim().orEmpty()
        val myName = currentUserName?.trim().orEmpty()
        if (myContact.isEmpty() && myName.isEmpty()) {
            updateInboxBadge(0)
            return
        }

        // ✅ Watch AllReport/ResponseMessage for unread
        val baseRef = database.child(stationNode).child("AllReport").child("ResponseMessage")
        val query: Query = if (myContact.isNotEmpty()) {
            baseRef.orderByChild("contact").equalTo(myContact)
        } else {
            baseRef.orderByChild("reporterName").equalTo(myName)
        }

        val v = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var count = 0
                snapshot.children.forEach { msg ->
                    val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                    if (!isRead) count++
                }
                unreadMessageCount = count
                sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
            override fun onCancelled(error: DatabaseError) {
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
        }

        query.addValueEventListener(v)
        unreadCounterListeners += query to v
    }

    private fun stopRealtimeUnreadCounter() {
        unreadCounterListeners.forEach { (q, v) ->
            runCatching { q.removeEventListener(v) }
        }
        unreadCounterListeners.clear()
    }
}
