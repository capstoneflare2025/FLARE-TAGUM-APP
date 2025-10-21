package com.example.flare_capstone

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityVerifyEmailBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class VerifyEmailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyEmailBinding
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().getReference("Users")

    private var cooldownMs = 30_000L // 30s between resends
    private var timer: CountDownTimer? = null

    private var name: String = ""
    private var email: String = ""
    private var contact: String = ""
    private var date: String = ""
    private var time: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyEmailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        name    = intent.getStringExtra("name") ?: ""
        email   = intent.getStringExtra("email") ?: ""
        contact = intent.getStringExtra("contact") ?: ""
        date    = intent.getStringExtra("date") ?: ""
        time   = intent.getStringExtra("time") ?: ""

        binding.emailTv.text = email

        binding.verifiedBtn.setOnClickListener { checkVerifiedAndFinish() }
        binding.resendBtn.setOnClickListener { resendVerification() }
        binding.changeEmailBtn.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        startCooldown() // prevent instant spam
    }

    private fun checkVerifiedAndFinish() {
        val user = auth.currentUser ?: return toast("No user session.")
        user.reload().addOnCompleteListener { reloadTask ->
            if (!reloadTask.isSuccessful) {
                toast("Couldn’t refresh status. Try again.")
                return@addOnCompleteListener
            }
            if (user.isEmailVerified) {
                val uid = user.uid
                val obj = mapOf("name" to name, "email" to email, "contact" to contact, "date" to date, "time" to time)
                database.child(uid).setValue(obj).addOnCompleteListener { dbTask ->
                    if (dbTask.isSuccessful) {
                        toast("Email verified! You can now log in.")
                        auth.signOut()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    } else {
                        toast("Failed to save profile. Please try again.")
                    }
                }
            } else {
                toast("Not verified yet. Tap the link in your email.")
            }
        }
    }

    private fun resendVerification() {
        val user = auth.currentUser ?: return toast("No user session.")
        user.sendEmailVerification()
            .addOnSuccessListener {
                toast("Verification link resent.")
                startCooldown()
            }
            .addOnFailureListener { e ->
                toast("Resend failed: ${e.message}")
            }
    }

    private fun startCooldown() {
        binding.resendBtn.isEnabled = false
        timer?.cancel()
        timer = object : CountDownTimer(cooldownMs, 1_000) {
            override fun onTick(ms: Long) {
                binding.timerTv.text = "You can resend in ${ms / 1000}s"
            }
            override fun onFinish() {
                binding.timerTv.text = ""
                binding.resendBtn.isEnabled = true
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
