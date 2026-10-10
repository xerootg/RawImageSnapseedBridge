# backlog
## exif
* if there's AF info, please render a green box on the convert preview where the af location was if the data is available (rawler would need to expose AF point data from the makernotes). Do not do this for the gallery preview.

## exif editing
* allow all dng exif fields to be editable

## filtering
* add a dropdown to allow the directories to search for raws in to be overridden in the settings menu. it should be based on the current folders that current raw images were found in. this selection should be persisted, easily resettable, and apply immediately to the picker as well as preview
* conversion picker should be filterable by exif data like camera, lens, in addition to already converted formats

## sources (see docs/SOURCES.md)
* PTP/IP transport (Wi-Fi cameras): implement `PtpTransport`; discovery produces the transport factory for `PtpSource`
* advanced camera settings: vendor modules over `PtpSession.execute` keyed on `deviceInfo.vendorExtensionId`; UI gated on `SourceCapability.DEVICE_PROPERTIES`
* tethered capture: `PtpSession.pollEvent` + ObjectAdded -> auto-convert
* MTP `GetObjectPropList` to enumerate a store in one transaction instead of one `GetObjectInfo` per object
* RRC: manual bucket + key entry form (calls `RrcConfig.save`); medium thumbs for the preview pager

## release!
* playstore??? f-droid???
* a button in settings after the licenses button to navigate to the github for this project
* expose more dnglab authoring options in the UI (embed original RAW, DCP profile directory, uncompressed DNG)
* find a way to handle license credits semi-automatically - using llm extraction is a great way to miss someone
