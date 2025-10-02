package com.example.flare_capstone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemFireStationBinding
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResponseMessageAdapter(
    private val responseMessageList: MutableList<ResponseMessage>,
    private val onMarkedRead: (() -> Unit)? = null
) : RecyclerView.Adapter<ResponseMessageAdapter.ResponseMessageViewHolder>() {

    /** Cache latest preview per thread to prevent flicker on scroll */
    private val lastPreviewByThread =
        mutableMapOf<String, Pair<Boolean /*fromUser*/, String /*text*/>>()

    // Station fallback resolver
    private val stationNodeByDisplayName = mapOf(
        "LaFilipina Fire Station" to "LaFilipinaFireStation",
        "Canocotan Fire Station"  to "CanocotanFireStation",
        "Mabini Fire Station"     to "MabiniFireStation",
        "LaFilipinaFireStation"   to "LaFilipinaFireStation",
        "CanocotanFireStation"    to "CanocotanFireStation",
        "MabiniFireStation"       to "MabiniFireStation"
    )

    // Fire nodes
    private val fireReportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
    )

    // Other emergency nodes
    private val otherReportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaOtherEmergency",
        "CanocotanFireStation"  to "CanocotanOtherEmergency",
        "MabiniFireStation"     to "MabiniOtherEmergency"
    )

    inner class ResponseMessageViewHolder(val binding: ItemFireStationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResponseMessageViewHolder {
        val binding =
            ItemFireStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ResponseMessageViewHolder(binding)
    }

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private fun formatTime(ts: Long?): String {
        if (ts == null || ts <= 0L) return ""
        val millis = if (ts < 1_000_000_000_000L) ts * 1000 else ts
        return timeFmt.format(Date(millis))
    }

    override fun onBindViewHolder(holder: ResponseMessageViewHolder, position: Int) {
        val item = responseMessageList[position]
        val displayName = item.fireStationName ?: "Unknown Fire Station"

        val stationNode = item.stationNode ?: stationNodeByDisplayName[displayName]

        // ✅ pick FireReport vs OtherEmergency depending on category
        val reportNode = when (item.category?.lowercase(Locale.getDefault())) {
            "fire"  -> stationNode?.let { fireReportNodeByStationNode[it] }
            "other" -> stationNode?.let { otherReportNodeByStationNode[it] }
            else    -> null
        }

        val isUnread = !item.isRead

        // Title + time
        holder.binding.fireStationName.apply {
            text = displayName
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        holder.binding.timestamp.text = formatTime(item.timestamp)

        // Consistent thread id
        val threadId = item.incidentId ?: item.uid

        // Fallback preview if no nodes
        if (stationNode.isNullOrBlank() || reportNode.isNullOrBlank() || threadId.isNullOrBlank()) {
            applyPreview(
                holder,
                fromUser = false,
                text = item.responseMessage.orEmpty(),
                isUnread = isUnread
            )
        } else {
            // Use cache immediately
            val cached = lastPreviewByThread[threadId]
            if (cached != null) {
                applyPreview(holder, cached.first, cached.second, isUnread)
            } else {
                applyPreview(holder, fromUser = false, text = "Loading…", isUnread = isUnread)
            }

            // Tag view with threadId to avoid recycling mismatch
            holder.itemView.tag = threadId

            // Always fetch true latest message
            FirebaseDatabase.getInstance().reference
                .child(stationNode).child(reportNode).child(threadId).child("messages")
                .orderByChild("timestamp")
                .limitToLast(1)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) return
                        if (holder.itemView.tag != threadId) return

                        var text = ""
                        var fromUser = false

                        val msg = snapshot.children.firstOrNull()
                        if (msg != null) {
                            text = msg.child("text").getValue(String::class.java)
                                ?: msg.child("message").getValue(String::class.java)
                                        ?: msg.child("body").getValue(String::class.java)
                                        ?: ""

                            val type = msg.child("type").getValue(String::class.java)
                                ?.trim()?.lowercase(Locale.getDefault())
                            fromUser = type == "reply" // reporter/user message
                        }

                        lastPreviewByThread[threadId] = fromUser to text
                        applyPreview(holder, fromUser, text, isUnread)
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
        }

        // Click: mark read + open thread
        holder.binding.root.setOnClickListener {
            val validThreadId = threadId
            if (validThreadId.isNullOrBlank()) {
                Toast.makeText(holder.itemView.context, "Thread id missing.", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            if (stationNode.isNullOrBlank() || reportNode.isNullOrBlank()) {
                Toast.makeText(
                    holder.itemView.context,
                    "Missing station/report node.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Mark as read in Firebase
            FirebaseDatabase.getInstance().reference
                .child(stationNode).child(reportNode).child(validThreadId).child("messages")
                .get()
                .addOnSuccessListener { snap ->
                    val updates = HashMap<String, Any?>()
                    snap.children.forEach { m -> updates["${m.key}/isRead"] = true }
                    if (updates.isNotEmpty()) {
                        FirebaseDatabase.getInstance().reference
                            .child(stationNode).child(reportNode).child(validThreadId)
                            .child("messages")
                            .updateChildren(updates)
                    }
                }

            // Optimistic update
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                responseMessageList[idx].isRead = true
                notifyItemChanged(idx)
                onMarkedRead?.invoke()
            }

            // Open response activity
            val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                putExtra("UID", item.uid)
                putExtra("FIRE_STATION_NAME", displayName)
                putExtra("CONTACT", item.contact)
                putExtra("NAME", item.reporterName)
                putExtra("INCIDENT_ID", validThreadId)
                putExtra("STATION_NODE", stationNode)
                putExtra("REPORT_NODE", reportNode)
                putExtra("CATEGORY", item.category) // 🔑 pass category
            }
            holder.itemView.context.startActivity(intent)
        }
    }

    /** Apply subtitle + unread visuals */
    private fun applyPreview(
        holder: ResponseMessageViewHolder,
        fromUser: Boolean,
        text: String,
        isUnread: Boolean
    ) {
        val prefix = if (fromUser) "You: " else "Reply: "
        holder.binding.uid.text = prefix + text

        if (isUnread) {
            holder.binding.uid.setTypeface(null, Typeface.BOLD)
            holder.binding.uid.setTextColor(Color.BLACK)
            holder.binding.unreadDot.visibility = android.view.View.VISIBLE
        } else {
            holder.binding.uid.setTypeface(null, Typeface.NORMAL)
            holder.binding.uid.setTextColor(Color.parseColor("#757575"))
            holder.binding.unreadDot.visibility = android.view.View.GONE
        }
    }

    fun updateMessageIfNeeded(newMessage: ResponseMessage) {
        val existingIndex = responseMessageList.indexOfFirst {
            it.incidentId == newMessage.incidentId &&
                    it.fireStationName == newMessage.fireStationName
        }
        if (existingIndex != -1) {
            val oldTs = responseMessageList[existingIndex].timestamp ?: 0L
            val newTs = newMessage.timestamp ?: 0L
            if (newTs > oldTs || (responseMessageList[existingIndex].isRead != newMessage.isRead)) {
                responseMessageList[existingIndex] = newMessage
                notifyItemChanged(existingIndex)
            }
        } else {
            responseMessageList.add(0, newMessage)
            notifyItemInserted(0)
        }
    }

    override fun getItemCount(): Int = responseMessageList.size
}
