package com.example.flare_capstone

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.flare_capstone.databinding.FragmentProfileFireFighterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class ProfileFireFighterFragment : Fragment() {

    private var _binding: FragmentProfileFireFighterBinding? = null
    private val binding get() = _binding!!

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

        // Show logged-in email in the "Full Name" section
        val email = FirebaseAuth.getInstance().currentUser?.email ?: "Unknown"
        binding.fullName.text = email

        binding.logout.setOnClickListener {
            val inflater = requireActivity().layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_logout, null)
            val logoImageView = dialogView.findViewById<ImageView>(R.id.logoImageView)
            logoImageView.setImageResource(R.drawable.ic_logo)

            AlertDialog.Builder(requireActivity())
                .setView(dialogView)
                .setPositiveButton("Yes") { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(requireActivity(), MainActivity::class.java))
                    Toast.makeText(requireActivity(), "You have been logged out.", Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                }
                .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
                .create()
                .show()
        }
    }


    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
