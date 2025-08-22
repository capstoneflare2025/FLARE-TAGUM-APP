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
import kotlin.jvm.java
import kotlin.text.isEmpty
import kotlin.text.trim


class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangePasswordBinding
    private lateinit var auth: FirebaseAuth

    private lateinit var connectivityManager: ConnectivityManager

    private var loadingDialog: AlertDialog? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this) // Set the theme first!
        super.onCreate(savedInstanceState)
        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Check initial internet connection status
        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        } else {
            hideLoadingDialog()
        }

        // Register network callback to listen for connection changes
        connectivityManager.registerDefaultNetworkCallback(networkCallback)


        // Handle save button click to change password
        binding.saveButton.setOnClickListener {
            val currentPassword = binding.currentPassword.text.toString().trim()
            val newPassword = binding.newPassword.text.toString().trim()
            val confirmPassword = binding.confirmPassword.text.toString().trim()

            // Validate passwords
            if (currentPassword.isEmpty()) {
                binding.currentPassword.error = "Please enter your current password"
                return@setOnClickListener
            }

            if (newPassword.isEmpty()) {
                binding.newPassword.error = "Please enter a new password"
                return@setOnClickListener
            }

            if (confirmPassword.isEmpty()) {
                binding.confirmPassword.error = "Please confirm your new password"
                return@setOnClickListener
            }

            if (newPassword != confirmPassword) {
                binding.confirmPassword.error = "Passwords do not match"
                return@setOnClickListener
            }

            val user = auth.currentUser
            if (user != null) {
                // Get current email from Firebase auth
                val currentEmail = user.email

                if (currentEmail != null) {
                    // Create credential using current email and password
                    val credential = EmailAuthProvider.getCredential(currentEmail, currentPassword)

                    // Reauthenticate the user using the credentials
                    user.reauthenticate(credential)
                        .addOnSuccessListener {
                            // Once reauthenticated, change the password
                            user.updatePassword(newPassword)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Password changed successfully", Toast.LENGTH_SHORT).show()
                                }
                                .addOnFailureListener { exception ->
                                    Toast.makeText(this, "Failed to change password: ${exception.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                        .addOnFailureListener { exception ->
                            if (exception is FirebaseAuthRecentLoginRequiredException) {
                                Toast.makeText(this, "Reauthentication required", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Authentication failed: ${exception.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    Toast.makeText(this, "Current email is missing.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Back button action to navigate back
        binding.back.setOnClickListener {
            onBackPressed()
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
            val dialogView = inflater.inflate(com.example.flare_capstone.R.layout.custom_loading_dialog, null)
            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(com.example.flare_capstone.R.id.loading_message)?.text = message
    }


    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
