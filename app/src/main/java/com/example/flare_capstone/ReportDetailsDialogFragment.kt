package com.example.flare_capstone

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.flare_capstone.databinding.DialogFireReportBinding

class ReportDetailsDialogFragment : DialogFragment() {

    private var _binding: DialogFireReportBinding? = null
    private val binding get() = _binding!!

    private var fire: FireReport? = null
    private var other: OtherEmergency? = null

    companion object {
        fun newInstance(report: FireReport): ReportDetailsDialogFragment {
            val f = ReportDetailsDialogFragment()
            f.fire = report
            return f
        }

        fun newInstance(report: OtherEmergency): ReportDetailsDialogFragment {
            val f = ReportDetailsDialogFragment()
            f.other = report
            return f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFireReportBinding.inflate(inflater, container, false)
        fillDetails()
        binding.btnClose.setOnClickListener { dismiss() }
        return binding.root
    }

    private fun fillDetails() {
        val f = fire
        val o = other

        if (f != null) {
            // Title stays fixed
            binding.title.text = "Fire Report"

            // Body
            binding.txtName.text = f.name
            binding.txtContact.text = f.contact

            // TYPE → use the actual value from Firebase, fallback to "Fire"
            val fireType = f.type?.takeIf { it.isNotBlank() } ?: "Fire"
            binding.txtType.text = fireType

            binding.txtDateTime.text = join(f.date, f.reportTime)
            binding.txtLocation.text = when {
                f.exactLocation.isNotBlank() -> f.exactLocation
                (f.latitude != 0.0 || f.longitude != 0.0) -> "${f.latitude}, ${f.longitude}"
                else -> "Unknown Location"
            }
            binding.txtStatus.text = f.status
            setStatusColor(f.status)
        } else if (o != null) {
            // Title stays one of the three long forms
            binding.title.text = when {
                o.category.equals("EMS", true) -> "Emergency Medical Services Report"
                o.category.equals("SMS", true) -> "SMS Report"
                else                           -> "Other Emergency Report"
            }

            binding.txtName.text = o.name.orEmpty()
            binding.txtContact.text = o.contact.orEmpty()

            // TYPE → prefer the actual "type" field saved in DB; fallback chain keeps you safe
            val otherType = when {
                !o.type.isNullOrBlank()          -> o.type!!.trim()          // e.g., "Patient Transport", "Flood", "SMS"
                !o.emergencyType.isNullOrBlank() -> o.emergencyType!!.trim() // legacy field some paths use
                o.category.equals("EMS", true)   -> "EMS"
                o.category.equals("SMS", true)   -> "SMS"
                else                             -> "Other"
            }
            binding.txtType.text = otherType

            binding.txtDateTime.text = join(o.date, o.reportTime)

            // SMS shows message text in the Location line (you already store message in 'location')
            binding.txtLocation.text = when {
                o.category.equals("SMS", true)   -> o.location.orEmpty()
                o.exactLocation.isNotBlank()     -> o.exactLocation
                else                             -> o.location.orEmpty()
            }

            binding.txtStatus.text = o.status.orEmpty()
            setStatusColor(o.status)
        }

        // Hide optional rows you’re not using
        binding.rowStartTime.visibility = View.GONE
        binding.rowHouses.visibility = View.GONE
    }



    private fun setStatusColor(statusRaw: String?) {
        val color = when (statusRaw?.trim()?.lowercase()) {
            "ongoing" -> Color.parseColor("#E00024")
            "completed" -> Color.parseColor("#0D9F00")
            "pending" -> Color.BLACK
            else -> Color.BLACK
        }
        binding.txtStatus.setTextColor(color)
    }

    private fun join(a: String?, b: String?): String =
        listOfNotNull(a?.takeIf { it.isNotBlank() }, b?.takeIf { it.isNotBlank() })
            .joinToString("  ")

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
