package com.example.flare_capstone

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.MotionEvent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlin.jvm.java

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    private val CONTACT_REGEX = Regex("^09\\d{9}$")
    private val PASSWORD_REGEX =
        Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])(?=\\S+$).{8,}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("Users")

        binding.contact.keyListener = DigitsKeyListener.getInstance("0123456789")
        binding.contact.filters = arrayOf(InputFilter.LengthFilter(11))
        attachContactWatcher()

        attachEmailWatcher()

        setupPasswordToggle(binding.password)
        setupPasswordToggle(binding.confirmPassword)
        binding.password.compoundDrawablePadding = 14 // match your EditText padding
        binding.confirmPassword.compoundDrawablePadding = 14 // match your EditText padding

        binding.loginButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.logo.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.register.setOnClickListener {
            val name = binding.name.text.toString().trim()
            val email = binding.email.text.toString().trim()
            val contact = binding.contact.text.toString().trim()
            val password = binding.password.text.toString()
            val confirmPassword = binding.confirmPassword.text.toString()

            // ---- validations (yours, unchanged) ----
            if (name.isEmpty() || email.isEmpty() || contact.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                if (name.isEmpty()) binding.name.error = "Required"
                if (email.isEmpty()) binding.email.error = "Required"
                if (contact.isEmpty()) binding.contact.error = "Required"
                if (password.isEmpty()) binding.password.error = "Required"
                if (confirmPassword.isEmpty()) binding.confirmPassword.error = "Required"
                toast("Fill all fields")
                return@setOnClickListener
            }
            if (!isValidEmail(email)) {
                binding.email.error = "Enter valid email, e.g., example@gmail.com"
                toast("Invalid email format")
                return@setOnClickListener
            } else binding.email.error = null

            if (!CONTACT_REGEX.matches(contact)) {
                binding.contact.error = when {
                    contact.startsWith("9") -> "Must start with 09"
                    contact.startsWith("0") && contact.length >= 2 && contact[1] != '9' -> "Must start with 09"
                    contact.startsWith("09") && contact.length != 11 -> "Must be exactly 11 digits"
                    else -> "Must start with 09 and be exactly 11 digits"
                }
                toast("Contact invalid")
                return@setOnClickListener
            } else binding.contact.error = null

            if (!PASSWORD_REGEX.matches(password)) {
                binding.password.error = "8+ chars, upper, lower, number, special, no spaces"
                toast("Weak password")
                return@setOnClickListener
            } else binding.password.error = null

            if (password != confirmPassword) {
                binding.confirmPassword.error = "Passwords do not match"
                toast("Passwords do not match")
                return@setOnClickListener
            } else binding.confirmPassword.error = null
            // ---- end validations ----

            // 1) Check if email already has an Auth account
            auth.fetchSignInMethodsForEmail(email)
                .addOnSuccessListener { res ->
                    val methods = res.signInMethods ?: emptyList()
                    if (methods.isEmpty()) {
                        // Brand-new → create + send verify (no DB write yet)
                        createUserAndSendVerify(email, password, name, contact)
                        return@addOnSuccessListener
                    }

                    // 2) Account exists → try to sign in with the provided password
                    auth.signInWithEmailAndPassword(email, password)
                        .addOnSuccessListener {
                            val user = auth.currentUser ?: return@addOnSuccessListener
                            user.reload().addOnCompleteListener {
                                val verified = user.isEmailVerified
                                if (verified) {
                                    // Already verified → tell them to log in normally
                                    toast("Email is already verified. Please log in.")
                                    auth.signOut()
                                    startActivity(Intent(this, LoginActivity::class.java))
                                    finish()
                                } else {
                                    // Not verified → show “Pending Verification” dialog
                                    showPendingVerificationDialog(
                                        email = email,
                                        name = name,
                                        contact = contact,
                                        canResend = true // password was correct → we can resend
                                    )
                                }
                            }
                        }
                        .addOnFailureListener {
                            // Wrong password or sign-in blocked → we can’t resend, but inform and offer Reset
                            showPendingVerificationDialog(
                                email = email,
                                name = name,
                                contact = contact,
                                canResend = false // need correct password to resend from client
                            )
                        }
                }
                .addOnFailureListener { e ->
                    toast("Couldn’t check email: ${e.message}")
                }
        }

    }

    private fun createUserAndSendVerify(email: String, password: String, name: String, contact: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    toast("Registration failed: ${task.exception?.message}")
                    return@addOnCompleteListener
                }
                val user = auth.currentUser ?: return@addOnCompleteListener toast("Registration failed: no user")

                // Do NOT write to /Users yet. Only after verified (in VerifyEmailActivity).
                user.sendEmailVerification()
                    .addOnSuccessListener {
                        toast("Verification link sent to $email")
                        val i = Intent(this, VerifyEmailActivity::class.java).apply {
                            putExtra("name", name)
                            putExtra("email", email)
                            putExtra("contact", contact)
                        }
                        startActivity(i)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        toast("Failed to send verification: ${e.message}")
                    }
            }
    }

    private fun showPendingVerificationDialog(email: String, name: String, contact: String, canResend: Boolean) {
        val msg = buildString {
            appendLine("This email is pending verification:")
            appendLine(email)
            appendLine()
            appendLine("We’ve sent a verification link to your inbox.")
            appendLine("• Verify now to complete your registration.")
            appendLine("• Or use a different email.")
            appendLine("• If you do nothing, this email will be released after 24 hours.")
        }

        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Pending Verification")
            .setMessage(msg)
            .setCancelable(false)
            .apply {
                if (canResend) {
                    setPositiveButton("Resend link") { _, _ ->
                        // Current user is signed in at this point (password was correct)
                        val u = auth.currentUser
                        if (u == null) {
                            toast("No user session to resend.")
                            return@setPositiveButton
                        }
                        u.sendEmailVerification()
                            .addOnSuccessListener {
                                toast("Verification link re-sent to $email")
                                // Go to verify screen with the entered details (we’ll write DB after verify)
                                val i = Intent(this@RegisterActivity, VerifyEmailActivity::class.java).apply {
                                    putExtra("name", name)
                                    putExtra("email", email)
                                    putExtra("contact", contact)
                                }
                                startActivity(i)
                                finish()
                            }
                            .addOnFailureListener { e ->
                                toast("Resend failed: ${e.message}")
                            }
                    }
                } else {
                    // We can't resend without a successful sign-in → offer Reset Password
                    setPositiveButton("Reset password") { _, _ ->
                        auth.sendPasswordResetEmail(email)
                            .addOnSuccessListener { toast("Password reset email sent to $email") }
                            .addOnFailureListener { e -> toast("Reset failed: ${e.message}") }
                    }
                }

                setNegativeButton("Use different email") { _, _ ->
                    // Sign out if we happened to sign the user in earlier
                    auth.signOut()
                    // Let them edit the email field; no navigation needed
                }

                setNeutralButton("OK") { _, _ ->
                    // Just close; keep current state
                }
            }
            .create()

        dlg.show()
    }


    private fun attachContactWatcher() {
        binding.contact.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                val v = editable?.toString() ?: ""
                when {
                    v.isEmpty() -> binding.contact.error = null
                    v.startsWith("9") -> binding.contact.error = "Must start with 09"
                    v.startsWith("0") && v.length == 1 -> binding.contact.error = "Must start with 09"
                    v.length >= 2 && v[0] == '0' && v[1] != '9' -> binding.contact.error = "Must start with 09"
                    v.startsWith("09") && v.length < 11 -> binding.contact.error = "Must be exactly 11 digits"
                    v.startsWith("09") && v.length == 11 -> binding.contact.error = null
                    else -> binding.contact.error = "Must start with 09"
                }
            }
        })
    }

    private fun attachEmailWatcher() {
        binding.email.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                val email = editable?.toString()?.trim() ?: ""
                if (email.isEmpty()) {
                    binding.email.error = null
                    return
                }
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    binding.email.error = "Enter valid email, e.g., example@gmail.com"
                } else {
                    binding.email.error = null
                }
            }
        })
    }

    private fun isValidEmail(email: String): Boolean =
        !TextUtils.isEmpty(email) && Patterns.EMAIL_ADDRESS.matcher(email).matches()

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
                val drawable = editText.compoundDrawables[drawableRight]
                if (drawable != null) {
                    val iconStart = editText.width - editText.paddingRight - drawable.intrinsicWidth
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
        val selection = editText.selectionEnd
        val isHidden = editText.transformationMethod is PasswordTransformationMethod
        if (isHidden) {
            editText.transformationMethod = null
            setEndIcon(editText, visibleIcon)
        } else {
            editText.transformationMethod = PasswordTransformationMethod.getInstance()
            setEndIcon(editText, hiddenIcon)
        }
        editText.setSelection(selection)
    }

    private fun setEndIcon(editText: EditText, iconRes: Int) {
        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    data class User(val name: String, val email: String, val contact: String)
}
