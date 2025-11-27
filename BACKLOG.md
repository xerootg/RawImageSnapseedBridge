# backlog
## high priority
### conversion
*) ensure tab navigation does not end active conversions
*) the filename under each thumbnail while convering should reflect the new filename not the old one. so not img.nef but img.jpeg or img.dng
*) persisted setting to adjust concurrency while converting from 1 to number of cores

## unsorted
*) raw format picker arrow does not match style, and if it does, its ugly and should be replaced
*) all possible raw metadata from libraw_data_t should be rendered in the advanced exif viewer for raw files, including manufacture notes, common and manufacturer specific.
*) I see there's AF info, please render a green box on the convert preview where the af location was if the data is available - libraw_afinfo_item_t, in libraw_metadata_common_t, part of makernotes.
*) converted DNG thumbnails are not rotated correctly.
*) button style for regen/preview/open/delete is smushed and also mismatched from the rest of the UI
*) playstore??? f-droid???
*) a button in settings after the licenses button to navigate to the github for this project
*) source libraw from the jetpack source
*) find a way to handle license credits semi-automatically


## filtering
*) add a dropdown to allow the directories to search for raws in to be overridden in the settings menu. it should be based on the current folders that current raw images were found in. this selection should be persisted, easily resettable, and apply immediately to the picker as well as preview
*) conversion list should be filterable by exif data like camera, lens, etc
