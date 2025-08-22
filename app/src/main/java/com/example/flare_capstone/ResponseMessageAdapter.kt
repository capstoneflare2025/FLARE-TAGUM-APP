package com.example.flare_capstone

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemFireStationBinding
import com.google.firebase.database.FirebaseDatabase

class ResponseMessageAdapter(
    private val responseMessageList: MutableList<ResponseMessage>
) : RecyclerView.Adapter<ResponseMessageAdapter.ResponseMessageViewHolder>() {

    private val stationNodeByDisplayName = mapOf(
        "La Filipina Fire Station" to "LaFilipinaFireStation",
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

    override fun onBindViewHolder(holder: ResponseMessageViewHolder, position: Int) {
        val item = responseMessageList[position]

        val displayName = item.fireStationName ?: "Unknown Fire Station"
        val stationNode = stationNodeByDisplayName[displayName] ?: "MabiniFireStation"
        val reportNode  = reportNodeByStationNode[stationNode] ?: "MabiniFireReport"

        holder.binding.apply {
            fireStationName.text = displayName
            uid.text = "Reply: ${item.responseMessage ?: ""}"

            root.setCardBackgroundColor(
                if (item.isRead) Color.parseColor("#E87F2E") else Color.parseColor("#F5F206")
            )

            root.setOnClickListener {
                val incidentId = item.incidentId ?: return@setOnClickListener
                // Optional: mark whole thread read
                FirebaseDatabase.getInstance().reference
                    .child(stationNode).child(reportNode).child(incidentId).child("messages")
                    .get()
                    .addOnSuccessListener { snap ->
                        val updates = HashMap<String, Any?>()
                        snap.children.forEach { m -> updates["${m.key}/isRead"] = true }
                        if (updates.isNotEmpty()) {
                            FirebaseDatabase.getInstance().reference
                                .child(stationNode).child(reportNode).child(incidentId).child("messages")
                                .updateChildren(updates)
                        }
                    }

                val idx = holder.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) {
                    responseMessageList[idx].isRead = true
                    notifyItemChanged(idx)
                }

                val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                    putExtra("UID", item.uid)
                    putExtra("FIRE_STATION_NAME", displayName)
                    putExtra("CONTACT", item.contact)
                    putExtra("NAME", item.reporterName)
                    putExtra("INCIDENT_ID", item.incidentId)
                    putExtra("STATION_NODE", stationNode)
                    putExtra("REPORT_NODE", reportNode)
                }
                holder.itemView.context.startActivity(intent)
            }
        }
    }

    fun updateMessageIfNeeded(newMessage: ResponseMessage) {
        val existingIndex = responseMessageList.indexOfFirst {
            it.incidentId == newMessage.incidentId && it.fireStationName == newMessage.fireStationName
        }
        if (existingIndex != -1) {
            val oldTs = responseMessageList[existingIndex].timestamp ?: 0L
            val newTs = newMessage.timestamp ?: 0L
            if (newTs > oldTs) {
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
