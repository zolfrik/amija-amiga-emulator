# Changelog

## [Unreleased]

### Added

* Hard disk images with their own filesystem (e.g. PFS3) and larger than 2 GB.
* Ready-made hard disk packages (AmigaVision, Amiga Game Selector) in automatic mode.

### Changed

* Remote play: the popup shows the port the router opened.

### Fixed

* Minor bugfixing.

## [0.9.0](https://github.com/zolfrik/amija-amiga-emulator/compare/v0.8.0...v0.9.0) - 2026-11-23

### Added

* **CDTV**: discs as .cue, .iso or zip, CD audio, the front panel and battery-backed memory.
* A built-in list of games that need settings other than their automatic profile.

### Changed

* Remote play: invitations, play over the Internet and a simpler setup.
* Drag and drop changes the media, never the machine.
* Automatic floppy profile: ECS with 1 MB of Chip RAM plus 512 KB of Slow RAM.

### Fixed

* CD32 disc recognition, CD audio and timing.
* Minor bugfixing.

## [0.8.0](https://github.com/zolfrik/amija-amiga-emulator/compare/v0.7.0...v0.8.0) - 2026-11-18

### Added

* **CD32**: discs as .cue, .iso or zip, CD audio, the CD32 pad, saved games in NVRAM.
* **Null modem**: a virtual serial cable between two AmiJa over the network.
* Full serial port emulation.
* Extended ADF images and ADFs of 81-83 cylinders.

### Changed

* Automatic mode takes one **Kickstart folder**: AmiJa recognises every ROM in it by its contents and picks the right one for each machine.

### Fixed

* The mouse moves the same distance at every window zoom.
* Floppy motor timing in a few loaders (Great Courts).
* Minor bugfixing.

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
