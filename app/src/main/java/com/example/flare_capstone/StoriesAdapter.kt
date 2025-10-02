package com.example.flare_capstone

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemStoryBinding
import com.example.flare_capstone.databinding.ItemStoryEmptyBinding
import java.util.Locale

data class StoryItem(
    val fireStationName: String
)

class StoriesAdapter(
    private val items: MutableList<StoryItem>,
    private val onStationSelected: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VT_EMPTY = 0
        const val VT_ITEM = 1
    }

    private var emptyText: String = "No fire station available"
    private var selectedStation: String = "All Fire Station" // ✅ default

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

        // Name
        holder.binding.stationName.text = item.fireStationName

        // ✅ Highlight if selected
        if (item.fireStationName == selectedStation) {
            holder.binding.stationName.setTypeface(null, Typeface.BOLD)
            holder.binding.stationName.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.black)
            )
        } else {
            holder.binding.stationName.setTypeface(null, Typeface.NORMAL)
            holder.binding.stationName.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.gray)
            )
        }

        // ✅ Clickable
        holder.binding.root.setOnClickListener {
            selectedStation = item.fireStationName
            notifyDataSetChanged()
            onStationSelected(selectedStation)
        }
    }

    fun setData(newItems: List<StoryItem>, emptyMessage: String? = null) {
        emptyMessage?.let { emptyText = it }
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Always show All Fire Station + 3 fire stations, filterable by search query */
    fun setDefaultStations(query: String = "") {
        val stations = listOf(
            "All Fire Station",
            "LaFilipina Fire Station",
            "Canocotan Fire Station",
            "Mabini Fire Station"
        )
        val filtered = if (query.isBlank()) {
            stations
        } else {
            stations.filter { it.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT)) }
        }
        val storyItems = filtered.map { name -> StoryItem(fireStationName = name) }
        setData(storyItems, if (storyItems.isEmpty()) "No matching stations" else null)
    }
}
