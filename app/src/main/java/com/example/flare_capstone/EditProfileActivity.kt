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
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import java.io.ByteArrayOutputStream

class EditProfileActivity : AppCompatActivity() {

    /* ---------------- View / Firebase ---------------- */
    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth

    /* ---------------- Request Codes ---------------- */
    companion object {
        private const val CAMERA_REQUEST_CODE = 101
        private const val CAMERA_PERMISSION_REQUEST_CODE = 102
        private const val GALLERY_REQUEST_CODE = 104
        private const val GALLERY_PERMISSION_REQUEST_CODE = 103
    }

    /* ---------------- Profile Image State ---------------- */
    private var base64ProfileImage: String? = null
    private var hasProfileImage: Boolean = false
    private var removeProfileImageRequested: Boolean = false

    /* ---------------- Connectivity ---------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

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
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("Users")
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Initial connectivity state
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()

        // Navigation
        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Profile image click
        binding.profileIcon.isClickable = true
        binding.profileIcon.setOnClickListener { showImageSourceSheet() }

        binding.changePhotoIcon.setOnClickListener { showImageSourceSheet() }

        // Email is readonly
        binding.email.isFocusable = false
        binding.email.isFocusableInTouchMode = false
        binding.email.isClickable = false

        // Network callback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Load user data
        val userId = auth.currentUser?.uid
        if (userId != null) {
            database.child(userId).get().addOnSuccessListener { snapshot ->
                hideLoadingDialog()
                if (!snapshot.exists()) return@addOnSuccessListener

                val user = snapshot.getValue(User::class.java)
                if (user != null) {
                    binding.name.setText(user.name ?: "")
                    binding.email.setText(user.email ?: "")
                    binding.contact.setText(user.contact ?: "")

                    val profileBase64 = snapshot.child("profile").getValue(String::class.java)
                    val bmp = convertBase64ToBitmap(profileBase64)
                    if (bmp != null) {
                        binding.profileIcon.setImageBitmap(bmp)
                        hasProfileImage = true
                        base64ProfileImage = profileBase64
                    } else {
                        hasProfileImage = false
                        base64ProfileImage = null
                    }

                    val originalName = user.name ?: ""
                    val originalEmail = user.email ?: ""
                    val originalContact = user.contact ?: ""

                    binding.currentPassword.visibility = View.GONE
                    binding.currentPasswordText.visibility = View.GONE

                    binding.saveButton.setOnClickListener {
                        val newName = binding.name.text.toString().trim()
                        var newContact = binding.contact.text.toString().trim()

                        if (newName.isEmpty()) {
                            binding.name.error = "Required"
                            return@setOnClickListener
                        }

                        if (newContact.startsWith("639")) {
                            newContact = newContact.replaceFirst("639", "09")
                            binding.contact.setText(newContact)
                        }

                        val isNameChanged = newName != originalName
                        val isContactChanged = newContact != originalContact
                        val isPhotoAddedOrReplaced = !base64ProfileImage.isNullOrEmpty() && !removeProfileImageRequested
                        val isPhotoRemoved = removeProfileImageRequested && base64ProfileImage.isNullOrEmpty()
                        val anythingChanged = isNameChanged || isContactChanged || isPhotoAddedOrReplaced || isPhotoRemoved

                        if (!anythingChanged) {
                            Toast.makeText(this, "Nothing changed", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        if (isContactChanged && !newContact.matches(Regex("^09\\d{9}$"))) {
                            binding.contact.error = "Invalid number. Must start with 09 and have 11 digits."
                            return@setOnClickListener
                        }

                        database.get().addOnSuccessListener { allUsersSnapshot ->
                            var contactExists = false
                            for (child in allUsersSnapshot.children) {
                                if (child.key != userId) {
                                    val other = child.getValue(User::class.java)
                                    if (other?.contact == newContact) { contactExists = true; break }
                                }
                            }
                            if (contactExists) {
                                binding.contact.error = "Contact number already used"
                                return@addOnSuccessListener
                            }

                            val currentUser = auth.currentUser
                            if (isNameChanged || isContactChanged) {
                                binding.currentPassword.visibility = View.VISIBLE
                                binding.currentPasswordText.visibility = View.VISIBLE
                            } else {
                                binding.currentPassword.visibility = View.GONE
                                binding.currentPasswordText.visibility = View.GONE
                            }

                            val currentPassword = binding.currentPassword.text.toString().trim()
                            if ((isNameChanged || isContactChanged) && currentPassword.isEmpty()) {
                                binding.currentPassword.error = "Please enter your current password"
                                return@addOnSuccessListener
                            }

                            if (currentPassword.isNotEmpty()) {
                                val emailForAuth = currentUser?.email
                                if (emailForAuth.isNullOrEmpty()) {
                                    Toast.makeText(this, "Missing auth email", Toast.LENGTH_SHORT).show()
                                    return@addOnSuccessListener
                                }
                                val credential = EmailAuthProvider.getCredential(emailForAuth, currentPassword)
                                currentUser.reauthenticate(credential)
                                    .addOnSuccessListener {
                                        updateDatabase(
                                            userId = userId,
                                            name = newName,
                                            email = originalEmail,
                                            contact = newContact,
                                            profileBase64 = if (isPhotoAddedOrReplaced) base64ProfileImage else null,
                                            removePhoto = isPhotoRemoved
                                        )
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(this, "Re-authentication failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            } else {
                                updateDatabase(
                                    userId = userId,
                                    name = newName,
                                    email = originalEmail,
                                    contact = newContact,
                                    profileBase64 = if (isPhotoAddedOrReplaced) base64ProfileImage else null,
                                    removePhoto = isPhotoRemoved
                                )
                            }
                        }
                    }
                }
            }.addOnFailureListener { hideLoadingDialog() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    /* =========================================================
     * Connectivity
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    /* =========================================================
     * Firebase: Update
     * ========================================================= */
    private fun updateDatabase(
        userId: String,
        name: String,
        email: String,
        contact: String,
        profileBase64: String?,
        removePhoto: Boolean
    ) {
        val updates = mutableMapOf<String, Any>(
            "name" to name,
            "email" to email,
            "contact" to contact
        )
        if (profileBase64 != null) updates["profile"] = profileBase64

        database.child(userId).updateChildren(updates)
            .addOnSuccessListener {
                if (removePhoto) {
                    database.child(userId).child("profile").removeValue()
                        .addOnCompleteListener {
                            Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
                            hasProfileImage = false
                            removeProfileImageRequested = false
                            base64ProfileImage = null
                        }
                } else {
                    Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
                    hasProfileImage = profileBase64 != null
                    removeProfileImageRequested = false
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Database update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /* =========================================================
     * Image Picker UI
     * ========================================================= */
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
                        binding.profileIcon.setImageResource(R.drawable.ic_profile)
                        base64ProfileImage = null
                        hasProfileImage = false
                        removeProfileImageRequested = true
                        Toast.makeText(this, "Photo removed (pending save)", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    /* =========================================================
     * Permissions / Launchers
     * ========================================================= */
    private fun ensureCameraAndOpen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            openCamera()
        }
    }

    private fun ensureGalleryAndOpen() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), GALLERY_PERMISSION_REQUEST_CODE)
        } else {
            openGallery()
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) return
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> openCamera()
            GALLERY_PERMISSION_REQUEST_CODE -> openGallery()
        }
    }

    /* =========================================================
     * Activity Result (deprecated API retained)
     * ========================================================= */
    @Deprecated("Using startActivityForResult; migrate to Activity Result APIs when convenient")
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

    /* =========================================================
     * Bitmap <-> Base64
     * ========================================================= */
    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun convertBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) return null
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Exception) { null }
    }
}
