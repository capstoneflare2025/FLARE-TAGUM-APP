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

    // prevent flicker, but keep it fresh via live listeners
    private val lastPreviewByThread = mutableMapOf<String, Pair<Boolean /*fromUser*/, String /*text*/>>()

    // keep a live (ValueEventListener) for each threadId so previews update in real-time
    private val livePreviewListeners = mutableMapOf<String, Pair<Query, ValueEventListener>>()

    private val stationNodeByDisplayName = mapOf(
        "LaFilipina Fire Station" to "LaFilipinaFireStation",
        "Canocotan Fire Station"  to "CanocotanFireStation",
        "Mabini Fire Station"     to "MabiniFireStation",
        "LaFilipinaFireStation"   to "LaFilipinaFireStation",
        "CanocotanFireStation"    to "CanocotanFireStation",
        "MabiniFireStation"       to "MabiniFireStation"
    )

    private val fireReportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
    )

    private val otherReportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaOtherEmergency",
        "CanocotanFireStation"  to "CanocotanOtherEmergency",
        "MabiniFireStation"     to "MabiniOtherEmergency"
    )

    inner class ResponseMessageViewHolder(val binding: ItemFireStationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResponseMessageViewHolder {
        val binding = ItemFireStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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

        val reportNode = when (item.category?.lowercase(Locale.getDefault())) {
            "fire"  -> stationNode?.let { fireReportNodeByStationNode[it] }
            "other" -> stationNode?.let { otherReportNodeByStationNode[it] }
            else    -> null
        }

        val isUnread = !item.isRead

        // Station name style follows unread state
        holder.binding.fireStationName.apply {
            text = displayName
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }

        holder.binding.timestamp.text = formatTime(item.timestamp)

        // stable thread id
        val threadId = item.incidentId ?: item.uid

        // detach any old listener that might be associated with this recycled view
        (holder.itemView.tag as? String)?.let { oldTag ->
            if (oldTag != threadId) detachPreviewListener(oldTag)
        }
        holder.itemView.tag = threadId

        // no nodes? just show whatever the legacy responseMessage holds
        if (stationNode.isNullOrBlank() || reportNode.isNullOrBlank() || threadId.isNullOrBlank()) {
            applyPreview(holder, fromUser = false, text = item.responseMessage.orEmpty(), isUnread = isUnread)
            return
        }

        // show cached preview immediately (reduces flicker)
        lastPreviewByThread[threadId]?.let { cached ->
            applyPreview(holder, cached.first, cached.second, isUnread)
        } ?: applyPreview(holder, fromUser = false, text = "Loading…", isUnread = isUnread)

        // attach a live listener to the last message in this thread
        val q = FirebaseDatabase.getInstance().reference
            .child(stationNode).child(reportNode).child(threadId).child("messages")
            .orderByChild("timestamp")
            .limitToLast(1)

        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // view could be recycled; make sure it's still the same thread
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
                    fromUser = (type == "reply")

                    // If no text, derive a sensible label for media
                    if (text.isBlank()) {
                        val hasImage = !msg.child("imageBase64").getValue(String::class.java).isNullOrEmpty()
                        val hasAudio = !msg.child("audioBase64").getValue(String::class.java).isNullOrEmpty()
                        text = when {
                            hasImage && fromUser -> "Sent a photo."
                            hasImage             -> "Sent you a photo."
                            hasAudio && fromUser -> "Sent a voice message."
                            hasAudio             -> "Sent you a voice message."
                            else                 -> "" // nothing available
                        }
                    }
                }

                lastPreviewByThread[threadId] = fromUser to text
                applyPreview(holder, fromUser, text, isUnread)
            }

            override fun onCancelled(error: DatabaseError) { /* ignore */ }
        }

        // store and start listening
        livePreviewListeners[threadId]?.first?.removeEventListener(livePreviewListeners[threadId]!!.second)
        q.addValueEventListener(l)
        livePreviewListeners[threadId] = q to l

        // click → mark read + open
        holder.binding.root.setOnClickListener {
            val validThreadId = threadId
            if (validThreadId.isNullOrBlank()) {
                Toast.makeText(holder.itemView.context, "Thread id missing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (stationNode.isNullOrBlank() || reportNode.isNullOrBlank()) {
                Toast.makeText(holder.itemView.context, "Missing station/report node.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // mark messages read (thread)
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

            // optimistic UI
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                responseMessageList[idx].isRead = true
                notifyItemChanged(idx)
                onMarkedRead?.invoke()
            }

            val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                putExtra("UID", item.uid)
                putExtra("FIRE_STATION_NAME", displayName)
                putExtra("CONTACT", item.contact)
                putExtra("NAME", item.reporterName)
                putExtra("INCIDENT_ID", validThreadId)
                putExtra("STATION_NODE", stationNode)
                putExtra("REPORT_NODE", reportNode)
                putExtra("CATEGORY", item.category)
            }
            holder.itemView.context.startActivity(intent)
        }
    }

    // ensure we don’t leak listeners
    override fun onViewRecycled(holder: ResponseMessageViewHolder) {
        super.onViewRecycled(holder)
        (holder.itemView.tag as? String)?.let { detachPreviewListener(it) }
        holder.itemView.tag = null
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        // cleanup everything on adapter detach
        livePreviewListeners.values.forEach { (q, l) -> q.removeEventListener(l) }
        livePreviewListeners.clear()
    }

    private fun detachPreviewListener(threadId: String) {
        livePreviewListeners.remove(threadId)?.let { (q, l) ->
            q.removeEventListener(l)
        }
    }

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
            // keep title bold in sync too
            holder.binding.fireStationName.setTypeface(null, Typeface.BOLD)
            holder.binding.fireStationName.setTextColor(Color.BLACK)
        } else {
            holder.binding.uid.setTypeface(null, Typeface.NORMAL)
            holder.binding.uid.setTextColor(Color.parseColor("#757575"))
            holder.binding.unreadDot.visibility = android.view.View.GONE
            holder.binding.fireStationName.setTypeface(null, Typeface.NORMAL)
            holder.binding.fireStationName.setTextColor(Color.parseColor("#1A1A1A"))
        }
    }

    fun updateMessageIfNeeded(newMessage: ResponseMessage) {
        val existingIndex = responseMessageList.indexOfFirst {
            it.incidentId == newMessage.incidentId && it.fireStationName == newMessage.fireStationName
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
