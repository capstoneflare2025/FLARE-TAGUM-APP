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
            } else {
                binding.email.error = null
            }

            if (!CONTACT_REGEX.matches(contact)) {
                binding.contact.error = when {
                    contact.startsWith("9") -> "Must start with 09"
                    contact.startsWith("0") && contact.length >= 2 && contact[1] != '9' -> "Must start with 09"
                    contact.startsWith("09") && contact.length != 11 -> "Must be exactly 11 digits"
                    else -> "Must start with 09 and be exactly 11 digits"
                }
                toast("Contact invalid")
                return@setOnClickListener
            } else {
                binding.contact.error = null
            }

            if (!PASSWORD_REGEX.matches(password)) {
                binding.password.error = "8+ chars, upper, lower, number, special, no spaces"
                toast("Weak password")
                return@setOnClickListener
            } else {
                binding.password.error = null
            }

            if (password != confirmPassword) {
                binding.confirmPassword.error = "Passwords do not match"
                toast("Passwords do not match")
                return@setOnClickListener
            } else {
                binding.confirmPassword.error = null
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val userId = auth.currentUser?.uid
                        val user = User(name, email, contact)
                        if (userId != null) {
                            database.child(userId).setValue(user)
                                .addOnCompleteListener { dbTask ->
                                    if (dbTask.isSuccessful) {
                                        toast("Registration successful. Login your account.")
                                        startActivity(Intent(this, LoginActivity::class.java))
                                        finish()
                                    } else {
                                        toast("Failed to save user data")
                                    }
                                }
                        } else {
                            toast("Registration failed: missing user id")
                        }
                    } else {
                        toast("Registration failed: ${task.exception?.message}")
                    }
                }
        }
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
