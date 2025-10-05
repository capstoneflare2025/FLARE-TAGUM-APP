package com.example.flare_capstone

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.flare_capstone.databinding.FragmentProfileFireFighterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class ProfileFireFighterFragment : Fragment() {

    private var _binding: FragmentProfileFireFighterBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseDatabase.getInstance().reference }

    private val stationPaths = listOf(
        "MabiniFireStation" to "MabiniFireFighterAccount",
        "LafilipinaFireStation" to "LafilipinaFireFighterAccount",
        "CanocotanFireStation" to "CanocotanFireFighterAccount"
    )

    // Will hold something like "MabiniFireStation/MabiniFireFighterAccount"
    private var matchedPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileFireFighterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // placeholders while loading
        binding.fullyName.text = "Loading..."
        binding.fullName.text = auth.currentUser?.email ?: "Unknown"
        binding.contact.text = "—"
        binding.profileIcon.setImageResource(R.drawable.ic_default_profile)

        val email = auth.currentUser?.email?.trim()?.lowercase()
        if (email.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No signed-in user.", Toast.LENGTH_SHORT).show()
        } else {
            findAndBindFirefighter(email)
        }

        // Edit Profile → launch firefighter editor with the matched path
        binding.editProfile.setOnClickListener {
            val path = matchedPath
            if (path == null) {
                Toast.makeText(requireContext(), "Profile path not resolved yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(requireActivity(), EditFirefighterProfileActivity::class.java)
            intent.putExtra(EditFirefighterProfileActivity.EXTRA_DB_PATH, path)
            startActivity(intent)
        }

        // Logout flow (kept)
        binding.logout.setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.dialog_logout, null)
            dialogView.findViewById<ImageView>(R.id.logoImageView)?.setImageResource(R.drawable.ic_logo)

            AlertDialog.Builder(requireActivity())
                .setView(dialogView)
                .setPositiveButton("Yes") { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(requireActivity(), MainActivity::class.java))
                    Toast.makeText(requireActivity(), "You have been logged out.", Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                }
                .setNegativeButton("No") { dlg, _ -> dlg.dismiss() }
                .create()
                .show()
        }
    }

    /**
     * Read each station’s *FireFighterAccount and compare .email with logged-in email.
     * Stops at the first match and binds the UI. Saves the matched DB path.
     */
    private fun findAndBindFirefighter(emailLc: String, index: Int = 0) {
        if (!isAdded || _binding == null) return
        if (index >= stationPaths.size) {
            binding.fullyName.text = "Unknown Firefighter"
            binding.fullName.text = emailLc
            binding.contact.text = "—"
            binding.profileIcon.setImageResource(R.drawable.ic_default_profile)
            matchedPath = null
            Toast.makeText(requireContext(), "No firefighter profile matched $emailLc.", Toast.LENGTH_SHORT).show()
            return
        }

        val (stationNode, accountNode) = stationPaths[index]
        val ref = db.child(stationNode).child(accountNode)

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!isAdded || _binding == null) return

                if (!snap.exists()) {
                    findAndBindFirefighter(emailLc, index + 1); return
                }

                val stationEmail = snap.child("email").getValue(String::class.java)?.trim()?.lowercase()
                if (stationEmail == emailLc) {
                    val name = snap.child("name").getValue(String::class.java) ?: "(No name)"
                    val contact = snap.child("contact").getValue(String::class.java) ?: "—"
                    val profileBase64 = snap.child("profile").getValue(String::class.java)

                    binding.fullyName.text = name
                    binding.fullName.text = stationEmail
                    binding.contact.text = contact

                    // Decode and show photo (Base64 → Bitmap). Fallback to default if null/invalid.
                    val bmp = convertBase64ToBitmap(profileBase64)
                    if (bmp != null) {
                        binding.profileIcon.setImageBitmap(bmp)
                    } else {
                        binding.profileIcon.setImageResource(R.drawable.ic_default_profile)
                    }

                    // Save path so the editor knows where to write
                    matchedPath = "$stationNode/$accountNode"

                } else {
                    findAndBindFirefighter(emailLc, index + 1)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded || _binding == null) return
                android.util.Log.w("ProfileFF", "DB error @ $stationNode/$accountNode: ${error.message}")
                findAndBindFirefighter(emailLc, index + 1)
            }
        })
    }

    private fun convertBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) return null
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
