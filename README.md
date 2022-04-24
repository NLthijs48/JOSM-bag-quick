[![Build jar](https://github.com/NLthijs48/JOSM-bag-quick/actions/workflows/build.yml/badge.svg?event=push)](https://github.com/NLthijs48/JOSM-bag-quick/actions/workflows/build.yml)

# BAG Quick 

Plugin for the [JSOM](https://josm.openstreetmap.de/) editor for [OpenStreetMap](https://www.openstreetmap.org/) to do 1-click updates of buildings in The Netherlands based on the [official BAG data](https://bag.basisregistraties.overheid.nl/) ([browse data online](https://bagviewer.kadaster.nl/lvbag/bag-viewer)).

This plugin is built on top of the [ODS-BAG plugin](https://bag.tools4osm.nl/) maintained by [Sander H](https://www.openstreetmap.org/user/Sander%20H), which downloads BAG data in a separate layer next to the OSM layer.
After this data is downloaded the 'Action' in the plugin allows you to click a building to update it.

## Features
- Import a new building with all correct tags (`building`, `ref:bag`, `source`, `source:date`, `start_date`)
- Update buildings already present in OpenStreetMap
    - Updates the geometry, reusing nodes where possible
    - Reconnects to surrounding buildings if nodes are in the same location
    - Updates all tags
    - Warns about `note`, `note:bag` and `fixme` tags if present
- Shows detailed results after the update to indicate what has changed

## Installation
1. Become a BAG importer by asking in [this topic of the section of the forum of The Netherlands](https://forum.openstreetmap.org/viewtopic.php?pid=831990#p831990)
2. Login to [ttps://bag.tools4osm.nl/plugins.php](https://bag.tools4osm.nl/plugins.php) (on your [profile page](https://bag.tools4osm.nl/profile.php) it should say `BAG importeur: ja` if step 1. has worked correctly)
3. In the `ods-bag` section, download `bag-plugin-X.Y.Z.zip`, unzip it, add the plugin files to the JOSM plugin folder:
    - Windows: `%APPDATA%\JOSM\plugins`
    - Linux: `/home/$USER/.local/share/JOSM/plugins`
    - MacOS: `Users/<YourName>/Library/JOSM/plugins`
5. Optional: install additional plugins on the same page
    - `nl-bag` for a couple validations
    - `address_outside_buildign.mapcss` for warnings about address nodes outside of buildings
    - `nl-pdok-report` to report data issues to PDOK
6. Install the `bag-quick` plugin
7. Restart JOSM to activate the plugins

## Usage
1. Enable ODS bag import using the menu at the top: `ODS` > `Enable` > `BAG`
    - A `BAG ODS` and `BAG OSM` layer should show up in the `Layers` panel
2. Do a download of the area you want to update in using `ODS` > `Download` (using the regular download button won't work)
    - Select an area in the popup
    - Click `Download`
3. Activate the `BAG update` tool using either:
    - `CTRL+ALT+G`
    - `Tools` > `BAG update`
4. Click on building to import/update it
    - A message in the bottom left corner will indicate the result
    - The `Validation Results` panel might show errors/warnings, solve those
5. Verify the results on the `BAG OSM` layer
6. Import/update more buildings, and possibly download more data as well until you are done
7. Upload the results (solving any warnings before upload)

## Notes
Licenced with GPL-v3.0, see `GPL-v3.0.txt` in this repository.
