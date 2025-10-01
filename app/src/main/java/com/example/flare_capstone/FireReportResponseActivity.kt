package com.example.flare_capstone

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.media.MediaRecorder
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.flare_capstone.databinding.ActivityFireReportResponseBinding
import com.google.firebase.database.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FireReportResponseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFireReportResponseBinding
    private lateinit var database: DatabaseReference

    private lateinit var uid: String
    private lateinit var fireStationName: String
    private lateinit var incidentId: String
    private var fromNotification: Boolean = false

    private var base64Image: String = ""
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var isRecording = false
    private val RECORD_AUDIO_PERMISSION_CODE = 103
    private var isPaused = false
    private var pauseStartMs = 0L


    private var recordStartMs = 0L
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordStartMs
            val sec = (elapsed / 1000).toInt()
            val mm = sec / 60
            val ss = sec % 60
            binding.recordTimer.text = String.format("%02d:%02d", mm, ss)
            timerHandler.postDelayed(this, 500)
        }
    }


    companion object {
        const val CAMERA_REQUEST_CODE = 100
        const val CAMERA_PERMISSION_REQUEST_CODE = 101
        const val GALLERY_REQUEST_CODE = 102

        const val TAG = "FireReportResponse"
    }

    private val reportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
    )

    private val stationNodeByDisplayName = mapOf(
        "La Filipina Fire Station" to "LaFilipinaFireStation",
        "Canocotan Fire Station"   to "CanocotanFireStation",
        "Mabini Fire Station"      to "MabiniFireStation",
        "LaFilipinaFireStation"    to "LaFilipinaFireStation",
        "CanocotanFireStation"     to "CanocotanFireStation",
        "MabiniFireStation"        to "MabiniFireStation"
    )

    private lateinit var stationNode: String
    private lateinit var reportNode: String

    data class ChatMessage(
        var type: String? = null,
        var text: String? = null,
        var imageBase64: String? = null,
        var audioBase64: String? = null,
        var uid: String? = null,
        var reporterName: String? = null,
        var date: String? = null,
        var time: String? = null,
        var timestamp: Long? = null,
        var isRead: Boolean? = false
    )

    sealed class MessageItem(val key: String, val timestamp: Long) {
        data class AnyMsg(val keyId: String, val msg: ChatMessage, val time: Long): MessageItem(keyId, time)
    }

    private lateinit var messagesListener: ValueEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFireReportResponseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance().reference

        uid = intent.getStringExtra("UID") ?: ""
        fireStationName = intent.getStringExtra("FIRE_STATION_NAME") ?: ""
        incidentId = intent.getStringExtra("INCIDENT_ID") ?: ""
        fromNotification = intent.getBooleanExtra("fromNotification", false)

        val explicitStationNode = intent.getStringExtra("STATION_NODE")
        val explicitReportNode = intent.getStringExtra("REPORT_NODE")

        stationNode = explicitStationNode ?: run {
            Toast.makeText(this, "Station node missing!", Toast.LENGTH_SHORT).show()
            return
        }

        reportNode = explicitReportNode ?: run {
            Toast.makeText(this, "Report node missing!", Toast.LENGTH_SHORT).show()
            return
        }


        binding.fireStationName.text = fireStationName.ifEmpty { stationNode }

        if (incidentId.isEmpty()) {
            Toast.makeText(this, "No Incident ID provided.", Toast.LENGTH_SHORT).show()
            return
        }

        // IMPORTANT: Mark station responses as read if opened (from notif or from list)
        markStationResponsesAsReadOnOpen()

        attachMessagesListener()

        binding.back.setOnClickListener { onBackPressed() }

        binding.cameraIcon.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
                )
            }
        }

        binding.chatInputArea.layoutTransition?.enableTransitionType(android.animation.LayoutTransition.CHANGING)
