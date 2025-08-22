package com.example.flare_capstone

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityLoginBinding
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    // Strong password: 8+ chars, upper, lower, digit, special, no spaces
    private val PASSWORD_REGEX =
        Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])(?=\\S+$).{8,}$")

    // Replace with your Firebase project's default domain
    private val continueUrl = "https://flare-capstone-c029d.firebaseapp.com/reset-complete"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        setupPasswordToggle(binding.password)
        binding.password.compoundDrawablePadding = 14 // match your EditText padding

        binding.registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        binding.loginButton.setOnClickListener {
            val email = binding.email.text.toString().trim()
            val password = binding.password.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                if (email.isEmpty()) binding.email.error = "Required" else binding.email.error = null
                if (password.isEmpty()) binding.password.error = "Required" else binding.password.error = null
                Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            } else {
                binding.email.error = null
                binding.password.error = null
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val userEmail = auth.currentUser?.email
                        if (userEmail == "mabinifirefighter123@gmail.com" ||
                            userEmail == "lafilipinafirefighter123@gmail.com" ||
                            userEmail == "canocotanfirefighter123@gmail.com") {
                            startActivity(Intent(this, DashboardFireFighterActivity::class.java))
                        } else {
                            startActivity(Intent(this, DashboardActivity::class.java))
                        }
                        finish()
                    } else {
                        val message = when (val ex = task.exception) {
                            is FirebaseAuthInvalidCredentialsException -> "Incorrect password"
                            is FirebaseAuthInvalidUserException -> "No account found for this email"
                            else -> "Login failed: ${ex?.message}"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }


        }

        binding.forgotPassword.setOnClickListener {
            val email = binding.email.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.email.error = "Enter valid email, e.g., example@gmail.com"
                Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendPasswordResetLink(email)
        }

        binding.logo.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Handle reset link if app is opened from email
        handleResetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResetIntent(intent)
    }

    private fun handleResetIntent(intent: Intent) {
        val data: Uri? = intent.data ?: return
        val mode = data!!.getQueryParameter("mode")
        val oobCode = data.getQueryParameter("oobCode")
        if (mode != "resetPassword" || oobCode.isNullOrBlank()) return

        auth.verifyPasswordResetCode(oobCode)
            .addOnSuccessListener {
                showNewPasswordDialog(oobCode)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Invalid or expired reset link: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showNewPasswordDialog(oobCode: String) {
        val newPass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "New password"
        }
        val confirmPass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "Confirm password"
        }

        setupPasswordToggle(newPass)
        setupPasswordToggle(confirmPass)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(newPass)
            addView(confirmPass)
        }

        AlertDialog.Builder(this)
            .setTitle("Set New Password")
            .setView(container)
            .setPositiveButton("Update") { _, _ ->
                val p1 = newPass.text.toString()
                val p2 = confirmPass.text.toString()
                if (!PASSWORD_REGEX.matches(p1)) {
                    Toast.makeText(this, "Weak password. Use 8+ chars with upper, lower, number, special, no spaces.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (p1 != p2) {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                auth.confirmPasswordReset(oobCode, p1)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Password updated. You can log in now.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to update password: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendPasswordResetLink(email: String) {
        // Log the email being used
        android.util.Log.d("PasswordReset", "Attempting to send reset email to: $email")

        // Optional: Check if email is valid before calling Firebase
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            android.util.Log.e("PasswordReset", "Invalid email format: $email")
            Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    android.util.Log.i("PasswordReset", "Password reset link successfully sent to: $email")
                    Toast.makeText(this, "Password reset link sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    val exception = task.exception
                    android.util.Log.e("PasswordReset", "Failed to send reset email", exception)
                    Toast.makeText(
                        this,
                        "Reset failed: ${exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun initPasswordField(editText: EditText) {
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        editText.transformationMethod = PasswordTransformationMethod.getInstance()
        setupPasswordToggle(editText)
    }

    private fun setupPasswordToggle(editText: EditText) {
        val visibleIcon = android.R.drawable.ic_menu_view
        val hiddenIcon = android.R.drawable.ic_secure
        setEndIcon(editText, hiddenIcon)
        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableRight = 2
                val d = editText.compoundDrawables[drawableRight]
                if (d != null) {
                    val iconStart = editText.width - editText.paddingRight - d.intrinsicWidth
                    if (event.x >= iconStart) {
                        togglePasswordVisibility(editText, visibleIcon, hiddenIcon)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun togglePasswordVisibility(editText: EditText, visibleIcon: Int, hiddenIcon: Int) {
        val sel = editText.selectionEnd
        val hidden = editText.transformationMethod is PasswordTransformationMethod
        if (hidden) {
            editText.transformationMethod = null
            setEndIcon(editText, visibleIcon)
        } else {
            editText.transformationMethod = PasswordTransformationMethod.getInstance()
            setEndIcon(editText, hiddenIcon)
        }
        editText.setSelection(sel)
    }

    private fun setEndIcon(editText: EditText, iconRes: Int) {
        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
    }
}
