# Changelog

## [0.7.0](https://github.com/zolfrik/amija-amiga-emulator/compare/v0.6.0...v0.7.0) - 2026-11-09

### Added

* **MiSTer output**: the picture and the sound go over Ethernet to a MiSTer FPGA running the Groovy core, on a real 15 kHz CRT, with less than a frame of delay. New MiSTer (Groovy) section in the F12 popup: start and stop, automatic search of the MiSTer, picture position, and the controls plugged into the MiSTer can drive the Amiga.

### Fixed

* WHDLoad archives with file names containing characters Windows does not allow.
* ECS Denise: the background colour comes up white at power-on, as on the real chip.
* Interlaced screens: a line that turns interlace off no longer jumps one row on the short field.

## [0.6.0](https://github.com/zolfrik/amija-amiga-emulator/compare/v0.5.0...v0.6.0) - 2026-11-05

### Added

* **Rewind**: go back in time from the new Rewind section of the F12 popup.
* **Remote play spectator mode**: others can watch a session without playing.
* **Command line options**: settings and disk images can be given at launch.

### Changed

* Lower input and audio latency.
* F12 popup sections can be collapsed.

### Fixed

* Better AGA (A1200) accuracy.
* CIA interrupt timing on faster processors.
* Save state fixes.
* No more stuck keys when the window loses focus.
* Remote play and Windows packaging fixes.

## [0.5.0](https://github.com/zolfrik/amija-amiga-emulator/releases/tag/v0.5.0) - 2026-10-23

First public release.

