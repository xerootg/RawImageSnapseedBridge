package com.raw2dng.source

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.raw2dng.ptp.PtpSource
import com.raw2dng.ptp.UsbPtpTransport

/**
 * Discovery helpers for USB-attached sources.
 *
 * Two shapes of camera show up over USB:
 * - **PTP/MTP** (most cameras today): a Still Image class interface this app
 *   drives directly via [UsbPtpTransport]; needs the user's USB permission.
 * - **Mass storage** (older cameras, card readers): Android mounts it as a
 *   removable [StorageVolume]; the app reaches it through a SAF document
 *   tree ([DocumentTreeSource]) that the user grants once.
 */
object UsbCameras {

    fun manager(context: Context): UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Attached PTP/MTP devices, permission or not. */
    fun attachedPtpDevices(context: Context): List<UsbDevice> =
        manager(context).deviceList.values.filter { UsbPtpTransport.isPtpDevice(it) }

    fun label(device: UsbDevice): String {
        val product = device.productName
        val maker = device.manufacturerName
        return listOfNotNull(maker?.trim()?.takeIf { it.isNotEmpty() }, product?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ").ifEmpty { "USB %04x:%04x".format(device.vendorId, device.productId) }
    }

    fun provisionalInstance(device: UsbDevice): String {
        // Reading the serial needs the USB permission on API 29+; fall back to vid:pid until then.
        val serial = runCatching { device.serialNumber }.getOrNull()
        return serial?.takeIf { it.isNotBlank() } ?: "usb:%04x:%04x".format(device.vendorId, device.productId)
    }

    /** A [PtpSource] for a device the user has granted permission for. Not yet opened. */
    fun ptpSourceFor(context: Context, device: UsbDevice): PtpSource {
        val app = context.applicationContext
        val mgr = manager(app)
        return PtpSource(app, { UsbPtpTransport.open(mgr, device) }, label(device), provisionalInstance(device))
    }

    /** Removable volumes (USB drives, SD cards) that are mounted right now. */
    fun removableVolumes(context: Context): List<StorageVolume> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
    }

    /** An intent that lets the user grant a document tree rooted at [volume] (or anywhere, pre-Q). */
    fun openTreeIntent(volume: StorageVolume?): android.content.Intent {
        val intent = if (volume != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            volume.createOpenDocumentTreeIntent()
        } else {
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        intent.addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        return intent
    }
}