// 1) When the field gains focus, treat it like “typing mode”
        binding.messageInput.setOnFocusChangeListener { _, hasFocus ->
            setTypingUi(hasFocus || (binding.messageInput.text?.isNotBlank() == true))
        }

// 2) When text changes, toggle typing mode based on emptiness
        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                setTypingUi(hasText || binding.messageInput.hasFocus())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

// 3) (Nice-to-have) Also react when the keyboard opens/closes
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatInputArea) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            setTypingUi(imeVisible || binding.messageInput.text?.isNotBlank() == true)
            insets
        }

        // expand when focused or has text
        binding.messageInput.setOnFocusChangeListener { _, hasFocus ->
            setExpandedUi(hasFocus || (binding.messageInput.text?.isNotBlank() == true))
        }

        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val expanded = !s.isNullOrBlank() || binding.messageInput.hasFocus()
                setExpandedUi(expanded)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

// arrow tap → collapse to normal (keep text)
        binding.arrowBackIcon.setOnClickListener {
            binding.messageInput.clearFocus()
            binding.chatInputArea.hideKeyboard()
            setExpandedUi(false)
            // (optional) also clear text:
            // binding.messageInput.text?.clear()
        }

// optional: react to keyboard open/close for smoother UX
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatInputArea) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            setExpandedUi(imeVisible || binding.messageInput.text?.isNotBlank() == true)
            insets
        }



        binding.galleryIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, GALLERY_REQUEST_CODE)
        }

        // when startRecording():
        binding.voiceRecordIcon.setImageResource(R.drawable.ic_recording) // add a red mic asset

