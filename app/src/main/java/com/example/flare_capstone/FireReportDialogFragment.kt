package com.example.flare_capstone

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.*
import android.view.ViewGroup.LayoutParams
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.example.flare_capstone.databinding.DialogFireReportBinding

class FireReportDialogFragment(private val report: FireReport) : DialogFragment() {

    private var _binding: DialogFireReportBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFireReportBinding.inflate(inflater, container, false)

        // Header
        binding.title.text = "Fire Report"

        // Name / Contact
        binding.txtName.text = report.name.orEmpty()
        binding.txtContact.text = report.contact.orEmpty()

        // Type
        binding.txtType.text = report.type?.takeIf { it.isNotBlank() } ?: "Fire"

        // Date - Time
        val dateTime = listOfNotNull(
            report.date?.takeIf { it.isNotBlank() },
            report.reportTime?.takeIf { it.isNotBlank() }
        ).joinToString("  ")
        binding.txtDateTime.text = dateTime

        // Location (prefer exactLocation; else map link; else "Unknown")
        val locationText = when {
            !report.exactLocation.isNullOrBlank() -> report.exactLocation!!.trim()
            report.latitude != null && report.longitude != null ->
                "https://www.google.com/maps?q=${report.latitude},${report.longitude}"
            else -> "Unknown Location"
        }
        binding.txtLocation.text = locationText
        binding.txtLocation.movementMethod = LinkMovementMethod.getInstance() // make autoLink clickable

        // Status + simple color
        val status = report.status?.trim().orEmpty()
        binding.txtStatus.text = status
        binding.txtStatus.setTextColor(
            when (status.lowercase()) {
                "Ongoing"   -> Color.parseColor("#E00024") // red
                "Completed" -> Color.parseColor("#4CAF50") // green
                else        -> Color.parseColor("#2196F3") // blue default
            }
        )

        // Close
        binding.btnClose.setOnClickListener { dismiss() }

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
