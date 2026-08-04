<p align="center">
  <img src="src/main/resources/icons/amija-128.png" alt="AmiJa" width="128" height="128">
</p>

<h1 align="center">AmiJa</h1>

<p align="center">
  A cycle-exact Amiga 500 emulator, written from scratch in Java.
</p>

---

AmiJa emulates an Amiga 500 at the level of the individual bus cycle: the 68000,
Agnus, Denise, Paula, the two 8520 CIAs, the Copper, the Blitter and the floppy
drives all advance together on the same 7.09 MHz master clock, and DMA slots are
arbitrated exactly as the real chipset does. That is what lets it run software
that depends on hardware timing — copy-protection loaders, demos and games that
reprogram the display mid-line.

It also started as an experiment: the point was to find out what AI coding
assistants can really do when they are pointed at a large, genuinely difficult
piece of software instead of a toy example. Cycle-exact emulation is an
unforgiving subject to try that on — there is one correct behaviour for every
one of the seven million cycles per second, oracles exist to check it against,
and a plausible-looking answer that is wrong shows up immediately as a black
screen.

**Features**

- OCS and ECS chipsets, PAL and NTSC
- floppy drives DF0–DF3 with reading *and* writing, ADF and IPF images
- hard disk support (`.hdf`, Zorro II autoconfig) and WHDLoad `.lha` auto-run
- save / load state
- audio, real joysticks (via GLFW), mouse, and full keyboard mapping
- software renderer or an OpenGL backend with RetroArch CRT shaders

## Download

Every release ships a **self-contained archive per system**. It already contains
the Java runtime, so there is nothing to install: unpack it and start it.

| System | File | How you start it |
|---|---|---|
| Windows x64 | `AmiJa-<version>-windows-x64.zip` | double-click `AmiJa.exe` |
| Linux x64 | `AmiJa-<version>-linux-x64.tar.gz` | run `bin/AmiJa` |
| Linux ARM64 | `AmiJa-<version>-linux-arm64.tar.gz` | run `bin/AmiJa` |
| macOS (Apple Silicon) | `AmiJa-<version>-macos-arm64.tar.gz` | open `AmiJa.app` |

> **Unpack into a folder you can write to.** AmiJa is portable by design: it keeps
> `amiga.properties`, `savestate/`, `screenshots/` and the WHDLoad caches *next to
> itself*, not in a system-wide application-data directory. Dropping the app into
> `C:\Program Files`, `/opt` or `/Applications` leaves it unable to save anything.
> Your home directory, or anywhere else you own, is fine.

