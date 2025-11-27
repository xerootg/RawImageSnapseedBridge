package com.raw2dng

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog displaying open source licenses for all libraries used in the app.
 */
class LicensesDialog : DialogFragment() {

    companion object {
        const val TAG = "LicensesDialog"
        
        fun newInstance(): LicensesDialog {
            return LicensesDialog()
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_licenses, null)
        
        val btnClose = view.findViewById<Button>(R.id.btnClose)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
        
        btnClose.setOnClickListener {
            dismiss()
        }
        
        return dialog
    }
}
