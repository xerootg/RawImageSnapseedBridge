# backlog
## high priority

### ux
*) the conversion page should autonavigate after 5 seconds if the automatically go to gallery on completion checkbox is checked. this value should be persisted.
*) the style of the buttons at the bottom of convert do not match
*) in the conversion preview, the gutter of images should be rendered, showing selected images with a green checkmark
*) screen rotation cancels whatever is running if converting, even if conversion is complete
*) in multi-select mode in gallery, previewing the selected image(s) should be possible with a button in the top bar, and in preview there should be a button a the top allowing openwith, as well as selection and de-selection of images. gutter of images should be rendered, showing selected images with a green checkmark

### conversion workflow
*) conversion should be parallelized, after ensuring the underlying JNI is thread-safe. if it is not threadsafe, do not parallelize.
*) if an image has previously been converted, add a badge to the conversion thumbnail that says "tap to overwrite" and make sure the existing code overwrites the previous conversion. require the user to touch each overwrite image before converting it, and if it is not touched, and all other images have been rendered, done should be available. conversion should run for all images not needing overwrite confirmation, and confirmed overwrites should be dynamically added to the list of images to convert at the end of the conversion queue.

### raw handling
*) rotation of the input should be transfered to the dng, even if that means rotating the image in memory before writing the dng

### exif
*) preview view should have a button to show exif data
*) all exif data should be transfered to jpeg
*) all exif data should be transfered to dng
*) in multi-select mode in gallery, exif data should be available under a button when only one image is selected

## low priority
*) conversion list should be filterable by exif data like camera, lens, etc
*) playstore???
