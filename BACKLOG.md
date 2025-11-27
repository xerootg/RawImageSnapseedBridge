# backlog
## high priority

### ux
*) if the mediaapi is used to delete pictures, regardless of filter type, mediaapi asks permission to delete the files. do not additionally ask for permission as jpeg does now.
*) screen rotation cancels whatever is running if converting, even if conversion is complete
*) in multi-select mode in gallery, previewing the selected image(s) should be possible with a button in the top bar, and in preview there should be a button a the top allowing openwith, as well as selection and de-selection of images. gutter of images should be rendered, showing selected images with a green checkmark
*) material you should reflect the system theme. it is currently purple and my system theme is not purple.

### conversion workflow
*) conversion should be parallelized, after ensuring the underlying JNI is thread-safe. if it is not threadsafe, do not parallelize.
*) if an image has previously been converted, add a badge to the conversion thumbnail that says "tap to overwrite" and make sure the existing code overwrites the previous conversion. require the user to touch each overwrite image before converting it, and if it is not touched, and all other images have been rendered, done should be available. conversion should run for all images not needing overwrite confirmation, and confirmed overwrites should be dynamically added to the list of images to convert at the end of the conversion queue. The autonavigate checkbox feature should not autonavigate if not all images have been confirmed, even if all other images converted sucessfully

### raw handling
*) rotation of the input should be transfered to the dng, even if that means rotating the image in memory before writing the dng

### settings window, rendered as a gear on the top right corner of the gallery and convert views
- all settings should be persisted
*) allow the autonavigate feature to be set
*) allow the timeout for autonav to be overridden from 0 seconds (warning text in red for 0 seconds) to 30 seconds
*) allow the list of known raw types to be any-combo of picked from
*) allow the directories to search for raws in to be overridden (requires a picker)
*) allow setting of the convert dialogue text log or thumbnail previews

### exif
*) preview view should have a button to show exif data
*) all exif data should be transfered to jpeg
*) all exif data should be transfered to dng
*) in multi-select mode in gallery, exif data should be available under a button when only one image is selected

## low priority
*) conversion list should be filterable by exif data like camera, lens, etc
*) playstore???
