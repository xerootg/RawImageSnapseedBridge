package com.raw2dng

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.DynamicColors
import com.google.android.material.tabs.TabLayoutMediator
import com.raw2dng.databinding.ActivityMainTabsBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainTabsBinding

    // FragmentStateAdapter tags its fragments "f<itemId>" and does not call
    // createFragment() again after the activity is recreated, so cached
    // references would be stale (null) then. Always resolve the live instance.
    private val galleryFragment: GalleryFragment?
        get() = supportFragmentManager.findFragmentByTag("f$TAB_GALLERY") as? GalleryFragment
    private val pickerFragment: RawFilePickerFragment?
        get() = supportFragmentManager.findFragmentByTag("f$TAB_CONVERT") as? RawFilePickerFragment

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // Permissions granted - refresh the picker fragment
            refreshPickerFragment()
        } else {
            Toast.makeText(this, "Storage permission is required to browse files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPickerFragment() {
        pickerFragment?.refreshFiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply dynamic colors (Material You) before calling super.onCreate
        DynamicColors.applyToActivityIfAvailable(this)
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainTabsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupBackNavigation()
        checkPermissions()
    }

    private fun setupBackNavigation() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.viewPager.currentItem > 0) {
                    // Go back to first tab
                    binding.viewPager.currentItem = 0
                } else {
                    // On first tab, exit the app
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true  // Re-enable for next time
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun setupTabs() {
        val adapter = TabAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_convert)
                1 -> getString(R.string.tab_gallery)
                2 -> getString(R.string.tab_settings)
                else -> ""
            }
        }.attach()
        
        // Clear conversion overlay when switching away from Convert tab
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position != 0) {
                    // User navigated away from Convert tab - clear overlay if done
                    pickerFragment?.clearConversionOverlayIfDone()
                }
            }
        })
    }

    fun refreshGallery() {
        galleryFragment?.refresh()
    }
    
    fun refreshConvertTab() {
        pickerFragment?.refreshConversionStatus()
    }

    fun navigateToGallery() {
        binding.viewPager.currentItem = TAB_GALLERY
    }
    
    fun navigateToGallery(filterFormat: OutputFormat) {
        binding.viewPager.currentItem = TAB_GALLERY
        // Use post to ensure the fragment is fully visible before setting filter
        binding.viewPager.post {
            galleryFragment?.setFilter(filterFormat)
        }
    }
    
    /**
     * Navigate to Convert tab and pre-select the specified RAW files.
     * Used by Gallery's regenerate feature to re-convert files.
     * @param rawUris The URIs of the RAW files to select
     */
    fun navigateToConvertWithSelection(rawUris: List<Uri>) {
        binding.viewPager.currentItem = TAB_CONVERT
        // Use post to ensure the fragment is fully visible before selecting
        binding.viewPager.post {
            pickerFragment?.selectFilesByUris(rawUris)
        }
    }
    
    /**
     * Navigate to Convert tab, pre-select the specified RAW files, and auto-start conversion.
     * Used by Gallery's regenerate feature to re-convert files with specific settings.
     * @param rawUris The URIs of the RAW files to select
     * @param outputFormat The format to convert to (DNG or JPEG)
     * @param jpegQuality JPEG quality (1-100), only used if outputFormat is JPEG
     * @param jpegChroma JPEG chroma subsampling, only used if outputFormat is JPEG
     * @param jpegOptimize Whether to optimize JPEG Huffman tables, only used if outputFormat is JPEG
     * @param jpegEmbeddedPolicy When to reuse the camera's embedded JPEG, only used if outputFormat is JPEG
     */
    fun navigateToConvertAndStart(
        rawUris: List<Uri>,
        outputFormat: OutputFormat,
        jpegQuality: Int = 95,
        jpegChroma: Int = DNGConverter.CHROMA_SUBSAMPLING_444,
        jpegOptimize: Boolean = true,
        jpegEmbeddedPolicy: Int = DNGConverter.DEFAULT_EMBEDDED_POLICY
    ) {
        binding.viewPager.currentItem = TAB_CONVERT
        // Use post to ensure the fragment is fully visible before selecting and starting
        binding.viewPager.post {
            pickerFragment?.selectFilesAndStartConversion(rawUris, outputFormat, jpegQuality, jpegChroma, jpegOptimize, jpegEmbeddedPolicy)
        }
    }

    private inner class TabAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                TAB_CONVERT -> RawFilePickerFragment.newInstance()
                TAB_GALLERY -> GalleryFragment()
                TAB_SETTINGS -> SettingsFragment.newInstance()
                else -> RawFilePickerFragment.newInstance()
            }
        }
    }

    companion object {
        private const val TAB_CONVERT = 0
        private const val TAB_GALLERY = 1
        private const val TAB_SETTINGS = 2
    }
}
