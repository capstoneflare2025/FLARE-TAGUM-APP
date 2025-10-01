package com.example.flare_capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.flare_capstone.databinding.ActivityDashboardBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

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
    private val stationNodes = listOf("LaFilipinaFireStation", "CanocotanFireStation", "MabiniFireStation")
    private val responseListeners = mutableListOf<Pair<Query, ChildEventListener>>()

    /* ---------------- Network Callback ---------------- */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { hideLoadingDialog() } }
        override fun onLost(network: Network) { runOnUiThread { showLoadingDialog("No internet connection") } }
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
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
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

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)

        createNotificationChannel()

        fetchCurrentUserName { name ->
            if (name != null) {
                currentUserName = name
                updateUnreadMessageCount()
                listenForResponseMessages()
            } else {
                Log.e("UserCheck", "Failed to get current user name. Notifications will not be triggered.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized && user != null) {
            updateUnreadMessageCount()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        responseListeners.forEach { (query, listener) -> try { query.removeEventListener(listener) } catch (_: Exception) {} }
        responseListeners.clear()
    }

    /* =========================================================
     * Connectivity / Loading
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
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
            val channel = NotificationChannel("default_channel", "General Notifications", NotificationManager.IMPORTANCE_HIGH)
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
        stationNode: String
    ) {
        val notificationId = (stationNode + "::" + messageId).hashCode()
        val reportNode = reportNodeFor(stationNode)

        val resultIntent = Intent(this, FireReportResponseActivity::class.java).apply {
            putExtra("INCIDENT_ID", incidentId)
            putExtra("FIRE_STATION_NAME", fireStationName)
            putExtra("NAME", reporterName)
            putExtra("fromNotification", true)
            putExtra("STATION_NODE", stationNode)
            putExtra("REPORT_NODE", reportNode)
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
        stationNode: String,
        reporterName: String,
        status: String
    ) {
        val notificationId = (stationNode + "::" + reportId).hashCode()

        val resultIntent = Intent(this, FireReportResponseActivity::class.java).apply {
            putExtra("REPORT_ID", reportId)
            putExtra("STATUS", status)
            putExtra("REPORTER_NAME", reporterName)
            putExtra("STATION_NODE", stationNode)
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

        var total = 0
        var pending = stationNodes.size
        if (pending == 0) {
            unreadMessageCount = 0
            sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
            runOnUiThread { updateInboxBadge(unreadMessageCount) }
            return
        }

        stationNodes.forEach { stationNode ->
            val baseRef = database.child(stationNode).child("ResponseMessage")
            val query: Query = if (myContact.isNotEmpty()) {
                baseRef.orderByChild("contact").equalTo(myContact)
            } else {
                baseRef.orderByChild("reporterName").equalTo(myName)
            }

            query.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { msg ->
                        val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                        if (!isRead) total++
                    }
                    if (--pending == 0) {
                        unreadMessageCount = total
                        sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                        runOnUiThread { updateInboxBadge(unreadMessageCount) }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    if (--pending == 0) {
                        unreadMessageCount = total
                        sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                        runOnUiThread { updateInboxBadge(unreadMessageCount) }
                    }
                }
            })
        }
    }

    private fun listenForResponseMessages() {
        val prefs = getSharedPreferences("user_preferences", MODE_PRIVATE)
        if (!prefs.getBoolean("notifications_enabled", true)) return

        val myName = currentUserName?.trim().orEmpty()
        val myContact = user?.contact?.trim().orEmpty()
        if (myName.isEmpty() && myContact.isEmpty()) return

        stationNodes.forEach { stationNode ->
            val baseRef = database.child(stationNode).child("ResponseMessage")
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
                            stationNode = stationNode
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
    }

    private fun isNotificationShown(key: String): Boolean =
        sharedPreferences.getBoolean(key, false)

    private fun markNotificationAsShown(key: String) {
        sharedPreferences.edit().putBoolean(key, true).apply()
    }

    /* =========================================================
     * Firebase: Status Changes
     * ========================================================= */
    private fun listenForStatusChanges() {
        val myName = currentUserName?.trim().orEmpty()
        if (myName.isEmpty()) return

        stationNodes.forEach { stationNode ->
            val baseRef = database.child(stationNode).child("FireReports")

            baseRef.addChildEventListener(object : ChildEventListener {
                @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val reportId = snapshot.key ?: return
                    val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                    val status = snapshot.child("status").getValue(String::class.java)

                    if (reporterName == myName && status == "Ongoing") {
                        triggerStatusChangeNotification(reportId, stationNode, reporterName, status)
                    }
                }

                @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    val reportId = snapshot.key ?: return
                    val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                    val status = snapshot.child("status").getValue(String::class.java)

                    if (reporterName == myName && status == "Ongoing") {
                        triggerStatusChangeNotification(reportId, stationNode, reporterName, status)
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    /* =========================================================
     * Utilities
     * ========================================================= */
    private fun reportNodeFor(stationNode: String): String {
        return when (stationNode) {
            "LaFilipinaFireStation" -> "LaFilipinaFireReport"
            "CanocotanFireStation" -> "CanocotanFireReport"
            "MabiniFireStation" -> "MabiniFireReport"
            else -> "MabiniFireReport"
        }
    }

    private fun updateInboxBadge(count: Int) {
        val activity = this
        if (activity is DashboardActivity) {
            val badge = activity.binding.bottomNavigation.getOrCreateBadge(R.id.inboxFragment)
            badge.isVisible = count > 0
            badge.number = count
            badge.maxCharacterCount = 3
        }
    }
}
