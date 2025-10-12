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

    private val lastPreviewByThread = mutableMapOf<String, Pair<Boolean, String>>()
    private val livePreviewListeners = mutableMapOf<String, Pair<Query, ValueEventListener>>()

    private val STATION_NODE = "TagumCityCentralFireStation"
    private val FIRE_NODE  = "AllReport/FireReport"
    private val OTHER_NODE = "AllReport/OtherEmergencyReport"
    private val EMS_NODE   = "AllReport/EmergencyMedicalServicesReport"
    private val SMS_NODE   = "AllReport/SmsReport"

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

    private fun prettyStation(s: String?): String {
        val raw = s ?: return "Tagum City Central Fire Station"
        if (raw.contains(' ')) return raw
        return raw.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }

    override fun onBindViewHolder(holder: ResponseMessageViewHolder, position: Int) {
        val item = responseMessageList[position]

        val displayName = prettyStation(item.fireStationName)
        val isUnread = !item.isRead

        holder.binding.fireStationName.apply {
            text = displayName
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }
        holder.binding.timestamp.text = formatTime(item.timestamp)

        val threadId = item.incidentId ?: item.uid

        (holder.itemView.tag as? String)?.let { old ->
            if (old != threadId) detachPreviewListener(old)
        }
        holder.itemView.tag = threadId

        if (threadId.isNullOrBlank()) {
            applyPreview(holder, false, item.responseMessage.orEmpty(), isUnread)
            return
        }

        val reportNode = when (item.category?.lowercase(Locale.getDefault())) {
            "fire"  -> FIRE_NODE
            "other" -> OTHER_NODE
            "ems"   -> EMS_NODE
            "sms"   -> SMS_NODE
            else    -> FIRE_NODE
        }

        lastPreviewByThread[threadId]?.let { (fromUser, text) ->
            applyPreview(holder, fromUser, text, isUnread)
        } ?: applyPreview(holder, false, "Loading…", isUnread)

        val q = FirebaseDatabase.getInstance().reference
            .child(STATION_NODE).child(reportNode).child(threadId).child("messages")
            .orderByChild("timestamp")
            .limitToLast(1)

        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) return
                if (holder.itemView.tag != threadId) return

                var text = ""
                var fromUser = false

                snapshot.children.firstOrNull()?.let { msg ->
                    text = msg.child("text").getValue(String::class.java)
                        ?: msg.child("message").getValue(String::class.java)
                                ?: msg.child("body").getValue(String::class.java)
                                ?: ""

                    val type = msg.child("type").getValue(String::class.java)
                        ?.trim()?.lowercase(Locale.getDefault())
                    fromUser = (type == "reply")

                    if (text.isBlank()) {
                        val hasImage = !msg.child("imageBase64").getValue(String::class.java).isNullOrEmpty()
                        val hasAudio = !msg.child("audioBase64").getValue(String::class.java).isNullOrEmpty()
                        text = when {
                            hasImage && fromUser -> "Sent a photo."
                            hasImage             -> "Sent you a photo."
                            hasAudio && fromUser -> "Sent a voice message."
                            hasAudio             -> "Sent you a voice message."
                            else                 -> ""
                        }
                    }
                }

                lastPreviewByThread[threadId] = fromUser to text
                applyPreview(holder, fromUser, text, isUnread)
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        livePreviewListeners[threadId]?.first?.removeEventListener(livePreviewListeners[threadId]!!.second)
        q.addValueEventListener(l)
        livePreviewListeners[threadId] = q to l

        holder.binding.root.setOnClickListener {
            val validThreadId = threadId
            if (validThreadId.isNullOrBlank()) {
                Toast.makeText(holder.itemView.context, "Thread id missing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            FirebaseDatabase.getInstance().reference
                .child(STATION_NODE).child(reportNode).child(validThreadId).child("messages")
                .get()
                .addOnSuccessListener { snap ->
                    val updates = HashMap<String, Any?>()
                    snap.children.forEach { m -> updates["${m.key}/isRead"] = true }
                    if (updates.isNotEmpty()) {
                        FirebaseDatabase.getInstance().reference
                            .child(STATION_NODE).child(reportNode).child(validThreadId)
                            .child("messages").updateChildren(updates)
                    }
                }

            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                responseMessageList[idx].isRead = true
                notifyItemChanged(idx)
                onMarkedRead?.invoke()
            }

            val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                putExtra("UID", item.uid)
                putExtra("FIRE_STATION_NAME", "Tagum City Central Fire Station")
                putExtra("CONTACT", item.contact)
                putExtra("NAME", item.reporterName)
                putExtra("INCIDENT_ID", validThreadId)
                putExtra("STATION_NODE", STATION_NODE)
                putExtra("REPORT_NODE", reportNode) // e.g. AllReport/FireReport
                putExtra("CATEGORY", item.category)
            }
            holder.itemView.context.startActivity(intent)
        }
    }

    override fun onViewRecycled(holder: ResponseMessageViewHolder) {
        super.onViewRecycled(holder)
        (holder.itemView.tag as? String)?.let { detachPreviewListener(it) }
        holder.itemView.tag = null
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        livePreviewListeners.values.forEach { (q, l) -> q.removeEventListener(l) }
        livePreviewListeners.clear()
    }

    private fun detachPreviewListener(threadId: String) {
        livePreviewListeners.remove(threadId)?.let { (q, l) -> q.removeEventListener(l) }
    }

    private fun applyPreview(
        holder: ResponseMessageViewHolder,
        fromUser: Boolean,
        text: String,
        isUnread: Boolean
    ) {
        // If the list is for the station operator, you can switch to:
        // val prefix = if (fromUser) "Reporter: " else "Station: "
        val prefix = if (fromUser) "You: " else "Reply: "
        holder.binding.uid.text = prefix + text

        if (isUnread) {
            holder.binding.uid.setTypeface(null, Typeface.BOLD)
            holder.binding.uid.setTextColor(Color.BLACK)
            holder.binding.unreadDot.visibility = android.view.View.VISIBLE
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

    override fun getItemCount(): Int = responseMessageList.size
}
