package com.raw2dng

import android.Manifest
import android.content.pm.PackageManager
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
    private var galleryFragment: GalleryFragment? = null
    private var pickerFragment: RawFilePickerFragment? = null
    private var settingsFragment: SettingsFragment? = null

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
        // Find the RawFilePickerFragment and refresh it
        val fragment = supportFragmentManager.fragments
            .filterIsInstance<RawFilePickerFragment>()
            .firstOrNull()
        fragment?.refreshFiles()
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
        binding.viewPager.currentItem = 1
    }
    
    fun navigateToGallery(filterFormat: OutputFormat) {
        galleryFragment?.setFilter(filterFormat)
        binding.viewPager.currentItem = 1
    }

    private inner class TabAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> {
                    pickerFragment = RawFilePickerFragment.newInstance()
                    pickerFragment!!
                }
                1 -> {
                    galleryFragment = GalleryFragment()
                    galleryFragment!!
                }
                2 -> {
                    settingsFragment = SettingsFragment.newInstance()
                    settingsFragment!!
                }
                else -> RawFilePickerFragment.newInstance()
            }
        }
    }
}
