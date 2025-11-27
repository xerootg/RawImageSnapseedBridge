# backlog
## high priority

### settings window, rendered as a gear on the top right corner of the gallery and convert views
- all of these settings should be persisted
*) a button to a dialogue with all license credits
*) allow filters on convert picker tab to de-emphasize, gray out if you will, images which have already been converted to that format
*) the jpeg conversion factors such as quality level, in a modal to be reused later. an ok button on the modal will be used 

### conversion workflow (depends on settings)
*) from the gallery tab, regenrate should be an option, and prompt the user for settings, defaulting at the app level. do not persist these values

### exif
*) preview view should have a button to show exif data for both gallery and convert. it belongs with the zoom and nav controls on the bottom of the page.
*) all exif data should be transfered to jpeg
*) all exif data should be transfered to dng
*) in multi-select mode in gallery, exif data should be available under a button when only one image is selected

### raw handling
*) rotation of the input should be transfered to the dng, even if that means rotating the image in memory before writing the dng

## low priority
*) conversion list should be filterable by exif data like camera, lens, etc
*) playstore???
*) a button in settings after the licenses button to navigate to the github for this project
*) source libraw from the jetpack source
*) find a way to handle license credits semi-automatically
*) allow the directories to search for raws in to be overridden (requires a picker)
