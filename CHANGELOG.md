# Changelog

## [0.7.0](https://github.com/zolfrik/amija-amiga-emulator/compare/v0.6.0...v0.7.0) - 2026-11-11

### Added

* Integration with the [Groovy_MiSTer](https://github.com/psakhis/Groovy_MiSTer) project, to send video and audio to a real CRT.

### Changed

* Cleaner audio output, with the A1200 output filter on AGA machines.

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
