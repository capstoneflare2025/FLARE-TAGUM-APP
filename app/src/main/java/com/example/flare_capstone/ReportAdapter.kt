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
        val txtAlert: TextView = view.findViewById(R.id.txtAlert)
        val txtStatus: TextView = view.findViewById(R.id.txtStatus)

        init {
            view.setOnClickListener {
                val item = reports[adapterPosition]
                when (item) {
                    is FireReport -> listener.onFireReportClick(item)
                    is OtherEmergency -> listener.onOtherEmergencyClick(item)
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
        when (val item = reports[position]) {
            is FireReport -> {
                holder.txtDate.text = item.date
                holder.txtAlert.text = item.alertLevel
                holder.txtStatus.text = item.status
                when (item.status.lowercase()) {
                    "ongoing" -> holder.txtStatus.setTextColor(Color.parseColor("#E00024"))
                    "completed" -> holder.txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                    else -> holder.txtStatus.setTextColor(Color.parseColor("#F5F206"))
                }
            }
            is OtherEmergency -> {
                holder.txtDate.text = item.date
                holder.txtAlert.text = item.emergencyType
                holder.txtStatus.text = item.status
                when (item.status.lowercase()) {
                    "ongoing" -> holder.txtStatus.setTextColor(Color.parseColor("#E00024"))
                    "completed" -> holder.txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                    else -> holder.txtStatus.setTextColor(Color.parseColor("#2196F3"))
                }
            }
        }
    }

    override fun getItemCount(): Int = reports.size
}
