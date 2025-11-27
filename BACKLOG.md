# backlog
## high priority

### ux
*) material you should reflect the system theme. it is currently purple and my system theme is not purple.
*) in the gallery, when multiselect is active, a button should be rendered to the right of "Open (count) in..." that says "Delete Selections"

### conversion workflow
*) jpeg quality is very noisy, lets encode that at a higher quality for export versus thumbnails
*) from the gallery tab, regenrate should be an option, and prompt the user for settings, defaulting at the app level. do not persist these values

### raw handling
*) rotation of the input should be transfered to the dng, even if that means rotating the image in memory before writing the dng

### settings window, rendered as a gear on the top right corner of the gallery and convert views
- all of these settings should be persisted
*) allow the autonavigate feature to be set
*) allow the timeout for autonav to be overridden from 0 seconds (warning text in red for 0 seconds) to 30 seconds
*) allow the list of known raw types to be any-combo of picked from
*) allow the directories to search for raws in to be overridden (requires a picker)
*) allow setting of the convert dialogue text log or thumbnail previews
*) a button to a dialogue with all license credits
*) allow filters on convert picker tab to de-emphasize, gray out if you will, images which have already been converted to that format
*) the jpeg conversion factors such as quality level

### exif
*) preview view should have a button to show exif data
*) all exif data should be transfered to jpeg
*) all exif data should be transfered to dng
*) in multi-select mode in gallery, exif data should be available under a button when only one image is selected

## low priority
*) conversion list should be filterable by exif data like camera, lens, etc
*) playstore???
*) a button in settings after the licenses button to navigate to the github for this project
*) source libraw from the jetpack source
*) find a way to handle license credits semi-automatically
