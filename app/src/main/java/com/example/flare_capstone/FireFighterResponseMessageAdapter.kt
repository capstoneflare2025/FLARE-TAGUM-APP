package com.example.flare_capstone

import android.annotation.SuppressLint
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.*

class FireFighterResponseMessageAdapter(
    private val stationList: MutableList<FireFighterStation>,
    private val onItemClick: (FireFighterStation) -> Unit
) : RecyclerView.Adapter<FireFighterResponseMessageAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileIcon: ShapeableImageView = itemView.findViewById(R.id.profileIcon)
        val fireStationName: TextView = itemView.findViewById(R.id.fireStationName)
        val uid: TextView = itemView.findViewById(R.id.uid)
        val timestamp: TextView = itemView.findViewById(R.id.timestamp)
        val unreadDot: View = itemView.findViewById(R.id.unreadDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fire_fighter_fire_station, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SimpleDateFormat")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stationList[position]

        // ✅ Fire station name
        holder.fireStationName.text = station.name

        // ✅ Format prefix (Reply: / You:)
        var displayText = station.lastMessage
        if (station.lastSender.equals("admin", ignoreCase = true)) {
            displayText = "Reply: ${station.lastMessage}"
        } else if (station.lastSender.equals(station.name, ignoreCase = true)) {
            displayText = "You: ${station.lastMessage}"
        }

        // ✅ Show last message or fallback
        holder.uid.text = if (displayText.isNotEmpty()) displayText else "No recent message"

        // ✅ Timestamp
        if (station.timestamp > 0) {
            val sdf = SimpleDateFormat("hh:mm a")
            holder.timestamp.text = sdf.format(Date(station.timestamp))
        } else {
            holder.timestamp.text = ""
        }

        // ✅ Profile image
        if (station.profileUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(station.profileUrl)
                .placeholder(R.drawable.station_logo)
                .into(holder.profileIcon)
        } else {
            holder.profileIcon.setImageResource(R.drawable.station_logo)
        }

        holder.unreadDot.visibility = View.GONE

        // Handle item click, and open FireFighterResponseActivity
        holder.itemView.setOnClickListener {
            // Create the intent to navigate to FireFighterResponseActivity
            val context = holder.itemView.context
            val intent = Intent(context, FireFighterResponseActivity::class.java)

            // Pass data (e.g. station name and ID) to the new activity
            intent.putExtra("STATION_NAME", station.name)
            intent.putExtra("STATION_ID", station.id)

            // Start the new activity
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = stationList.size

    fun updateData(newList: List<FireFighterStation>) {
        stationList.clear()
        stationList.addAll(newList)
        notifyDataSetChanged()
    }
}

