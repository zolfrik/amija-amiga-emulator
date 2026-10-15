<p align="center">
  <img src="logo/amija-256.png" alt="AmiJa" width="128" height="128">
</p>

<h1 align="center">AmiJa</h1>

<p align="center">
  A cycle-exact Amiga emulator in Java.
</p>

---

AmiJa emulates an Amiga at the level of the individual bus cycle: all
components advance together on the same 7.09 MHz master clock, and DMA slots are
arbitrated exactly as the real chipset does.

**Features**

- OCS, ECS and AGA chipsets, PAL and NTSC — AGA on an emulated A1200 board
- 68000, 68010 and 68020 processors
- an automatic mode that builds the right machine around whatever you load:
  a stock OCS Amiga for floppy games, an expanded one for hard disks and
  WHDLoad, an A1200 for AGA software
- floppy drives DF0–DF3 with reading *and* writing, ADF and IPF images, and
  multi-disk sets in a `.zip`
- hard disk support (`.hdf`, Zorro II autoconfig) and WHDLoad `.lha` auto-run
- save / load state, five slots with a thumbnail each
- audio, real joysticks (via GLFW), mouse, and full keyboard mapping
- software renderer or an OpenGL backend with RetroArch CRT shaders — the
  `.glslp` presets are included, `.slangp` ones are supported and installed by you
- remote play: a second player joins over the network and takes one of the two
  game ports, without needing a Kickstart or a copy of the game

## Download

Each release on the
[Releases page](https://github.com/zolfrik/amija-amiga-emulator/releases) comes
in these forms:

| System      | Archive                          | What is inside                                                           |
| ----------- | -------------------------------- | ------------------------------------------------------------------------ |
| Windows x64 | `AmiJa-<ver>-windows-x64.zip`    | `AmiJa.exe` and its own Java runtime: nothing else to install            |
| Linux x64   | `AmiJa-<ver>-linux-x64.tar.gz`   | the `bin/AmiJa` launcher and its own Java runtime                        |
| Linux ARM64 | `AmiJa-<ver>-linux-arm64.tar.gz` | the same, for ARM64                                                      |
| any         | `AmiJa-<ver>.zip`                | the portable `AmiJa.jar` with the same folders, for a JDK you already have |

Unpack the archive into a folder you can write to — not *Program Files* or
`/opt` — because AmiJa keeps its configuration, save states and screenshots
next to itself.

## Requirements

The Windows and Linux archives bring their own Java runtime. To run the
**jar** you need a **JDK 26** or newer. Either way you will also need a
reasonably fast CPU.

## Running

Start `AmiJa.exe` (Windows) or `bin/AmiJa` (Linux). With the portable package,
or a jar you built yourself (see below):

```bash
java -jar AmiJa.jar
```

On a first start no Kickstart ROM is configured, so AmiJa boots its own: a small
firmware written for this project, which ships with the emulator. It is not a full 
Kickstart — it has no AmigaDOS. Disks that hand over to `dos.library`, hard-disk 
installs, and WHDLoad all still need a real Kickstart.

The full guide to every setting and every key is in
[USER_MANUAL.txt](USER_MANUAL.txt).

## Building

```bash
mvn clean package
```

produces two things:

- `target/AmiJa.jar` — a single executable jar with the code, the resources and
  all the dependencies inside;
- `target/AmiJa-<version>.zip` — the ready-to-run package: that jar, the
  documents, and the folders AmiJa reads from its home
  (`shaders/`, `blurs/`, `floppysounds/`, and the `whdload/` placeholder).
  Unzip it anywhere and run the jar inside; `amiga.properties` is created on the
  first start.

To build one of the self-contained archives from the [Download](#download)
section instead — launcher, bundled Java runtime and all — run the packaging
script for your system:

```bash
packaging/build-image.sh                      # Linux
powershell -File packaging\build-image.ps1    # Windows
```

## User-supplied files

| Contents                                                                                                                                                                | Path                               | Feature                                                                                                   |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Kickstart ROM images (1.3 and 3.1; the A1200 release of 3.1 for AGA software)                                                                                           | configured into `amiga.properties` | AmigaDOS disks, hard disks, WHDLoad, AGA — floppy games with a self-loading boot block run on the built-in ROM |
| the WHDLoad loader (`C/`, `S/`) from <https://www.whdload.de/>, plus the `.RTB` relocation tables in `Devs/Kickstarts/` for installs that emulate a different Kickstart | `whdload/WHDLoad_files/`           | running `.lha` WHDLoad archives                                                                           |

**AmiJa can fetch WHDLoad for you.** Press **F12**, then *Optional files* →
*Download…*: the assistant downloads WHDLoad and the `.RTB` tables from their
official sites and unpacks them where they belong.

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

- **[TomHarte-style JSON tests for the Motorola 68000](https://github.com/SingleStepTests/m68000)**
  — per-instruction test vectors covering registers, flags, bus activity and
  cycle counts. The ground truth the CPU core was built against.
- **[Scripted Amiga Emulator](https://github.com/naTmeg/ScriptedAmigaEmulator)**
  by Rupert Hausberger — a complete Amiga in readable JavaScript, easy to
  instrument. Invaluable for comparing execution traces step by step.
- **[vAmiga](https://github.com/dirkwhoffmann/vamiga)** by Dirk W. Hoffmann —
  a meticulously written and documented chipset model; its clarity about *how*
  the Copper, the Blitter and the DMA arbiter actually behave is a reference
  in its own right.
- **[vAmiga Test Suite](https://github.com/dirkwhoffmann/vAmigaTS)** — the
  corpus of test programs that turns "it looks right" into a pass or a fail,
  and caught more regressions here than any other single thing.
- **[WinUAE](https://github.com/tonioni/WinUAE)** by Toni Wilen — the outcome
  of decades of reverse engineering, and the arbiter consulted whenever a
  timing question had no other answer.

None of their code is part of AmiJa: this is an independent implementation, and
any remaining inaccuracy is entirely its own. Thanks to their authors for doing
the hard work in the open.
