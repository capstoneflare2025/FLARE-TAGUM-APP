package com.example.flare_capstone

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ReportAdapter(
    private val reports: List<Any>,
    private val listener: OnItemClickListener
) : RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    interface OnItemClickListener {
        fun onFireReportClick(report: FireReport)
        fun onOtherEmergencyClick(report: OtherEmergency)
    }

    inner class ReportViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtDate: TextView = view.findViewById(R.id.txtDate)
        val txtType: TextView = view.findViewById(R.id.txtAlert)   // middle column (id kept from layout)
        val txtStatus: TextView = view.findViewById(R.id.txtStatus)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                when (val item = reports[pos]) {
                    is FireReport      -> listener.onFireReportClick(item)
                    is OtherEmergency  -> listener.onOtherEmergencyClick(item)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fire_report, parent, false)
        return ReportViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        // Reset to avoid recycled garbage
        holder.txtDate.text = ""
        holder.txtType.text = ""
        holder.txtStatus.text = ""
        holder.txtStatus.setTextColor(Color.BLACK)

        when (val item = reports[position]) {
            is FireReport -> {
                // DATE (use your preferred format; here the stored string)
                holder.txtDate.text = item.date

                // TYPE -> Always "Fire" (do NOT read any alarm/type field)
                holder.txtType.text = "Fire"

                // STATUS
                val status = item.status ?: ""
                holder.txtStatus.text = status
                setStatusColor(holder.txtStatus, status)
            }

            is OtherEmergency -> {
                // DATE
                holder.txtDate.text = item.date

                // TYPE -> EMS/SMS/OtherEmergencyType
                val type = item.emergencyType?.takeIf { it.isNotBlank() } ?: "Other"
                holder.txtType.text = type

                // STATUS (if your OtherEmergency has status; else leave blank)
                val status = item.status ?: ""   // if your model lacks this, remove these two lines
                holder.txtStatus.text = status
                setStatusColor(holder.txtStatus, status)
            }
        }
    }

    override fun getItemCount(): Int = reports.size

    private fun setStatusColor(tv: TextView, statusRaw: String) {
        when (statusRaw.trim().lowercase()) {
            "Ongoing"   -> tv.setTextColor(Color.parseColor("#E00024")) // red
            "Completed" -> tv.setTextColor(Color.parseColor("#4CAF50")) // green
            "Pending"   -> tv.setTextColor(Color.parseColor("#F5F206")) // yellow
            else        -> tv.setTextColor(Color.parseColor("#2196F3")) // default blue for unknowns
        }
    }
}
