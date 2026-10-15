<p align="center">
  <img src="src/main/resources/icons/amija-128.png" alt="AmiJa" width="128" height="128">
</p>

<h1 align="center">AmiJa</h1>

<p align="center">
  A cycle-exact Amiga 500 emulator, written from scratch in Java.
</p>

---

AmiJa emulates an Amiga 500 at the level of the individual bus cycle: all 
components advance together on the same 7.09 MHz master clock, and DMA slots are
arbitrated exactly as the real chipset does. 

The project has started as an experiment: the point was to find out what AI coding
assistants can really do when they are pointed at a large, genuinely difficult
piece of software. Cycle-exact emulation is an unforgiving subject for that.

This is a personal project; it isn't meant to compete with anyone. WinUAE and vAmiga 
are the work of decades of reverse engineering, and here they were the reference 
and the arbiter. The [Acknowledgements](#acknowledgements) at the bottom say what 
AmiJa owes them, and it owes them a great deal.

**Features**

- OCS and ECS chipsets, PAL and NTSC
- floppy drives DF0–DF3 with reading *and* writing, ADF and IPF images
- hard disk support (`.hdf`, Zorro II autoconfig) and WHDLoad `.lha` auto-run
- save / load state
- audio, real joysticks (via GLFW), mouse, and full keyboard mapping
- software renderer or an OpenGL backend with RetroArch CRT shaders — the
  `.glslp` presets are included, `.slangp` ones are supported and installed by you
- remote play: a second player joins over the network and takes one of the two
  game ports, without needing a Kickstart or a copy of the game

## Requirements

To run the **jar** you need a **JDK 26** or newer. You will also need a reasonably 
fast CPU; on a modern desktop or laptop it runs at full speed with room to spare, 
while an old or low-power machine may fall below 100%.

## Running

Grab or build `AmiJa.jar` (see below) and start it:

```bash
java -jar AmiJa.jar
```

On a first start no Kickstart ROM is configured, so AmiJa boots its own: a small
firmware written for this project, which ships with the emulator because it contains 
no Commodore code at all. It starts the good part of the classic library that boots 
straight from a self-loading boot block. It is not a full Kickstart — it has no 
AmigaDOS. Disks that hand over to `dos.library`, hard-disk installs, and WHDLoad 
all still need a real Kickstart.

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
| Kickstart ROM images (1.3 and 3.1)                                                                                                                                      | configured into `amiga.properties` | AmigaDOS disks, hard disks, WHDLoad — floppy games with a self-loading boot block run on the built-in ROM |
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