If you already have a JDK 26, `AmiJa-<version>.zip` (no runtime, much smaller) and
the bare `AmiJa.jar` are published alongside — see [Requirements](#requirements).

**Windows on ARM.** There is no ARM64 archive: no JDK 26 exists for that platform
yet. Use the x64 one — Windows runs it under emulation, at some cost in speed.

**Windows.** `AmiJa.exe` is not signed — a code-signing certificate is a paid,
per-year subscription, and this project does not have one. Windows tags every file
that came from the internet with the *mark of the web*, and on an unsigned program
that tag makes Microsoft Defender SmartScreen open a blue *"Windows protected your
PC — SmartScreen prevented an unrecognised app from starting"* panel instead of
launching it. Nothing is wrong with the download; there is simply no signature to
vouch for it. Either way past it is a one-off:

- click **More info**, then **Run anyway**; or
- clear the mark on the **zip** *before* unpacking, which stops it being inherited
  by the extracted files: right-click the `.zip` → *Properties* → tick **Unblock**.
  In PowerShell it is one line:

  ```powershell
  Unblock-File AmiJa-<version>-windows-x64.zip
  ```

  Already unpacked? `Get-ChildItem -Recurse <folder> | Unblock-File` clears them all.

**Linux.** The bundled runtime brings the JVM, not the system libraries it draws
on. On a normal desktop these are already installed; on a bare-bones or container
system, install the equivalents of `libx11-6`, `libxext6`, `libxrender1`,
`libxtst6`, `libxi6`, `libxrandr2`, `libgl1` and `libasound2`.

**macOS.** The app is not signed with an Apple Developer ID — that is a paid
program, and this project does not have one. Gatekeeper therefore refuses the
first launch, usually with *"AmiJa is damaged and can't be opened"*. Clear the
quarantine flag once, and it will start normally from then on:

```bash
xattr -dr com.apple.quarantine AmiJa.app
```

## Requirements

To run the **jar** (rather than one of the archives above) you need a **JDK 26**
or newer. That is all: LWJGL is the only external dependency and it ships inside
the jar, natives included, for Windows, Linux and macOS (x64 and ARM64).

And a reasonably fast CPU. Accuracy is not free: AmiJa simulates every one of
the 7.09 million bus cycles a second, one at a time, rather than taking the
shortcuts a faster-but-approximate emulator can afford. The emulation runs on a
single thread, so what matters is single-core speed, not how many cores the
machine has — on a modern desktop or laptop it runs at full speed with room to
spare, while an old or low-power machine may fall below 100%.

## Running

Grab or build `AmiJa.jar` (see below) and start it:

```bash
java -jar AmiJa.jar
```

The only class with a `main()` is `io.github.zolfrik.amija.Amija`. Configuration
— Kickstart ROM, attached floppies, video mode, renderer, input devices — is read
from `amiga.properties`, and can be changed at runtime through the popup you reach
with **F12**.

That file, and everything else AmiJa reads or writes at runtime, lives in what the
emulator calls its **home**: the folder holding the jar or the native launcher. If
you run it some other way — from an IDE, from `mvn exec` — there is no such folder
to speak of, and the current working directory is used instead, exactly as it
always was.

On a first start no Kickstart ROM is configured, so AmiJa boots its own: a small
firmware written for this project, which ships with the emulator because it
contains no Commodore code at all. It starts floppy games whose boot block loads
the game by itself, which is a good part of the classic library.

It is not a full Kickstart — it has no AmigaDOS. Disks that hand over to
`dos.library`, hard-disk installs and WHDLoad all still need a real Kickstart:
press **F12** and point *Kickstart (.rom)* at your own ROM image. See
[User-supplied files](#user-supplied-files) below.

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
  (`shaders/`, `blurs/`, and the `floppysounds/` and `whdload/` placeholders).
  Unzip it anywhere and run the jar inside; `amiga.properties` is created on the
  first start.

To build one of the self-contained archives from the [Download](#download)
section instead — launcher, bundled Java runtime and all — run the packaging
script for your system:

```bash
packaging/build-image.sh                      # Linux, macOS
powershell -File packaging\build-image.ps1    # Windows
```

It runs `mvn package`, trims a runtime with `jlink`, builds a native app-image
with `jpackage`, and leaves the archive in `target/dist/`. `jpackage` cannot
cross-compile, so each archive has to be built on the system it targets; that is
what `.github/workflows/release.yml` does, one runner per target. Pass
`--smoke-test` (`-SmokeTest` on Windows) to have the script launch the finished
image from an unrelated directory and check that it still finds its own files.

There is no `src/test/` here: the test suite depends on material that cannot be
redistributed, so it stays in a private repository. `mvn package` therefore has
nothing to skip.

## User-supplied files

Some third-party material cannot be redistributed with AmiJa, so it is not in
this repository. Each item is optional: when it is missing, the corresponding
feature simply stays off — no errors. Put it in AmiJa's home folder, next to
`amiga.properties`:

| Path | What goes in it | Feature |
|---|---|---|
| *(configured)* `kickstart.path` | a Kickstart ROM image, 256 KB | AmigaDOS disks, hard disks, WHDLoad — floppy games with a self-loading boot block run on the built-in ROM |
| *(configured)* `drive.dfN.disk` | `.adf` / `.ipf` disk images | floppies |
| `floppysounds/` | `drive_click_A500.wav`, `drive_spin_A500.wav`, `drive_startup_A500.wav`, `drive_snatch_A500.wav` — e.g. from a WinUAE installation | drive sounds |
| `whdload/WHDLoad_files/` | the WHDLoad loader (`C/`, `S/`) from <https://www.whdload.de/>, plus the `.RTB` relocation tables in `Devs/Kickstarts/` for installs that emulate a different Kickstart | running `.lha` WHDLoad archives |

**AmiJa can fetch the last two for you.** Press **F12**, then *Optional files* →
*Download…*: the assistant downloads the drive sounds, WHDLoad and the `.RTB`
tables from their official sites and unpacks them where they belong. Nothing is
ever downloaded without an explicit request, and nothing is redistributed — each
file travels straight from its rights holder's server to your disk.

Kickstart ROMs and disk images are the exception: no automation can procure
those. ROMs are licensed legally through
[Amiga Forever](https://www.amigaforever.com/), and the games have to be yours.
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) records the licensing status of
every item above, and why none of them are bundled.

The `shaders/` and `blurs/` folders *are* included: they are the RetroArch GLSL
shader packs used by the OpenGL renderer.

## Contributing

Before adding anything binary, read
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The short version: no ROMs, no
disk images, no WHDLoad files, not even for testing; and every new Maven
dependency has to be compatible with GPL-3.0-or-later.

## License

AmiJa is free software, licensed under the **GNU General Public License,
version 3 or (at your option) any later version** — see [LICENSE](LICENSE).
You may use, study, modify and redistribute it; any distributed derivative
work must in turn be released under the same license, with its source code.

Third-party components bundled with the emulator (LWJGL, the shader packs, the
`virtualhd.device` boot ROM) keep their own licenses: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

AmiJa is an independent reimplementation and is not affiliated with, nor
endorsed by, Commodore, Amiga Corporation, Cloanto or Hyperion. *Amiga* is a
trademark of its respective owner.

## Acknowledgements

AmiJa would not have been possible without the projects below. Cycle-exact
emulation comes down to knowing what the hardware does in each of millions of
cycles per second, and that knowledge is not written in any manual: it was
measured, argued over and made public by other people, over decades. Whenever
AmiJa and a real Amiga disagreed, it was almost always one of these that showed
where the mistake was — and that is a debt worth stating plainly.

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
