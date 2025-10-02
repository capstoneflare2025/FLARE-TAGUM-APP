// StoriesAdapter.kt
package com.example.flare_capstone

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemStoryBinding
import com.example.flare_capstone.databinding.ItemStoryEmptyBinding

/**
 * What the FireReportResponseActivity expects in Intent:
 *  - "UID": String?
 *  - "FIRE_STATION_NAME": String
 *  - "CONTACT": String?
 *  - "NAME": String?
 *  - "INCIDENT_ID": String (required; we apply incidentId ?: uid here)
 *  - "STATION_NODE": String (ex: LaFilipinaFireStation)
 *  - "REPORT_NODE":  String (ex: LaFilipinaFireReport)
 */
data class StoryItem(
    val fireStationName: String,   // Human-friendly name (e.g., "La Filipina Fire Station")
    val incidentId: String?,       // May be null in legacy data
    val uid: String?,              // Fallback id for legacy threads
    val contact: String?,
    val reporterName: String?,
    val stationNode: String,       // Should be node-safe (e.g., "LaFilipinaFireStation")
    val reportNode: String         // Should be node-safe (e.g., "LaFilipinaFireReport")
)

class StoriesAdapter(
    private val items: MutableList<StoryItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VT_EMPTY = 0
        const val VT_ITEM  = 1
    }

    // Keep this message customizable
    private var emptyText: String = "No fire station available"

    // === SAME canonical maps you use in ResponseMessageAdapter ===
    private val stationNodeByDisplayName = mapOf(
        // La Filipina variants
        "La Filipina Fire Station" to "LaFilipinaFireStation",
        "LaFilipina Fire Station"  to "LaFilipinaFireStation", // tolerate missing space
        "LaFilipinaFireStation"    to "LaFilipinaFireStation",

        // Canocotan variants
        "Canocotan Fire Station"   to "CanocotanFireStation",
        "CanocotanFireStation"     to "CanocotanFireStation",

        // Mabini variants
        "Mabini Fire Station"      to "MabiniFireStation",
        "MabiniFireStation"        to "MabiniFireStation"
    )

    private val reportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
    )

    // === ViewHolders ===
    inner class StoryVH(val binding: ItemStoryBinding) : RecyclerView.ViewHolder(binding.root)
    inner class EmptyVH(val binding: ItemStoryEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    // === Adapter plumbing ===
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

                // 1) Use SAME thread id fallback as your ResponseMessageAdapter:
                val threadId = item.incidentId ?: item.uid
                if (threadId.isNullOrBlank()) {
                    Toast.makeText(ctx, "Thread id missing for this station.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 2) Normalize nodes in case the StoryItem came with display names or blanks:
                val stationNodeSafe: String = normalizeStationNode(item.stationNode, item.fireStationName)
                val reportNodeSafe: String  = normalizeReportNode(item.reportNode, stationNodeSafe)

                // 3) Guard against wrong/empty paths (prevents opening an empty thread by mistake):
                if (stationNodeSafe.isBlank() || reportNodeSafe.isBlank()) {
                    Toast.makeText(
                        ctx,
                        "Station/report node missing for ${item.fireStationName}.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                // 4) Launch exactly like ResponseMessageAdapter:
                val intent = Intent(ctx, FireReportResponseActivity::class.java).apply {
                    putExtra("UID", item.uid)
                    putExtra("FIRE_STATION_NAME", item.fireStationName)
                    putExtra("CONTACT", item.contact)
                    putExtra("NAME", item.reporterName)
                    putExtra("INCIDENT_ID", threadId)        // <-- fallback applied
                    putExtra("STATION_NODE", stationNodeSafe) // <-- normalized
                    putExtra("REPORT_NODE", reportNodeSafe)   // <-- normalized
                }
                ctx.startActivity(intent)
            }
        }
    }

    /** Public API: replace data and optionally change the empty text. */
    fun setData(newItems: List<StoryItem>, emptyMessage: String? = null) {
        emptyMessage?.let { emptyText = it }
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    // === Helpers ===

    /**
     * Accepts either a node-safe string or a human display name.
     * Returns the canonical station node (e.g., "LaFilipinaFireStation") or "" if unknown.
     */
    private fun normalizeStationNode(stationNodeOrDisplay: String?, displayName: String?): String {
        val raw = (stationNodeOrDisplay ?: "").trim()
        if (raw.isNotEmpty() && !raw.contains(' ')) {
            // Looks node-safe already (no spaces); trust it.
            return raw
        }
        val byName = stationNodeByDisplayName[displayName?.trim().orEmpty()]
        return byName ?: ""
    }

    /**
     * Accepts either a node-safe string or blanks; uses the station node to derive the report node.
     */
    private fun normalizeReportNode(reportNode: String?, stationNodeSafe: String): String {
        val raw = (reportNode ?: "").trim()
        if (raw.isNotEmpty() && !raw.contains(' ')) {
            // Looks node-safe already (no spaces); trust it.
            return raw
        }
        return reportNodeByStationNode[stationNodeSafe] ?: ""
    }
}
