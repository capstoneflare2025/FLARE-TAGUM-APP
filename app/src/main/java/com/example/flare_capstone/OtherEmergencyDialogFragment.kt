package com.example.flare_capstone

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class OtherEmergencyDialogFragment(private val report: OtherEmergency) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val message = """
            Type: ${report.emergencyType}
            Name: ${report.name}
            Contact: ${report.contact}
            Date: ${report.date}
            Time: ${report.reportTime}
            Location: ${report.exactLocation}
            Maps Link: ${report.location}
            Fire Station: ${report.fireStationName}
        """.trimIndent()

        return AlertDialog.Builder(requireContext())
            .setTitle("Other Emergency Report")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
    }
}
