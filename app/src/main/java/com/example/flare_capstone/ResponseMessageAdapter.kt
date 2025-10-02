package com.example.flare_capstone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemFireStationBinding
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResponseMessageAdapter(
    private val responseMessageList: MutableList<ResponseMessage>,
    private val onMarkedRead: (() -> Unit)? = null
) : RecyclerView.Adapter<ResponseMessageAdapter.ResponseMessageViewHolder>() {

    // Fallback resolver only if stationNode is missing on the item
    private val stationNodeByDisplayName = mapOf(
        "LaFilipina Fire Station" to "LaFilipinaFireStation",
        "Canocotan Fire Station"   to "CanocotanFireStation",
        "Mabini Fire Station"      to "MabiniFireStation",
        "LaFilipinaFireStation"    to "LaFilipinaFireStation",
        "CanocotanFireStation"     to "CanocotanFireStation",
        "MabiniFireStation"        to "MabiniFireStation"
    )

    private val reportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
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

        // Prefer the exact node coming from the item; only try to map by display name if missing
        val stationNode = item.stationNode ?: stationNodeByDisplayName[displayName]
        if (stationNode.isNullOrBlank()) {
            holder.binding.root.setOnClickListener {
                Toast.makeText(holder.itemView.context, "Unknown station for this message.", Toast.LENGTH_SHORT).show()
            }
            bindRow(holder, displayName, item, isUnread = !item.isRead)
            return
        }

        val reportNode = reportNodeByStationNode[stationNode]
        if (reportNode.isNullOrBlank()) {
            holder.binding.root.setOnClickListener {
                Toast.makeText(holder.itemView.context, "Missing report node for $stationNode.", Toast.LENGTH_SHORT).show()
            }
            bindRow(holder, displayName, item, isUnread = !item.isRead)
            return
        }

        val isUnread = !item.isRead
        bindRow(holder, displayName, item, isUnread)

        holder.binding.root.setOnClickListener {
            // Prefer incidentId; fallback to uid (kept for older data)
            val threadId = item.incidentId ?: item.uid
            if (threadId.isNullOrBlank()) {
                Toast.makeText(holder.itemView.context, "Thread id missing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Mark ALL messages in that thread as read
            FirebaseDatabase.getInstance().reference
                .child(stationNode).child(reportNode).child(threadId).child("messages")
                .get()
                .addOnSuccessListener { snap ->
                    val updates = HashMap<String, Any?>()
                    snap.children.forEach { m -> updates["${m.key}/isRead"] = true }
                    if (updates.isNotEmpty()) {
                        FirebaseDatabase.getInstance().reference
                            .child(stationNode).child(reportNode).child(threadId).child("messages")
                            .updateChildren(updates)
                    }
                }

            // Flip current row state immediately
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                responseMessageList[idx].isRead = true
                notifyItemChanged(idx)
                onMarkedRead?.invoke()
            }

            // Open the correct conversation
            val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                putExtra("UID", item.uid)
                putExtra("FIRE_STATION_NAME", displayName)
                putExtra("CONTACT", item.contact)
                putExtra("NAME", item.reporterName)
                putExtra("INCIDENT_ID", threadId)     // <-- use threadId we validated
                putExtra("STATION_NODE", stationNode) // <-- exact node
                putExtra("REPORT_NODE", reportNode)   // <-- exact node
            }
            holder.itemView.context.startActivity(intent)
        }
    }

    private fun bindRow(
        holder: ResponseMessageViewHolder,
        displayName: String,
        item: ResponseMessage,
        isUnread: Boolean
    ) {
        holder.binding.apply {
            // Title
            fireStationName.text = displayName
            fireStationName.setTypeface(null, Typeface.BOLD)
            fireStationName.setTextColor(Color.BLACK)

            // Subtitle/snippet
            uid.text = "Reply: ${item.responseMessage.orEmpty()}"
            if (isUnread) {
                uid.setTypeface(null, Typeface.BOLD)
                uid.setTextColor(Color.BLACK)
                unreadDot.visibility = View.VISIBLE
            } else {
                uid.setTypeface(null, Typeface.NORMAL)
                uid.setTextColor(Color.parseColor("#757575"))
                unreadDot.visibility = View.GONE
            }

            // Right timestamp
            timestamp.text = formatTime(item.timestamp)
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
