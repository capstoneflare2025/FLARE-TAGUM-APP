package com.example.flare_capstone

import android.app.Dialog
import android.os.Bundle
import android.view.*
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogFireReportBinding.inflate(inflater, container, false)

        binding.txtName.text = report.name
        binding.txtContact.text = report.contact
        binding.txtAlertLevel.text = report.alertLevel
        binding.txtDate.text = "${report.date} ${report.reportTime}"
        binding.txtStartTime.text = report.fireStartTime
        binding.txtHouses.text = report.numberOfHousesAffected.toString()
        binding.txtLocation.text = report.exactLocation
        binding.txtStatus.text = report.status

        binding.btnClose.setOnClickListener { dismiss() }

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
