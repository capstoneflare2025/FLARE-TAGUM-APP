package com.example.flare_capstone

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityChangePasswordBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.EmailAuthProvider

class ChangePasswordActivity : AppCompatActivity() {

    /* ---------------- View / Auth ---------------- */
    private lateinit var binding: ActivityChangePasswordBinding
    private lateinit var auth: FirebaseAuth

    /* ---------------- Connectivity ---------------- */
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

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this) // ensure theme first
        super.onCreate(savedInstanceState)

        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Initial network state
        if (!isConnected()) showLoadingDialog("No internet connection")

        // Register network listener
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Back button
        binding.back.setOnClickListener { onBackPressed() }

        // Save action
        binding.saveButton.setOnClickListener { onChangePasswordClicked() }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadingDialog?.dismiss()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    /* =========================================================
     * UI Actions
     * ========================================================= */
    private fun onChangePasswordClicked() {
        val currentPassword = binding.currentPassword.text.toString().trim()
        val newPassword = binding.newPassword.text.toString().trim()
        val confirmPassword = binding.confirmPassword.text.toString().trim()

        // Client-side validation
        if (currentPassword.isEmpty()) {
            binding.currentPassword.error = "Please enter your current password"
            return
        }
        if (newPassword.isEmpty()) {
            binding.newPassword.error = "Please enter a new password"
            return
        }
        if (confirmPassword.isEmpty()) {
            binding.confirmPassword.error = "Please confirm your new password"
            return
        }
        if (newPassword != confirmPassword) {
            binding.confirmPassword.error = "Passwords do not match"
            return
        }
        if (!isConnected()) {
            showToast("No internet connection")
            return
        }

        val user = auth.currentUser
        if (user == null) {
            showToast("You need to be signed in.")
            return
        }
        val currentEmail = user.email
        if (currentEmail.isNullOrEmpty()) {
            showToast("Current email is missing.")
            return
        }

        // Lock UI while processing
        setSaving(true)

        // Reauthenticate with current password then update
        val credential = EmailAuthProvider.getCredential(currentEmail, currentPassword)
        user.reauthenticate(credential)
            .addOnSuccessListener {
                user.updatePassword(newPassword)
                    .addOnSuccessListener {
                        showToast("Password changed successfully")
                        clearPasswordFields()
                    }
                    .addOnFailureListener { e ->
                        showToast("Failed to change password: ${e.message}")
                    }
                    .addOnCompleteListener { setSaving(false) }
            }
            .addOnFailureListener { e ->
                setSaving(false)
                if (e is FirebaseAuthRecentLoginRequiredException) {
                    showToast("Reauthentication required. Please log in again.")
                } else {
                    showToast("Authentication failed: ${e.message}")
                }
            }
    }

    /* =========================================================
     * Helpers: UI / State
     * ========================================================= */
    private fun setSaving(saving: Boolean) {
        binding.saveButton.isEnabled = !saving
        if (saving) {
            showLoadingDialog("Updating password…")
        } else {
            hideLoadingDialog()
        }
    }

    private fun clearPasswordFields() {
        binding.currentPassword.text?.clear()
        binding.newPassword.text?.clear()
        binding.confirmPassword.text?.clear()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /* =========================================================
     * Helpers: Connectivity
     * ========================================================= */
    private fun isConnected(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /* =========================================================
     * Helpers: Loading Dialog
     * ========================================================= */
    private fun showLoadingDialog(message: String = "Please wait…") {
        if (loadingDialog == null) {
            val view = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            loadingDialog = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }
}
