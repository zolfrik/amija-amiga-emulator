<p align="center">
  <img src="logo/amija-256.png" alt="AmiJa" width="128" height="128">
</p>

<h1 align="center">AmiJa</h1>

<p align="center">
  A cycle-exact Amiga emulator in Java.
</p>

---

**Features**

- OCS, ECS and AGA chipsets, PAL and NTSC - AGA on an emulated A1200 board
- 68000, 68010 and 68020 processors
- automatic mode: the machine is configured from the media you load
- floppy drives DF0-DF3, ADF and IPF images, multi-disk sets in a .zip
- hard disk images (.hdf, Zorro II AutoConfig) and WHDLoad .lha auto-run
- five save-state slots and rewind
- audio, real joysticks, mouse and full keyboard mapping, with low input and audio latency
- software renderer or OpenGL backend with RetroArch shaders (.glslp, .slangp)
- remote play: a second player joins over the network without a Kickstart or a copy of the game, and spectators can watch
- integration with the Groovy MiSTer project
- command line for frontends: media files and any setting as `key=value`

See [CHANGELOG.md](CHANGELOG.md) for the changes in each release.

## Requirements

The Windows and Linux archives bring their own Java runtime. To run the
**jar** you need a **JDK 26** or newer.

## Running

Start `AmiJa.exe` (Windows) or `bin/AmiJa` (Linux). With the portable package,
or the jar:

```bash
java -jar AmiJa.jar
```

Without a configured Kickstart, AmiJa boots its own built-in firmware. It has
no AmigaDOS: Workbench, hard disk installs and WHDLoad require a real
Kickstart. Press F12 to configure it.

Every setting, key and command-line option is documented in
[USER_MANUAL.txt](USER_MANUAL.txt).

## Building

```bash
mvn clean package
```

produces two things:

- `target/AmiJa.jar` — a single executable jar with the code, the resources and
  all the dependencies inside;
- `target/AmiJa-<version>.zip` — the ready-to-run package: that jar, the
  documents, and the folders AmiJa reads from its home.

To build one of the self-contained archives run the packaging
script for your system:

```bash
packaging/build-image.sh                      # Linux
powershell -File packaging\build-image.ps1    # Windows
```

## License

AmiJa is free software, licensed under the **GNU General Public License,
version 3 or (at your option) any later version** — see [LICENSE](LICENSE).
You may use, study, modify and redistribute it; any distributed derivative
work must in turn be released under the same license, with its source code.

Third-party components bundled with the emulator (LWJGL, FlatLaf, the shader
packs, the `virtualhd.device` boot ROM) keep their own licenses: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

AmiJa is an independent reimplementation and is not affiliated with, nor
endorsed by, Commodore, Amiga Corporation, Cloanto or Hyperion. *Amiga* is a
trademark of its respective owner.

## Acknowledgements

AmiJa would not have been possible without the projects below.

- **[TomHarte-style JSON tests for the Motorola 68000](https://github.com/SingleStepTests/m68000)** - per-instruction test vectors.
- **[Scripted Amiga Emulator](https://github.com/naTmeg/ScriptedAmigaEmulator)** - a complete Amiga in JavaScript.
- **[vAmiga](https://github.com/dirkwhoffmann/vamiga)** by Dirk W. Hoffmann - Amiga emulator.
- **[vAmiga Test Suite](https://github.com/dirkwhoffmann/vAmigaTS)** - collection of test programs for vAmiga.
- **[WinUAE](https://github.com/tonioni/WinUAE)** - the reference Amiga emulator.

None of their code is part of AmiJa.
