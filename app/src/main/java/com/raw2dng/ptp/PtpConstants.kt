package com.raw2dng.ptp

/**
 * PIMA 15740 / MTP codes used by the app. Vendor extensions (Canon 0x9xxx,
 * Nikon 0x9xxx, Sony 0x9xxx) are deliberately not listed here: they belong to
 * per-vendor modules layered on [PtpSession.execute].
 */
object PtpConstants {
    // Container types
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    // Operations
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_NUM_OBJECTS = 0x1006
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016
    const val OP_GET_PARTIAL_OBJECT = 0x101B
    const val OP_GET_OBJECT_PROPS_SUPPORTED = 0x9801  // MTP
    const val OP_GET_OBJECT_PROP_VALUE = 0x9803       // MTP
    const val OP_GET_PARTIAL_OBJECT_64 = 0x95C1       // MTP (Android extension)

    // Responses
    const val RSP_OK = 0x2001
    const val RSP_GENERAL_ERROR = 0x2002
    const val RSP_SESSION_NOT_OPEN = 0x2003
    const val RSP_INVALID_TRANSACTION_ID = 0x2004
    const val RSP_OPERATION_NOT_SUPPORTED = 0x2005
    const val RSP_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RSP_INCOMPLETE_TRANSFER = 0x2007
    const val RSP_INVALID_STORAGE_ID = 0x2008
    const val RSP_INVALID_OBJECT_HANDLE = 0x2009
    const val RSP_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RSP_STORE_NOT_AVAILABLE = 0x2013
    const val RSP_SESSION_ALREADY_OPEN = 0x201E
    const val RSP_DEVICE_BUSY = 0x2019

    // Events
    const val EVT_OBJECT_ADDED = 0x4002
    const val EVT_OBJECT_REMOVED = 0x4003
    const val EVT_STORE_ADDED = 0x4004
    const val EVT_STORE_REMOVED = 0x4005
    const val EVT_DEVICE_PROP_CHANGED = 0x4006
    const val EVT_STORAGE_INFO_CHANGED = 0x400C
    const val EVT_CAPTURE_COMPLETE = 0x400D

    // Object formats
    const val FMT_UNDEFINED = 0x3000
    const val FMT_ASSOCIATION = 0x3001
    const val FMT_EXIF_JPEG = 0x3801
    const val FMT_TIFF = 0x380D
    const val FMT_DNG = 0x3811
    const val FMT_VENDOR_RAW_MIN = 0xB000  // vendor-defined image formats (CR2 0xB103, NEF 0xB102, ...)
    const val FMT_VENDOR_RAW_MAX = 0xBFFF

    // Special handles / storage ids
    const val HANDLE_ROOT = -0x1          // 0xFFFFFFFF: children of the root
    const val HANDLE_ALL = 0x0            // all objects in the store
    const val STORAGE_ALL = -0x1          // 0xFFFFFFFF
    const val FORMAT_ANY = 0x0

    // USB class codes (Still Image Capture Device)
    const val USB_CLASS_STILL_IMAGE = 6
    const val USB_SUBCLASS_STILL_IMAGE = 1
    const val USB_PROTOCOL_PIMA15740 = 1

    // Still Image class-specific requests (USB)
    const val REQ_CANCEL = 0x64
    const val REQ_GET_EXTENDED_EVENT_DATA = 0x65
    const val REQ_DEVICE_RESET = 0x66
    const val REQ_GET_DEVICE_STATUS = 0x67

    fun responseName(code: Int): String = when (code) {
        RSP_OK -> "OK"
        RSP_GENERAL_ERROR -> "GeneralError"
        RSP_SESSION_NOT_OPEN -> "SessionNotOpen"
        RSP_INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        RSP_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RSP_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        RSP_INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        RSP_INVALID_STORAGE_ID -> "InvalidStorageID"
        RSP_INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        RSP_DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        RSP_STORE_NOT_AVAILABLE -> "StoreNotAvailable"
        RSP_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        RSP_DEVICE_BUSY -> "DeviceBusy"
        else -> "0x%04X".format(code)
    }
}

/** A failed PTP transaction. [responseCode] is 0 when the failure was at the transport. */
class PtpException(message: String, val responseCode: Int = 0, cause: Throwable? = null) : Exception(message, cause)
