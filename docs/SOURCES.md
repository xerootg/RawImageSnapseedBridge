# RAW sources: cameras, drives and the cloud as inputs

Raw2DNG used to have exactly one place RAW files could come from: the phone's
MediaStore. This document describes the source abstraction that replaced it,
how "already converted" is tracked without touching the source, and where the
next pieces (PTP/IP, camera settings, tethering) plug in.

Package map (all under `app/src/main/java/com/raw2dng/`):

| Package  | What lives there |
|----------|------------------|
| `source` | `RawSource` / `RawEntry` model, `SourceRegistry`, `SourceContentProvider`, `SourceFetchCache`, the device-media and SAF sources, USB discovery |
| `ledger` | `ConversionLedger` (SQLite) and `RawFingerprint` |
| `ptp`    | PTP protocol: codec, datasets, `PtpTransport`, `UsbPtpTransport`, `PtpSession`, `PtpSource` |
| `rrc`    | RapidRawCloud: `SigV4`, `S3Client`, `RrcConfig`, `RrcSource`, `RrcPairing`, `PairingCallbackActivity` |

## 1. The `RawSource` contract

```kotlin
interface RawSource {
    val descriptor: SourceDescriptor          // stable SourceId + labels
    val capabilities: Set<SourceCapability>   // THUMBNAIL, PARTIAL_READ, CAPTURE_DATE, TRANSIENT, DEVICE_PROPERTIES, EVENTS
    suspend fun open(); suspend fun close()
    suspend fun enumerate(extensions: Set<String>): List<RawEntry>
    suspend fun lookup(ref: String): RawEntry?
    suspend fun openStream(entry: RawEntry): InputStream
    suspend fun readRange(entry: RawEntry, offset: Long, length: Int): ByteArray?   // PARTIAL_READ
    suspend fun thumbnail(entry: RawEntry): ByteArray?                              // THUMBNAIL
    suspend fun fetchTo(entry: RawEntry, dest: File, onProgress)                    // default: openStream copy
}
```

A `SourceId` is `(kind, instance)`: `media:local`, `tree:<sha256(treeUri)[..16]>`,
`ptp:<camera serial>`, `rrc:<bucket>@<host>`. It is the identity the ledger
stores, so a camera is the same source over USB today and over Wi-Fi later,
and a bucket is the same source from any device.

A `RawEntry` is `(source, ref, name, size, mtime, captureTime?, folder, nativeUri?)`.
`ref` is opaque to everyone but the source (MediaStore id, SAF document id,
`storage/handle` for PTP, S3 relkey).

### Every entry is a `content://` URI

