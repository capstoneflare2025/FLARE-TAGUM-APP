package com.example.flare_capstone

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityUserGuideBinding

class UserGuideActivity: AppCompatActivity() {

    private lateinit var binding: ActivityUserGuideBinding
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { hideLoadingDialog() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { showLoadingDialog("No internet connection") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityUserGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        } else {
            hideLoadingDialog()
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        binding.back.setOnClickListener { onBackPressed() }

        // 🔥 Show fire tutorial dialog
        binding.reportFireCard.setOnClickListener {
            VideoDialogFragment
                .newInstance("Tutorial Report Fire", R.raw.tutorial_report_fire)
                .show(supportFragmentManager, "video_fire")
        }

        // 🚨 Show other emergency tutorial dialog
        binding.reportOtherCard.setOnClickListener {
            VideoDialogFragment
                .newInstance("Tutorial Report Other", R.raw.tutorial_report_other)
                .show(supportFragmentManager, "video_other")
        }
    }

    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val inflater = layoutInflater
            val dialogView = inflater.inflate(R.layout.custom_loading_dialog, null)
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

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
