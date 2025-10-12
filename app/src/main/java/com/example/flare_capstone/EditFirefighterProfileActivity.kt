package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivityEditProfileBinding
import com.google.firebase.database.*
import java.io.ByteArrayOutputStream

class EditFirefighterProfileActivity : AppCompatActivity() {

    companion object {
        // e.g. "TagumCityCentralFireStation/FireFighter/AllFireFighterAccount/MabiniFireFighterAccount"
        const val EXTRA_DB_PATH = "extra_db_path"

        private const val CAMERA_REQUEST_CODE = 201
        private const val CAMERA_PERMISSION_REQUEST_CODE = 202
        private const val GALLERY_PERMISSION_REQUEST_CODE = 203
        private const val GALLERY_REQUEST_CODE = 204
    }

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var dbRef: DatabaseReference
    private lateinit var connectivityManager: ConnectivityManager

    private var base64ProfileImage: String? = null
    private var hasProfileImage = false
    private var removeProfileImageRequested = false

    private var loadingDialog: AlertDialog? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { hideLoadingDialog() } }
        override fun onLost(network: Network) { runOnUiThread { showLoadingDialog("No internet connection") } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_DB_PATH)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "Missing profile path.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        dbRef = FirebaseDatabase.getInstance().getReference(path)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Email is read-only
        binding.email.isFocusable = false
        binding.email.isFocusableInTouchMode = false
        binding.email.isClickable = false

        // Hide current password fields (unused here)
        binding.currentPassword.visibility = View.GONE
        binding.currentPasswordText.visibility = View.GONE

        // Image picker
        binding.profileIcon.isClickable = true
        binding.profileIcon.setOnClickListener { showImageSourceSheet() }

        // Load single firefighter object: { name, email, contact, profile(base64) }
        dbRef.get()
            .addOnSuccessListener { snap ->
                hideLoadingDialog()
                if (!snap.exists()) {
                    Toast.makeText(this, "Profile not found.", Toast.LENGTH_SHORT).show()
                    finish(); return@addOnSuccessListener
                }

                val name = snap.child("name").getValue(String::class.java).orEmpty()
                val email = snap.child("email").getValue(String::class.java).orEmpty()
                val contact = snap.child("contact").getValue(String::class.java).orEmpty()
                val profileBase64 = snap.child("profile").getValue(String::class.java)

                binding.name.setText(name)
                binding.email.setText(email)
                binding.contact.setText(contact)

                val bmp = convertBase64ToBitmap(profileBase64)
                if (bmp != null) {
                    binding.profileIcon.setImageBitmap(bmp)
                    hasProfileImage = true
                    base64ProfileImage = profileBase64
                } else {
                    hasProfileImage = false
                    base64ProfileImage = null
                }

                binding.saveButton.setOnClickListener {
                    val newName = binding.name.text.toString().trim()
                    var newContact = binding.contact.text.toString().trim()

                    if (newName.isEmpty()) {
                        binding.name.error = "Required"; return@setOnClickListener
                    }

                    // Normalize 639xxxxxx… → 09xxxxxx…
                    if (newContact.startsWith("639")) {
                        newContact = newContact.replaceFirst("639", "09")
                        binding.contact.setText(newContact)
                    }

                    if (newContact.isNotEmpty() && !newContact.matches(Regex("^09\\d{9}$"))) {
                        binding.contact.error = "Invalid number. Must start with 09 and have 11 digits."
                        return@setOnClickListener
                    }

                    val updates = mutableMapOf<String, Any>(
                        "name" to newName,
                        "contact" to newContact
                    )
                    if (base64ProfileImage != null && !removeProfileImageRequested) {
                        updates["profile"] = base64ProfileImage!!
                    }

                    showLoadingDialog("Saving…")
                    dbRef.updateChildren(updates)
                        .addOnSuccessListener {
                            if (removeProfileImageRequested) {
                                dbRef.child("profile").removeValue()
                                    .addOnCompleteListener {
                                        hideLoadingDialog()
                                        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
                                        hasProfileImage = false
                                        removeProfileImageRequested = false
                                        base64ProfileImage = null
                                    }
                            } else {
                                hideLoadingDialog()
                                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
                                hasProfileImage = base64ProfileImage != null
                                removeProfileImageRequested = false
                            }
                        }
                        .addOnFailureListener { e ->
                            hideLoadingDialog()
                            Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener {
                hideLoadingDialog()
                Toast.makeText(this, "Failed to load profile: ${it.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    /* ---------------- Connectivity ---------------- */
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

    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* ---------------- Image picker ---------------- */
    private fun showImageSourceSheet() {
        val options = if (hasProfileImage)
            arrayOf("Take photo", "Choose from gallery", "Remove photo")
        else
            arrayOf("Take photo", "Choose from gallery")

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Take photo" -> ensureCameraAndOpen()
                    "Choose from gallery" -> ensureGalleryAndOpen()
                    "Remove photo" -> {
                        binding.profileIcon.setImageResource(R.drawable.ic_camera_profile)
                        base64ProfileImage = null
                        hasProfileImage = false
                        removeProfileImageRequested = true
                        Toast.makeText(this, "Photo removed (pending save)", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun ensureCameraAndOpen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else openCamera()
    }

    private fun ensureGalleryAndOpen() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), GALLERY_PERMISSION_REQUEST_CODE)
        } else openGallery()
    }

    private fun openCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(cameraIntent, CAMERA_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, GALLERY_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) return
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> openCamera()
            GALLERY_PERMISSION_REQUEST_CODE -> openGallery()
        }
    }

    @Deprecated("startActivityForResult used; migrate later")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            CAMERA_REQUEST_CODE -> {
                val imageBitmap = data?.extras?.get("data") as? Bitmap ?: return
                binding.profileIcon.setImageBitmap(imageBitmap)
                base64ProfileImage = convertBitmapToBase64(imageBitmap)
                hasProfileImage = true
                removeProfileImageRequested = false
                Toast.makeText(this, "Profile picture updated (pending save)", Toast.LENGTH_SHORT).show()
            }
            GALLERY_REQUEST_CODE -> {
                val uri = data?.data ?: return
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                binding.profileIcon.setImageBitmap(bitmap)
                base64ProfileImage = convertBitmapToBase64(bitmap)
                hasProfileImage = true
                removeProfileImageRequested = false
                Toast.makeText(this, "Profile picture updated (pending save)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ---------------- Base64 helpers ---------------- */
    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val bytes = out.toByteArray()
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    private fun convertBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) return null
        return try {
            val decoded = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (_: Exception) { null }
    }
}
