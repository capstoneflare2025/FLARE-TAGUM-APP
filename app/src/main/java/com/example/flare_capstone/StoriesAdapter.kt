package com.example.flare_capstone

import android.content.Intent
import android.widget.Toast
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemStoryBinding
import com.example.flare_capstone.databinding.ItemStoryEmptyBinding

data class StoryItem(
    val fireStationName: String,
    val incidentId: String?,
    val uid: String?,
    val contact: String?,
    val reporterName: String?,
    val stationNode: String,   // e.g. LaFilipinaFireStation
    val reportNode: String?    // optional; derive if null
)

class StoriesAdapter(
    private val items: MutableList<StoryItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VT_EMPTY = 0
        const val VT_ITEM  = 1
    }

    private var emptyText: String = "No fire station available"

    inner class StoryVH(val binding: ItemStoryBinding) : RecyclerView.ViewHolder(binding.root)
    inner class EmptyVH(val binding: ItemStoryEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (items.isEmpty()) VT_EMPTY else VT_ITEM

    override fun getItemCount(): Int =
        if (items.isEmpty()) 1 else items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == VT_EMPTY) {
            EmptyVH(ItemStoryEmptyBinding.inflate(inf, parent, false))
        } else {
            StoryVH(ItemStoryBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is EmptyVH) {
            holder.binding.emptyText.text = emptyText
            return
        }
        holder as StoryVH
        val item = items[position]

        holder.binding.apply {
            stationName.text = item.fireStationName
            root.setOnClickListener {
                val ctx = holder.itemView.context

                val incidentId = item.incidentId ?: item.uid
                val stationNode = item.stationNode
                val reportNode  = item.reportNode ?: deriveReportNodeFromStation(stationNode)

                if (incidentId.isNullOrBlank()) {
                    Toast.makeText(ctx, "Missing conversation id.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (stationNode.isBlank() || reportNode.isNullOrBlank()) {
                    Toast.makeText(ctx, "Unknown station for this message.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val intent = Intent(ctx, FireReportResponseActivity::class.java).apply {
                    putExtra("UID", item.uid)
                    putExtra("FIRE_STATION_NAME", item.fireStationName)
                    putExtra("CONTACT", item.contact)
                    putExtra("NAME", item.reporterName)
                    putExtra("INCIDENT_ID", incidentId)
                    putExtra("STATION_NODE", stationNode)
                    putExtra("REPORT_NODE", reportNode)
                }
                ctx.startActivity(intent)
            }
        }
    }

    private fun deriveReportNodeFromStation(stationNode: String): String? =
        if (stationNode.endsWith("FireStation")) {
            stationNode.replace("FireStation", "FireReport")
        } else null

    fun setData(newItems: List<StoryItem>, emptyMessage: String? = null) {
        emptyMessage?.let { emptyText = it }
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
