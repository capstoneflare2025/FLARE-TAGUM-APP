package com.example.flare_capstone

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityFireFighterResponseBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class FireFighterResponseActivity : AppCompatActivity() {

    // Firebase paths
    private val ROOT = "TagumCityCentralFireStation"
    private val ACCOUNTS = "FireFighter/AllFireFighterAccount"
    private val ADMIN_MESSAGES = "AdminMessages"

    private lateinit var binding: ActivityFireFighterResponseBinding
    private lateinit var db: DatabaseReference
    private var accountKey: String? = null
    private var accountName: String = ""

    private var msgListener: ValueEventListener? = null
    private var msgRef: Query? = null
    private var lastTimestamp: Long = 0L  // To track the last timestamp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFireFighterResponseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseDatabase.getInstance().reference

        // Back button
        binding.back.setOnClickListener { finish() }

        // Load this firefighter account by email, then attach conversation
        resolveLoggedInAccountAndAttach()

        // Message input watcher
        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                binding.sendButton.isEnabled = hasText
                binding.sendButton.alpha = if (hasText) 1f else 0.4f
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Send button click
        binding.sendButton.setOnClickListener {
            val txt = binding.messageInput.text.toString().trim()
            if (txt.isEmpty()) return@setOnClickListener
            postFirefighterReply(txt)
        }
    }

    /**
     * Gets the currently logged-in firefighter account by their email
     * and attaches message listener.
     */
    private fun resolveLoggedInAccountAndAttach() {
        val email = FirebaseAuth.getInstance().currentUser?.email?.lowercase()
        if (email.isNullOrBlank()) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show()
            return
        }

        val accountsQuery = db.child(ROOT).child(ACCOUNTS)
            .orderByChild("email").equalTo(email)

        accountsQuery.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.hasChildren()) {
                    Toast.makeText(this@FireFighterResponseActivity, "Account not found for $email", Toast.LENGTH_SHORT).show()
                    return
                }
                val first = snapshot.children.first()
                accountKey = first.key
                accountName = first.child("name").getValue(String::class.java) ?: (first.key ?: "You")
                binding.fireStationName.text = accountName
                attachMessages()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@FireFighterResponseActivity, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * Attaches Firebase listener to the account's AdminMessages node.
     */
    private fun attachMessages() {
        val key = accountKey ?: return
        msgRef = db.child(ROOT).child(ACCOUNTS).child(key).child(ADMIN_MESSAGES)
            .orderByChild("timestamp")

        msgListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Triple<String, Map<String, Any?>, Long>>()
                for (child in snapshot.children) {
                    val map = child.value as? Map<String, Any?> ?: continue
                    val ts = (map["timestamp"] as? Number)?.toLong() ?: 0L
                    list.add(Triple(child.key ?: "", map, ts))
                }
                list.sortBy { it.third }
                renderMessages(list)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        msgRef!!.addValueEventListener(msgListener!!)
    }

    /**
     * Renders the messages in the scroll view.
     */
    private fun renderMessages(items: List<Triple<String, Map<String, Any?>, Long>>) {
        binding.scrollContent.removeAllViews()
        for ((_, msg, ts) in items) {
            val sender = (msg["sender"] as? String).orEmpty()
            val text = (msg["text"] as? String).orEmpty()
            val isYou = sender.equals(accountName, ignoreCase = true)

            // Just pass the text without any prefix.
            addMessageBubble(text, ts, isYou)
        }
        binding.scrollView.post { binding.scrollView.scrollTo(0, binding.scrollContent.bottom) }
    }

    /**
     * Adds a single message bubble to the layout.
     */
    private fun addMessageBubble(text: String, timestamp: Long, isYou: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isYou) Gravity.END else Gravity.START
            setPadding(20, 16, 20, 8)
        }

        if (text.isNotBlank()) {
            val msgView = TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.WHITE)
                setPadding(20, 14, 20, 14)
                background = if (isYou)
                    resources.getDrawable(R.drawable.received_message_bg, null)
                else
                    resources.getDrawable(R.drawable.sent_message_bg, null)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(msgView)
        }

        // Format the date and time
        val currentTime = System.currentTimeMillis()

        // Check if the current message is more than 6 hours from the last message
        val showTimestamp = shouldShowTimestamp(lastTimestamp, currentTime)

        if (showTimestamp) {
            // Display full date and time
            val formattedTime = SimpleDateFormat("MMM d, yyyy - HH:mm", Locale.getDefault()).format(Date(currentTime))
            val timeView = TextView(this).apply {
                setText(formattedTime)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.LTGRAY)
                setPadding(8, 4, 8, 0)
                gravity = Gravity.CENTER
            }
            container.addView(timeView)

            // Update the last timestamp
            lastTimestamp = currentTime
        } else {
            // Just show the time (HH:mm)
            val formattedTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTime))
            val timeView = TextView(this).apply {
                setText(formattedTime)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.LTGRAY)
                setPadding(8, 4, 8, 0)
                gravity = if (isYou) Gravity.END else Gravity.START
            }
            container.addView(timeView)
        }

        binding.scrollContent.addView(container)
    }

    /**
     * Determines if a timestamp should be shown based on the last message timestamp.
     */
    private fun shouldShowTimestamp(lastTs: Long, currentTs: Long): Boolean {
        val sixHoursInMillis = 6 * 60 * 60 * 1000
        return (currentTs - lastTs) >= sixHoursInMillis || isNewDay(currentTs, lastTs)
    }

    /**
     * Checks if a new day has started.
     */
    private fun isNewDay(currentTs: Long, lastTs: Long): Boolean {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return sdf.format(Date(currentTs)) != sdf.format(Date(lastTs))
    }

    /**
     * Pushes a new reply message to Firebase.
     */
    private fun postFirefighterReply(text: String) {
        val key = accountKey ?: return
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        val data = mapOf(
            "sender" to accountName,
            "text" to text,
            "timestamp" to now,
            "date" to date,
            "time" to time
        )

        db.child(ROOT).child(ACCOUNTS).child(key).child(ADMIN_MESSAGES)
            .push().setValue(data)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    binding.messageInput.text?.clear()
                } else {
                    Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        msgListener?.let { listener -> msgRef?.removeEventListener(listener) }
        msgListener = null
        msgRef = null
    }
}
