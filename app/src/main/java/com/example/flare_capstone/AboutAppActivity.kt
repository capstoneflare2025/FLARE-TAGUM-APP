package com.example.flare_capstone

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityAboutAppBinding

class AboutAppActivity : AppCompatActivity() {

    /* ---------------- View Binding ---------------- */
    private lateinit var binding: ActivityAboutAppBinding

    /* ---------------- Network ---------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    // Network callback to monitor internet availability
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { hideLoadingDialog() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { showLoadingDialog("No internet connection") }
        }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAboutAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init connectivity manager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Initial connection check
        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        }

        // Register network listener
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Back button
        binding.back.setOnClickListener { onBackPressed() }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    /* =========================================================
     * Network Helpers
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /* =========================================================
     * Loading Dialog Helpers
     * ========================================================= */
    private fun showLoadingDialog(message: String = "Please wait, checking internet...") {
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
}
