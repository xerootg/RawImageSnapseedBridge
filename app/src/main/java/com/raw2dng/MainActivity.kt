package com.raw2dng

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.raw2dng.ptp.PtpSource
import com.raw2dng.source.SourceId
import com.raw2dng.source.SourceRegistry
import com.raw2dng.source.UsbCameras
import kotlinx.coroutines.launch
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

        SourceRegistry.init(this)
        setupTabs()
        setupBackNavigation()
        checkPermissions()
        registerUsbReceivers()
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: IllegalArgumentException) { /* not registered */ }
    }

    // === USB cameras (PTP/MTP) ===

    private val ptpSources = HashMap<String, SourceId>()  // UsbDevice.deviceName -> registered source
    private val connecting = HashSet<String>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    device ?: return
                    connecting.remove(device.deviceName)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openPtpSource(device)
                    } else {
                        Toast.makeText(this@MainActivity, R.string.source_usb_permission_needed, Toast.LENGTH_SHORT).show()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device ?: return
                    if (!com.raw2dng.ptp.UsbPtpTransport.isPtpDevice(device)) return
                    Snackbar.make(binding.root, getString(R.string.source_camera_attached, UsbCameras.label(device)), Snackbar.LENGTH_LONG)
                        .setAction(R.string.source_use_camera) { connectPtpDevice(device) }
                        .show()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device ?: return
                    val id = ptpSources.remove(device.deviceName) ?: return
                    val name = SourceRegistry.get(id)?.descriptor?.displayName ?: UsbCameras.label(device)
                    SourceRegistry.unregister(this@MainActivity, id)
                    Toast.makeText(this@MainActivity, getString(R.string.source_disconnected, name), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun registerUsbReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts and our own PendingIntent broadcast still reach a non-exported receiver.
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    /** A camera plugged in while the app is closed launches us with the device in the intent. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }) ?: return
        connectPtpDevice(device)
    }

    fun isPtpDeviceConnected(device: UsbDevice): Boolean = ptpSources.containsKey(device.deviceName)

    /** Ask for USB permission if needed, then open the camera as a source and switch to it. */
    fun connectPtpDevice(device: UsbDevice) {
        val manager = UsbCameras.manager(this)
        ptpSources[device.deviceName]?.let { id ->
            SourceRegistry.setActive(this, id)
            binding.viewPager.currentItem = 0
            return
        }
        if (manager.hasPermission(device)) {
            openPtpSource(device)
            return
        }
        if (!connecting.add(device.deviceName)) return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        manager.requestPermission(device, pi)
    }

    private fun openPtpSource(device: UsbDevice) {
        if (ptpSources.containsKey(device.deviceName)) return
        val source: PtpSource = UsbCameras.ptpSourceFor(this, device)
        Toast.makeText(this, getString(R.string.source_opening, UsbCameras.label(device)), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                source.open()
                ptpSources[device.deviceName] = source.descriptor.id
                SourceRegistry.register(source)
                SourceRegistry.setActive(this@MainActivity, source.descriptor.id)
                binding.viewPager.currentItem = 0
            } catch (e: Exception) {
                Log.e("MainActivity", "PTP open failed for ${device.deviceName}", e)
                Toast.makeText(this@MainActivity, getString(R.string.source_open_failed, UsbCameras.label(device), e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun navigateToSettings() {
        binding.viewPager.currentItem = 2
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
        binding.viewPager.currentItem = 1
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
        binding.viewPager.currentItem = 0
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
        binding.viewPager.currentItem = 0
        // Use post to ensure the fragment is fully visible before selecting and starting
        binding.viewPager.post {
            pickerFragment?.selectFilesAndStartConversion(rawUris, outputFormat, jpegQuality, jpegChroma, jpegOptimize, jpegEmbeddedPolicy)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.raw2dng.USB_PERMISSION"
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