The whole existing UI (preview pager, EXIF overlay, thumbnail strip, share,
"open with", the converter's copy-to-cache) is written against `Uri` +
`ContentResolver`. Rather than rewrite it, `SourceContentProvider`
(authority `com.raw2dng.sources`, not exported) serves any entry:

- `content://com.raw2dng.sources/<sourceId>/<ref>?n=<name>`
- `openFile` materialises the object through `SourceFetchCache` (LRU, 768 MB
  default) so a camera or cloud object is transferred once and then read
  locally by the preview, the EXIF reader and the converter.
- `openTypedAssetFile` with `EXTRA_SIZE` returns the source's cheap thumbnail
  (PTP `GetThumb`, RRC `_small.jpg`), which is exactly what
  `ContentResolver.loadThumbnail` asks for; when there is none it throws and
  the existing fallback (native preview extraction) runs.
- `query` answers `DISPLAY_NAME`, `SIZE`, `DATE_MODIFIED`, `MIME_TYPE`, and
  `_data` when the object is already cached.

Sources whose bytes the platform already serves (MediaStore, SAF) keep their
native URI as `RawEntry.nativeUri`, so OS thumbnails keep working there.

### Registry and lifecycle

`SourceRegistry` holds the live sources. Persistent ones are recreated on
`init`: the device library, saved document trees, and the paired RRC bucket.
Transient ones (USB cameras) are registered by `MainActivity` when the device
is attached and permission granted, and unregistered on detach. The active
source id is persisted; the Convert tab listens and reloads on change.

## 2. Tracking conversions without touching the source

`ConversionLedger` (`conversion_ledger.db`) is the source of truth for "what
has been converted to what". Nothing is ever written next to a RAW.

**Identity.** `RawFingerprint`:

- `c:` content fingerprint: SHA-256(size ‖ first 64 KiB). The head of a RAW
  holds the TIFF/CR3 header, maker notes and usually the embedded thumbnail,
  so it is unique per capture, and 64 KiB is cheap from every source
  (SAF stream, PTP `GetPartialObject`, S3 `Range`).
- `m:` metadata fingerprint: SHA-256(source instance ‖ name ‖ size ‖ capture
  or mtime). Only for sources without range reads (a camera without
  `GetPartialObject`). After the first full transfer the ledger learns the
  content fingerprint and records an alias, so later sightings of the same
  file anywhere resolve to the same identity.

**Tables.** `entry_fp` caches `(source, ref, name, size, mtime) -> fp` so a
re-listing is DB-only; `fp_alias` maps `m:` to `c:`; `conversion` holds
`(fp, format) -> output URI, output name, time, provenance (source, ref, name)`.

**Reconciliation.** `statusOf` checks that each recorded output still exists
and prunes rows that do not, so deleting a DNG from the Gallery flips the RAW
back to "not converted" without any extra bookkeeping. Outputs produced before
the ledger existed are adopted the first time the picker sees a RAW whose
`<base>.dng`/`.jpg` exists (the old name-based check, now a fallback only).

**Gallery → source.** "Regenerate" asks the ledger which source entry produced
an output and, if that source is currently available, uses it; otherwise it
falls back to the old name search in MediaStore.

## 3. PTP

The PTP code is layered so that transports and camera features evolve
independently:

```
PtpSource            RawSource over a PtpSession (enumerate, GetThumb, GetObject, GetPartialObject)
PtpSession           transaction ids, OpenSession/CloseSession, typed standard ops, execute() for anything else
PtpTransport         one transaction = command phase + optional data phase + response phase
  UsbPtpTransport    bulk containers (PIMA 15740 Annex D) over UsbDeviceConnection
  PtpIpTransport     (next) PTP/IP packets over two TCP sockets
PtpCodec / datasets  little-endian readers, DeviceInfo / StorageInfo / ObjectInfo
```

`UsbPtpTransport` claims the Still Image interface (class 6/1/1) or a
vendor-class MTP interface with the same endpoint shape, handles split
headers, unknown-length data phases (0xFFFFFFFF), zero-length packets, and
the 16 KiB `bulkTransfer` limit on API < 28. `reset()` issues the
class-specific Device Reset request.

Why not `android.mtp.MtpDevice`? It speaks the same wire protocol but cannot
send vendor operations or device-property requests, which the next steps
need. Reimplementing the ~300 lines of container framing buys a session
object that the camera-settings and tethering features can share.

### Adding PTP/IP

Implement `PtpTransport` only. PTP/IP (ISO 15740 Annex) frames the same three
phases as packets with their own 8-byte header (`length`, `type`):
`Init_Command_Request`/`Ack`, `Init_Event_Request`/`Ack` on a second socket,
then `Cmd_Request`, `Start_Data`/`Data`/`End_Data`, `Cmd_Response`, and
`Event` on the event socket. `transact()` maps 1:1 onto that; `pollEvent()`
reads the event socket. Discovery (mDNS `_ptp._tcp`, Canon/Nikon pairing
dances) is a separate concern that produces a `() -> PtpTransport` factory for
`PtpSource`, exactly like `UsbCameras.ptpSourceFor` does for USB.

### Adding camera settings and vendor operations

`PtpSession.execute(PtpRequest(code, params), dataOut, dataIn)` is the
generic transaction; `getDevicePropDesc`/`getDevicePropValue`/
`setDevicePropValue` cover standard properties. A vendor module keys off
`deviceInfo.vendorExtensionId` (Canon 0x0000000B, Nikon 0x0000000A, Sony
0x00000011, Microsoft/MTP 0x00000006) and layers its op codes on top of the
session without touching the transport. `PtpSource.session()` exposes the
open session for such a module; the `DEVICE_PROPERTIES` and `EVENTS`
capabilities tell the UI whether to offer those screens.

## 4. Mass storage

Android does not give apps block access to USB drives; the system mounts
them and exposes them as removable `StorageVolume`s. `DocumentTreeSource`
walks a Storage Access Framework tree (one `DocumentsContract` query per
directory, skipping `Android/`, `.thumbnails`, `LOST.DIR`, …) and keeps the
persisted read permission. "Add folder or USB drive…" on the Convert tab
offers the mounted removable volumes first (`StorageVolume.createOpenDocumentTreeIntent`
on Android 10+), then any folder. A camera in mass-storage mode, a card
reader, an SD card and a network provider all look the same to the app.

## 5. RapidRawCloud

`RrcSource` is a read-only consumer of the RapidRawCloud bucket layout
(RapidRawCloud `docs/ARCHITECTURE.md` §1.2):

- originals are `library/<relpath>` minus sidecars (`.rrdata`, `.xmp`),
  conflict copies and engine temp files;
- `.rrcloud/v1/tombstones/*.json` name soft-deleted keys still inside their
  grace window; they are excluded;
- `.rrcloud/v1/manifests/<device>.json.gz` (gzip NDJSON) map relkeys to
  `content_id`, which keys `.rrcloud/v1/thumbs/<content_id>_small.jpg`, so
  the grid shows 480 px thumbnails without downloading RAWs.

Raw2DNG never writes to the bucket: no journal, no device registry, no
manifests. `S3Client` is a 150-line SigV4 (header auth, unsigned payload)
client over `HttpURLConnection` doing `ListObjectsV2`, `GET` with `Range`, and
`HEAD`; path-style by default.

### Fast pairing

`RrcPairing` implements the same flow as RapidRAW's `sync/pairing.rs` against
the RapidRawCloud pairing service, with this app's own redirect URI
`raw2dng://auth-callback` (the service advertises both URIs in
`GET /api/pairing-info` → `redirectUris`; the identity provider must allow
both). The user types one thing, the service URL, in Settings:

1. `GET <service>/api/pairing-info` → OIDC issuer + public client id.
2. `GET <issuer>/.well-known/openid-configuration` → authorize/token endpoints.
3. Custom Tab at the authorize endpoint with PKCE S256 + random `state`.
   The pending verifier/state live in private prefs for 10 minutes so the
   flow survives the process being killed behind the browser.
4. `PairingCallbackActivity` receives the redirect, exchanges the code
   (public client + verifier), calls `GET <service>/api/config` with the
   bearer and stores `{endpoint, bucket, region, forcePathStyle}` in plain
   prefs and the key pair in `EncryptedSharedPreferences` (Android Keystore).
5. The source is registered and selected; the token is dropped.

Manual configuration (typing bucket + key) is deliberately not exposed yet;
`RrcConfig.save` is the only entry point and a Settings form can call it.

## 6. What the UI does with all this

- Convert tab: a Source button (device, saved folders/drives, attached USB
  cameras, cloud) and a refresh button. Loading opens the source, enumerates,
  fingerprints what the ledger has not seen (progress in the count label),
  and resolves status from the ledger.
- A camera plugged in while the app is closed launches it (USB device
  filter); plugged in while running shows a snackbar with "Use camera".
  USB permission is requested through a mutable, package-scoped
  `PendingIntent` as Android 14 requires.
- Settings: RapidRawCloud pairing/unpairing, saved folders with "Forget",
  and the fetched-RAW cache size with a clear button.

## 7. Known limits / next steps

- PTP enumeration issues one `GetObjectInfo` per object. MTP
  `GetObjectPropList` would fetch name/size/date for a whole store in one
  transaction; add it behind `deviceInfo.supports(0x9805)`.
- No PTP/IP yet (see §3). No tethered capture / live view yet; the `EVENTS`
  capability and `PtpSession.pollEvent` are the hooks.
- RRC thumbnails depend on manifests existing (a device or the worker must
  have published one); without them the grid falls back to downloading the
  RAW for a preview.
- The fetch cache is per-entry, not per-session; a 40 MB RAW previewed from
  a camera stays cached (and is reused by the conversion) until LRU eviction.
