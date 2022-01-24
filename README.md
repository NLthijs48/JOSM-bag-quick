# BAG Quick 

Plugin for the [JSOM](https://josm.openstreetmap.de/) editor for [OpenStreetMap](https://www.openstreetmap.org/) to do 1-click updates of buildings in The Netherlands based on the [official BAG data](https://bag.basisregistraties.overheid.nl/) ([browse data online](https://bagviewer.kadaster.nl/lvbag/bag-viewer)).

This plugin is built on top of the [ODS-BAG plugin](https://bag.tools4osm.nl/#) maintained by [Sander H](https://www.openstreetmap.org/user/Sander%20H), which downloads BAG data in a separate layer next to the OSM layer.
After this data is downloaded the 'Action' in the plugin allows you to click a building to update it.

## Features
- Import a new building with all correct tags (`building`, `ref:bag`, `source`, `source:date`, `start_date`)
- WIP: update the geometry and tags of a building that is already present in OpenStreetMap

## Notes
Licenced with GPL-v3.0, see `GPL-v3.0.txt` in this repository.