// in stopRecordingAndPromptSend() finally:
        binding.voiceRecordIcon.setImageResource(R.drawable.ic_record)   // back to idle mic

        binding.voiceRecordIcon.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startRecordingMessengerStyle()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_CODE
                )
            }
        }

        binding.recordPause.setOnClickListener { togglePauseResume() }
        binding.recordCancel.setOnClickListener { cancelRecording() }
        binding.recordSend.setOnClickListener { finishRecordingAndSend() }


        binding.sendButton.setOnClickListener {
            val userMessage = binding.messageInput.text.toString().trim()
            when {
                userMessage.isNotEmpty() && base64Image.isNotEmpty() -> {
                    pushChatMessage(type = "reply", text = userMessage, imageBase64 = base64Image)
                    displayUserMessage("", userMessage, convertBase64ToBitmap(base64Image), isReply = true, timestamp = System.currentTimeMillis())
                }
                userMessage.isNotEmpty() -> {
                    pushChatMessage(type = "reply", text = userMessage, imageBase64 = "")
                    displayUserMessage("", userMessage, null, isReply = true, timestamp = System.currentTimeMillis())
                }
                base64Image.isNotEmpty() -> {
                    pushChatMessage(type = "reply", text = "", imageBase64 = base64Image)
                    displayUserMessage("", "", convertBase64ToBitmap(base64Image), isReply = true, timestamp = System.currentTimeMillis())
                }
                else -> {
                    Toast.makeText(this, "Message or image is required.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRecordBar(show: Boolean) {
        binding.recordBar.visibility = if (show) View.VISIBLE else View.GONE

        val dim = if (show) 0.3f else 1f
        binding.messageInput.isEnabled = !show
        binding.cameraIcon.isEnabled = !show
        binding.galleryIcon.isEnabled = !show
        binding.sendButton.isEnabled = !show

        binding.messageInput.alpha = dim
        binding.cameraIcon.alpha = dim
        binding.galleryIcon.alpha = dim
        binding.sendButton.alpha = if (show) 0.4f else 1f

        binding.voiceRecordIcon.setImageResource(
            if (show) R.drawable.ic_recording else R.drawable.ic_record
        )

        if (show) {
            binding.recordPause.setImageResource(
                if (isPaused) R.drawable.ic_resume else R.drawable.ic_pause
            )
        }
    }


    private fun startRecordingMessengerStyle() {
        try {
            recordFile = File.createTempFile("voice_", ".m4a", cacheDir)
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(recordFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            isPaused = false                // NEW
            pauseStartMs = 0L               // NEW
            recordStartMs = System.currentTimeMillis()
            binding.recordTimer.text = "00:00"
            binding.recordPause.setImageResource(R.drawable.ic_pause)  // NEW
            timerHandler.post(timerRunnable)
            showRecordBar(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            cleanupRecorder()
            showRecordBar(false)
        }
    }

    private fun togglePauseResume() {
        if (!isRecording) return

        if (android.os.Build.VERSION.SDK_INT < 24) {
            Toast.makeText(this, "Pause requires Android 7.0+", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (!isPaused) {
                recorder?.pause()
                isPaused = true
                pauseStartMs = System.currentTimeMillis()
                timerHandler.removeCallbacks(timerRunnable)
                binding.recordPause.setImageResource(R.drawable.ic_resume)
            } else {
                recorder?.resume()
                isPaused = false
                val pausedDuration = System.currentTimeMillis() - pauseStartMs
                recordStartMs += pausedDuration      // keep timer accurate
                pauseStartMs = 0L
                binding.recordPause.setImageResource(R.drawable.ic_pause)
                timerHandler.post(timerRunnable)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not toggle pause", Toast.LENGTH_SHORT).show()
        }
    }


    private fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        recordFile?.delete()
        recordFile = null
        isRecording = false
        isPaused = false            // NEW
        pauseStartMs = 0L           // NEW
        timerHandler.removeCallbacks(timerRunnable)
        showRecordBar(false)
        Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show()
    }

    private fun finishRecordingAndSend() {
        val file = recordFile
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        isRecording = false
        isPaused = false            // NEW
        pauseStartMs = 0L           // NEW
        timerHandler.removeCallbacks(timerRunnable)
        showRecordBar(false)

        if (file == null || !file.exists()) {
            Toast.makeText(this, "No recording captured", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bytes = file.readBytes()
            val audioB64 = Base64.encodeToString(bytes, Base64.DEFAULT)
            pushChatMessage(type = "reply", text = "", imageBase64 = "", audioBase64 = audioB64)
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to send recording", Toast.LENGTH_SHORT).show()
        } finally {
            file.delete()
        }
    }


    private fun cleanupRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) startRecordingMessengerStyle()
            else Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }


    private fun View.hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun setExpandedUi(expanded: Boolean) {
        // camera & gallery hidden while expanded
        binding.cameraIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.galleryIcon.visibility = if (expanded) View.GONE else View.VISIBLE

        // arrow only when expanded (and placed BEFORE the EditText)
        binding.arrowBackIcon.visibility = if (expanded) View.VISIBLE else View.GONE

        // send button state
        val hasText = binding.messageInput.text?.isNotBlank() == true
        binding.sendButton.isEnabled = hasText
        binding.sendButton.alpha = if (hasText) 1f else 0.4f

        // give EditText more room while expanded
        binding.messageInput.maxLines = if (expanded) 5 else 3
    }

    private fun setTypingUi(isTyping: Boolean) {
        // Hide or show the left-side icons
        binding.cameraIcon.visibility = if (isTyping) View.GONE else View.VISIBLE
        binding.galleryIcon.visibility = if (isTyping) View.GONE else View.VISIBLE

        // Optionally show/hide (or dim) the send button
        val hasText = binding.messageInput.text?.isNotBlank() == true
        binding.sendButton.isEnabled = hasText
        binding.sendButton.alpha = if (hasText) 1f else 0.4f

        // Let the EditText breathe a little more when typing
        if (isTyping) {
            binding.messageInput.maxLines = 5
        } else {
            binding.messageInput.maxLines = 3
        }
    }


    private fun messagesPath(): DatabaseReference =
        database.child(stationNode).child(reportNode).child(incidentId).child("messages")

    private fun markStationResponsesAsReadOnOpen() {
        val q = database.child(stationNode)
            .child("ResponseMessage")
            .orderByChild("incidentId")
            .equalTo(incidentId)

        q.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val updates = hashMapOf<String, Any?>()
                snapshot.children.forEach { child ->
                    updates["${child.key}/isRead"] = true
                }
                if (updates.isNotEmpty()) {
                    database.child(stationNode).child("ResponseMessage").updateChildren(updates)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }



    private fun attachMessagesListener() {
        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val merged = mutableListOf<MessageItem.AnyMsg>()
                snapshot.children.forEach { ds ->
                    val key = ds.key ?: return@forEach
                    val msg = ds.getValue(ChatMessage::class.java) ?: return@forEach
                    val ts = msg.timestamp ?: dateTimeFallbackToMillis(msg.date, msg.time)
                    merged.add(MessageItem.AnyMsg(key, msg, ts))
                }
                merged.sortBy { it.timestamp }
                renderMerged(merged)
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@FireReportResponseActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
        messagesPath().addValueEventListener(messagesListener)
    }

    private fun openCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(cameraIntent, CAMERA_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as? Bitmap
            if (imageBitmap != null) {
                base64Image = convertBitmapToBase64(imageBitmap)
                AlertDialog.Builder(this)
                    .setTitle("Send Picture")
                    .setMessage("Do you want to send this picture?")
                    .setPositiveButton("Send") { _, _ ->
                        pushChatMessage(type = "reply", text = "", imageBase64 = base64Image)
                        displayUserMessage("", "", imageBitmap, isReply = true, timestamp = System.currentTimeMillis())
                    }
                    .setNegativeButton("Cancel") { _, _ -> base64Image = "" }
                    .show()
            }
        } else if (requestCode == GALLERY_REQUEST_CODE && resultCode == RESULT_OK) {
            val selectedImageUri = data?.data
            if (selectedImageUri != null) {
                val inputStream = contentResolver.openInputStream(selectedImageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    base64Image = convertBitmapToBase64(bitmap)
                    AlertDialog.Builder(this)
                        .setTitle("Send Picture")
                        .setMessage("Do you want to send this picture?")
                        .setPositiveButton("Send") { _, _ ->
                            pushChatMessage(type = "reply", text = "", imageBase64 = base64Image)
                            displayUserMessage("", "", bitmap, isReply = true, timestamp = System.currentTimeMillis())
                        }
                        .setNegativeButton("Cancel") { _, _ -> base64Image = "" }
                        .show()
                }
            }
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
    }

    private fun convertBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) return null
        return try {
            val decoded = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (_: Exception) { null }
    }

    // Write message under incident thread and mirror a summary under {StationNode}/ReplyMessage
    private fun pushChatMessage(type: String, text: String, imageBase64: String) {
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        val msg = ChatMessage(
            type = type,
            text = text,
            imageBase64 = imageBase64.ifEmpty { null },
            audioBase64 = null,
            uid = uid,
            reporterName = intent.getStringExtra("NAME") ?: "",
            date = date,
            time = time,
            timestamp = now,
            isRead = false
        )

        messagesPath().push().setValue(msg).addOnCompleteListener { t ->
            if (t.isSuccessful) {
                // Mirror to /{StationNode}/ReplyMessage (for your “store all replies” list)
                mirrorReplyUnderStationNode(msg)
                Toast.makeText(this, "Message sent!", Toast.LENGTH_SHORT).show()
                base64Image = ""
                binding.messageInput.text.clear()
            } else {
                Toast.makeText(this, "Error sending message.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mirrorReplyUnderStationNode(msg: ChatMessage) {
        val map = hashMapOf<String, Any?>(
            "fireStationName" to fireStationName,
            "incidentId" to incidentId,
            "reporterName" to msg.reporterName,
            "contact" to null,
            "replyMessage" to (msg.text ?: ""),
            "imageBase64" to msg.imageBase64,
            "timestamp" to (msg.timestamp ?: System.currentTimeMillis()),
            "isRead" to true // citizen sent it; station can compute separate counters
        )
        database.child(stationNode).child("ReplyMessage").push().setValue(map)
    }

    private fun dateTimeFallbackToMillis(date: String?, time: String?): Long {
        if (date.isNullOrEmpty() || time.isNullOrEmpty()) return 0L
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
            SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.getDefault())
        )
        val combined = "$date $time"
        for (f in formats) {
            try { f.parse(combined)?.let { return it.time } } catch (_: Exception) {}
        }
        return 0L
    }

    private fun renderMerged(items: List<MessageItem.AnyMsg>) {
        binding.scrollContent.removeAllViews()
        items.forEach { item ->
            val isReply = item.msg.type.equals("reply", ignoreCase = true)
            val bmp = convertBase64ToBitmap(item.msg.imageBase64)
            displayUserMessage(item.key, item.msg.text.orEmpty(), bmp, isReply, item.timestamp)
            if (!(item.msg.isRead ?: false)) markAsRead(item.key)
        }
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun markAsRead(messageKey: String) {
        messagesPath().child(messageKey).child("isRead").setValue(true)
    }

    private fun displayUserMessage(
        key: String,
        message: String,
        imageBitmap: Bitmap?,
        isReply: Boolean,
        timestamp: Long
    ) {
        val messageWithPeriod =
            if (message.isNotEmpty() && !message.endsWith('.')) "$message." else message

        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 15, 20, 15)
            gravity = if (isReply) Gravity.END else Gravity.START
        }

        if (message.isNotEmpty()) {
            val messageTextView = TextView(this).apply {
                text = messageWithPeriod
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.WHITE)
                setPadding(20, 15, 20, 15)
                background = if (isReply) {
                    resources.getDrawable(R.drawable.received_message_bg, null)
                } else {
                    resources.getDrawable(R.drawable.sent_message_bg, null)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    width = (resources.displayMetrics.density * 200).toInt()
                }
                setOnLongClickListener {
                    showMessageOptionsPopup(this, key, message, isReply, isImage = false)
                    true
                }
            }
            messageLayout.addView(messageTextView)
        }

        imageBitmap?.let {
            val imageView = ImageView(this).apply {
                setImageBitmap(it)
                layoutParams = LinearLayout.LayoutParams(
                    (resources.displayMetrics.density * 250).toInt(),
                    (resources.displayMetrics.density * 200).toInt()
                ).apply {
                    setMargins(
                        (resources.displayMetrics.density * 100).toInt(),
                        (resources.displayMetrics.density * 5).toInt(),
                        (resources.displayMetrics.density * 10).toInt(),
                        (resources.displayMetrics.density * 15).toInt()
                    )
                    gravity = Gravity.CENTER
                }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnLongClickListener {
                    showMessageOptionsPopup(this, key, message, isReply, isImage = true)
                    true
                }
            }
            messageLayout.addView(imageView)
        }

        messagesPath().child(key).child("audioBase64").get()
            .addOnSuccessListener { snapshot ->
                val audioBase64 = snapshot.getValue(String::class.java)
                if (!audioBase64.isNullOrEmpty()) {
                    val playAudioBtn = TextView(this).apply {
                        text = "▶️ Play Voice Message"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setTextColor(Color.WHITE)
                        setPadding(20, 15, 20, 15)
                        background = if (isReply) {
                            resources.getDrawable(R.drawable.received_message_bg, null)
                        } else {
                            resources.getDrawable(R.drawable.sent_message_bg, null)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            width = (resources.displayMetrics.density * 250).toInt()
                        }
                        setOnClickListener {
                            try {
                                val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                                val tempFile = File.createTempFile("audio_", ".m4a", cacheDir)
                                tempFile.writeBytes(audioBytes)
                                val mediaPlayer = android.media.MediaPlayer().apply {
                                    setDataSource(tempFile.absolutePath)
                                    prepare()
                                    start()
                                }
                                Toast.makeText(this@FireReportResponseActivity, "Playing audio...", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(this@FireReportResponseActivity, "Error playing audio", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    messageLayout.addView(playAudioBtn)
                }
                val formattedDateTime = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date(timestamp))
                val timestampTextView = TextView(this).apply {
                    text = formattedDateTime
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.LTGRAY)
                    setPadding(10, 0, 10, 5)
                    gravity = if (isReply) Gravity.END else Gravity.START
                }
                messageLayout.addView(timestampTextView)
                binding.scrollContent.addView(messageLayout)
                binding.scrollView.visibility = android.view.View.VISIBLE
                binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }
            .addOnFailureListener {
                val formattedDateTime = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date(timestamp))
                val timestampTextView = TextView(this).apply {
                    text = formattedDateTime
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.LTGRAY)
                    setPadding(10, 0, 10, 5)
                    gravity = if (isReply) Gravity.END else Gravity.START
                }
                messageLayout.addView(timestampTextView)
                binding.scrollContent.addView(messageLayout)
                binding.scrollView.visibility = android.view.View.VISIBLE
                binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }
    }

    private fun showMessageOptionsPopup(
        anchorView: android.view.View,
        key: String,
        currentMessage: String,
        isReply: Boolean,
        isImage: Boolean
    ) {
        val popup = PopupMenu(this, anchorView)
        if (isImage) {
            popup.menuInflater.inflate(R.menu.message_options_delete_only, popup.menu)
        } else {
            popup.menuInflater.inflate(R.menu.message_options_menu, popup.menu)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_edit -> {
                    if (!isImage) {
                        showEditMessageDialog(key, currentMessage, isReply)
                        true
                    } else false
                }
                R.id.menu_delete -> {
                    confirmDeleteMessage(key)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showEditMessageDialog(key: String, currentMessage: String, isReply: Boolean) {
        val editText = EditText(this).apply {
            setText(currentMessage)
            setSelection(currentMessage.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Message")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) editMessage(key, newText, isReply)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteMessage(key: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Message")
            .setMessage("Are you sure you want to delete this message for everyone?")
            .setPositiveButton("Delete") { _, _ -> deleteMessage(key) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editMessage(key: String, newText: String, isReply: Boolean) {
        messagesPath().child(key).child("text").setValue(newText)
            .addOnCompleteListener { task ->
                Toast.makeText(this, if (task.isSuccessful) "Message updated" else "Failed to update message", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteMessage(key: String) {
        messagesPath().child(key).removeValue()
            .addOnCompleteListener { task ->
                Toast.makeText(this, if (task.isSuccessful) "Message deleted" else "Failed to delete message", Toast.LENGTH_SHORT).show()
            }
    }


    // change signature: add audioBase64 with default = ""
    private fun pushChatMessage(type: String, text: String, imageBase64: String, audioBase64: String = "") {
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        val msg = ChatMessage(
            type = type,
            text = text,
            imageBase64 = imageBase64.ifEmpty { null },
            audioBase64 = audioBase64.ifEmpty { null },   // <-- store audio here
            uid = uid,
            reporterName = intent.getStringExtra("NAME") ?: "",
            date = date,
            time = time,
            timestamp = now,
            isRead = false
        )

        messagesPath().push().setValue(msg).addOnCompleteListener { t ->
            if (t.isSuccessful) {
                mirrorReplyUnderStationNode(msg)
                Toast.makeText(this, "Message sent!", Toast.LENGTH_SHORT).show()
                base64Image = ""
                binding.messageInput.text.clear()
            } else {
                Toast.makeText(this, "Error sending message.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        if (::messagesListener.isInitialized) {
            messagesPath().removeEventListener(messagesListener)
        }
    }

    override fun onBackPressed() {
        if (fromNotification) {
            val intent = Intent(this, DashboardActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        } else {
            super.onBackPressed()
        }
    }
}
