package com.raw2dng

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.CheckBox
import android.widget.ExpandableListView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog fragment for selecting RAW file types organized by manufacturer.
 * Displays an expandable list with manufacturers as groups and their RAW formats as children.
 */
class RawTypesDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "RawTypesDialogFragment"

        /**
         * Manufacturer data: display name to list of extensions.
         * Extensions are lowercase for consistency with file matching.
         * 
         * Note: Some extensions like RAW are used by multiple manufacturers,
         * so they appear under the most common one or are listed separately.
         */
        val MANUFACTURERS: LinkedHashMap<String, List<String>> = linkedMapOf(
            "Canon" to listOf("cr2", "cr3", "crw"),
            "Nikon" to listOf("nef", "nrw"),
            "Sony" to listOf("arw", "sr2", "srf"),
            "Fujifilm" to listOf("raf"),
            "Olympus / OM System" to listOf("orf", "ori"),
            "Panasonic / Lumix" to listOf("rw2"),
            // DNG is excluded as it's an output format. you don't need this tool to convert DNG to DNG
            // dng_sdk crashes when trying to convert DNG to DNG anyway.
            "Pentax" to listOf("pef"),
            "Samsung" to listOf("srw"),
            "Hasselblad" to listOf("3fr", "fff"),
            "Phase One / Leaf" to listOf("iiq", "mos"),
            "Kodak" to listOf("dcr", "k25", "kdc"),
            "Leica" to listOf("rwl"),
            "Epson" to listOf("erf"),
            "Mamiya" to listOf("mef"),
            "Sigma" to listOf("x3f"),
            "Contax" to listOf("raw"),
            "Konica Minolta" to listOf("mrw"),
            "GoPro" to listOf("gpr")
        )

        /**
         * Get all unique extensions from all manufacturers as a flat array.
         * Deduplicates extensions that appear under multiple manufacturers.
         * This is used to maintain compatibility with existing preferences storage.
         */
        fun getAllExtensions(): Array<String> {
            return MANUFACTURERS.values.flatten().distinct().sorted().toTypedArray()
        }

        /**
         * Get manufacturer name for a given extension, or null if not found.
         */
        fun getManufacturerForExtension(ext: String): String? {
            return MANUFACTURERS.entries.find { ext.lowercase() in it.value }?.key
        }

        fun newInstance(): RawTypesDialogFragment {
            return RawTypesDialogFragment()
        }
    }

    // Selection state: extension -> isSelected
    private lateinit var selectionState: MutableMap<String, Boolean>
    
    // Callback for when selection is confirmed
    var onSelectionConfirmed: ((Set<String>) -> Unit)? = null

    private lateinit var expandableListView: ExpandableListView
    private lateinit var adapter: ManufacturerExpandableListAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_raw_types, null)
        
        expandableListView = view.findViewById(R.id.expandableListView)
        val btnDone = view.findViewById<View>(R.id.btnDone)
        val btnSelectAll = view.findViewById<View>(R.id.btnSelectAll)
        val btnClearAll = view.findViewById<View>(R.id.btnClearAll)

        // Initialize selection state from preferences
        val enabledTypes = SettingsFragment.getEnabledRawTypes(requireContext())
        selectionState = mutableMapOf()
        getAllExtensions().forEach { ext ->
            selectionState[ext] = enabledTypes.contains(ext)
        }

        // Setup adapter
        adapter = ManufacturerExpandableListAdapter(
            requireContext(),
            MANUFACTURERS.keys.toList(),
            MANUFACTURERS,
            selectionState
        ) { extension, isChecked ->
            selectionState[extension] = isChecked
            adapter.notifyDataSetChanged()
        }
        expandableListView.setAdapter(adapter)

        // Groups start collapsed - user can expand as needed

        // Don't consume group clicks - let the ExpandableListView handle expand/collapse
        // The checkbox in the group header handles selection

        // Setup buttons
        btnDone.setOnClickListener {
            val selectedExtensions = selectionState.filter { it.value }.keys
            if (selectedExtensions.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.raw_types_select_one,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                onSelectionConfirmed?.invoke(selectedExtensions)
                dismiss()
            }
        }

        btnSelectAll.setOnClickListener {
            selectionState.keys.forEach { selectionState[it] = true }
            adapter.notifyDataSetChanged()
        }

        btnClearAll.setOnClickListener {
            selectionState.keys.forEach { selectionState[it] = false }
            adapter.notifyDataSetChanged()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.raw_types_dialog_title)
            .setView(view)
            .create()
    }

    /**
     * Custom ExpandableListAdapter for manufacturer groups and format children.
     */
    private class ManufacturerExpandableListAdapter(
        private val context: Context,
        private val manufacturers: List<String>,
        private val manufacturerFormats: Map<String, List<String>>,
        private val selectionState: Map<String, Boolean>,
        private val onFormatToggled: (String, Boolean) -> Unit
    ) : BaseExpandableListAdapter() {

        override fun getGroupCount(): Int = manufacturers.size

        override fun getChildrenCount(groupPosition: Int): Int {
            val manufacturer = manufacturers[groupPosition]
            return manufacturerFormats[manufacturer]?.size ?: 0
        }

        override fun getGroup(groupPosition: Int): String = manufacturers[groupPosition]

        override fun getChild(groupPosition: Int, childPosition: Int): String {
            val manufacturer = manufacturers[groupPosition]
            return manufacturerFormats[manufacturer]?.get(childPosition) ?: ""
        }

        override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

        override fun getChildId(groupPosition: Int, childPosition: Int): Long = childPosition.toLong()

        override fun hasStableIds(): Boolean = true

        override fun getGroupView(
            groupPosition: Int,
            isExpanded: Boolean,
            convertView: View?,
            parent: ViewGroup?
        ): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_manufacturer_group, parent, false)

            val manufacturer = getGroup(groupPosition)
            val formats = manufacturerFormats[manufacturer] ?: emptyList()
            val selectedCount = formats.count { selectionState[it] == true }
            val totalCount = formats.size

            val txtManufacturer = view.findViewById<TextView>(R.id.txtManufacturer)
            val txtCount = view.findViewById<TextView>(R.id.txtCount)
            val imgExpand = view.findViewById<ImageView>(R.id.imgExpand)
            val chkGroup = view.findViewById<CheckBox>(R.id.chkGroup)

            txtManufacturer.text = manufacturer
            txtCount.text = context.getString(R.string.raw_types_group_count, selectedCount, totalCount)
            
            // Update checkbox state
            chkGroup.isChecked = selectedCount == totalCount && totalCount > 0
            chkGroup.alpha = if (selectedCount > 0 && selectedCount < totalCount) 0.5f else 1.0f
            
            // Rotate expand indicator
            imgExpand.rotation = if (isExpanded) 180f else 0f

            // Handle checkbox clicks
            chkGroup.setOnClickListener {
                val newState = chkGroup.isChecked
                formats.forEach { ext ->
                    onFormatToggled(ext, newState)
                }
            }

            return view
        }

        override fun getChildView(
            groupPosition: Int,
            childPosition: Int,
            isLastChild: Boolean,
            convertView: View?,
            parent: ViewGroup?
        ): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_format_child, parent, false)

            val extension = getChild(groupPosition, childPosition)
            val isSelected = selectionState[extension] == true

            val chkFormat = view.findViewById<CheckBox>(R.id.chkFormat)
            val txtFormat = view.findViewById<TextView>(R.id.txtFormat)

            txtFormat.text = extension.uppercase()
            chkFormat.isChecked = isSelected

            // Handle checkbox clicks
            chkFormat.setOnClickListener {
                onFormatToggled(extension, chkFormat.isChecked)
            }

            // Handle row clicks
            view.setOnClickListener {
                chkFormat.isChecked = !chkFormat.isChecked
                onFormatToggled(extension, chkFormat.isChecked)
            }

            return view
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true
    }
}
