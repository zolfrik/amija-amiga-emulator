# Third-party notices

AmiJa is distributed under the **GNU General Public License v3.0 or later**
(see [LICENSE](LICENSE)). The components listed below are third-party work —
redistributed in this repository, bundled into the released archives, or fetched
on request: each one keeps its own licence. This document records them, it does
not change their terms.

---

## 1. Java dependencies (`pom.xml`)

LWJGL is the only external dependency: the emulator core uses nothing but the
JDK and Swing.

| Component | Version | Licence | Used for |
|---|---|---|---|
| LWJGL — `lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, `lwjgl-jawt` | 3.3.5 | BSD-3-Clause | OpenGL shader backend, real joysticks |
| LWJGL natives — `natives-{windows,linux,macos}` and `-arm64` variants | 3.3.5 | BSD-3-Clause | all bundled in the jar, so it stays portable |
| `org.lwjglx:lwjgl3-awt` | 0.2.3 | MIT | `AWTGLCanvas` (GL context inside the Swing window) |

Both licences are permissive and GPL-3.0 compatible: they ask for the copyright
notice to be preserved, which this document and the `META-INF/` entries LWJGL
carries inside the jar together satisfy.

---

## 2. Shaders — `shaders/` and `blurs/`

Taken from the **[libretro/glsl-shaders](https://github.com/libretro/glsl-shaders)**
collection (its `crt/` and `blurs/` folders), the same one RetroArch uses.

| Shader | Author | Licence |
|---|---|---|
| `crt-geom` | cgwg, Themaister, DOLLS (2010–2012) | GPL-2.0-or-later |
| `crt-pi` | davej (2015–2016) | GPL-2.0-or-later |
| `crt-consumer` (+ glow / linearize / ntsc passes) | DariusG (2022–2024) | GPL-2.0-or-later |
| `crt-royale` (+ `port-helpers/`, mask PNGs) | TroggleMonkey (2014) | GPL-2.0-or-later — text in `shaders/shaders/crt-royale/LICENSE.TXT` |
| `crt-geom-mini` | DariusG (2024–2026) | MIT |
| `gizmo-blur` | gizmo98 (2023) | GPL-2.0-or-later |
| `sharpsmoother` | guest(r) (2005–2017) | GPL-2.0-or-later |
| `hash-blur` | Hyllian / David Hoskins | MIT |
| `bilateral`, `blur-gauss-h/v`, `smart-blur` | libretro contributors | no per-file header: covered by the collection's GPL-2.0-or-later |

The *"or any later version"* wording is what makes every GPL-2 shader
combinable with the project's GPL-3. The shaders do **not** go into the jar:
they are text files read at run time from the `shaders/` and `blurs/` folders
in the working directory, and keep their own copyright headers.

**Adding more:** take them from `libretro/glsl-shaders` and check the `.glsl`
header first — you need either *"or any later version"* or a permissive licence
(MIT / public domain). A shader marked **GPL-2.0 only**, without "or later", is
not combinable with the GPL-3 and must be left out. A `.glslp` preset also needs
every `.glsl` and texture (`.png`) it references. Leave the copyright headers
inside the files intact.

---

## 3. `src/main/resources/hd/exprom.bin`

Boot ROM of the emulated `virtualhd.device`, taken from **AmiEmu** by mras0
(<https://github.com/mras0/amiemu>) and MIT licensed. Here is the notice, as
the licence requires:

> MIT License
>
> Copyright (c) 2022 Michael Rasmussen
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

The rest of `src/main/resources/` — `icons/`, `messages*.properties`,
`version.properties` — is project material under GPL-3.0-or-later.

---

## 4. Not included: files the user supplies

Whatever cannot be redistributed is not in this repository and not under
`src/main/resources/`, so it never reaches the jar. AmiJa reads these files from
folders in the working directory, which exist here empty with a single
`readme.txt` inside; when they are missing the feature turns itself off silently.

| Path | Origin | Status |
|---|---|---|
| Kickstart ROM *(set via `kickstart.path`)* | Commodore, © Cloanto / Amiga Corporation | user-supplied — licensed copies are sold with [Amiga Forever](https://www.amigaforever.com/) |
| ADF / IPF / HDF disk images | game publishers, proprietary | user-supplied |
| `floppysounds/*.wav` | WinUAE "drive sounds" plugin | no declared licence — downloadable (§5) |
| `whdload/WHDLoad_files/C`, `S` | WHDLoad, © 1995–2026 Bert Jahn | closed-source freeware — downloadable (§5) |
| `whdload/WHDLoad_files/Devs/Kickstarts/*.RTB` | SoftKick, © SinSoft / Pavel Troller | redistributable only as an intact archive — downloadable (§5) |
| `whdload/WHDLoad_files/Devs/Kickstarts/kick*` | ROM dumps matching the `.RTB` tables | user-supplied, as the Kickstart above |

Those are all the WHDLoad files that matter: **no AmigaOS binary is required**,
and every optional Aminet library (`reqtools`, `xpkmaster`, `xfdmaster`, …) is
left out — the ones AmiJa would be allowed to ship are optional anyway.

---

## 5. Download assistant

To spare the user a file hunt, AmiJa includes a download assistant (package
`io.github.zolfrik.amija.assets`) that, **only when the user explicitly asks**,
fetches the packages below from their official sites and unpacks them into the
right folders of AmiJa's home directory.

> AmiJa redistributes nothing. The file travels from the rights holder's server
> to the user's disk; the copy is made by the user, exactly as if they had
> clicked the link in a browser. We automate a click.

That is also why the assistant covers archives that are redistributable *only
if intact*: it downloads them **intact**, and the unpacking happens on the
user's own machine for their own use.

| Package | URL | Rights holder |
|---|---|---|
| drive sounds | `download.abime.net/winuae/stuff/drive_sounds.zip` | WinUAE plugin, no declared licence |
| WHDLoad | `whdload.de/whdload/WHDLoad_usr.lha` | © Bert Jahn |
| `.RTB` tables | `aminet.net/util/boot/skick346.lha` | SoftKick, © SinSoft / Pavel Troller |

Rules every entry in the manifest must respect:

1. **No implicit download.** Consent is explicit, per package, and the prompt
   shows URL, size and rights holder first.
2. **No circumvention.** The current sources are direct public downloads, with
   no registration and no paywall. Should one of them add either, the entry is
   removed and replaced by opening the browser on the URL.
3. **Official source.** The rights holder's own site or its declared channel
   (Aminet for Aminet material); never a mirror, a third-party archive or an
   abandonware site.
4. **Verify before installing.** SHA-256 recorded in the manifest plus a
   structural check of the expected entries. The hash is *indicative* — the URLs
   are not versioned, so an upstream release will legitimately change it: report
   the mismatch to the user, do not treat it as fatal.
5. **Speaking failures.** URLs rot. On error, print the URL and the destination
   folder so the user can do by hand what the assistant could not.
6. **Never Kickstart ROMs or disk images.** No automation may procure them: they
   stay the user's responsibility (§4).

---

## 6. Bundled Java runtime — the native distributions

The per-system archives (`AmiJa-<version>-windows-x64.zip`,
`…-linux-x64.tar.gz`, `…-linux-arm64.tar.gz`, `…-macos-arm64.tar.gz`) carry a
Java runtime inside them, in `runtime/` — that is the whole point of those
archives: the user installs no JDK. The plain `AmiJa.jar` and
`AmiJa-<version>.zip` do **not** contain one and this section does not apply to
them.

| Component | Version | Licence | Origin |
|---|---|---|---|
| OpenJDK runtime image (built with `jlink`) | Java 26 | GPL-2.0 **with the Classpath Exception** | [Eclipse Temurin](https://adoptium.net/), installed on the build runner by `actions/setup-java` |

The runtime is not a repackaging: `jlink` assembles it from the modules of that
same OpenJDK build, keeping only what AmiJa uses —
`java.base`, `java.desktop`, `java.net.http`, `jdk.unsupported`, `jdk.crypto.ec`
and `jdk.zipfs`.

**Why this is allowed.** OpenJDK is GPL-2.0 *with the Classpath Exception*. The
exception is what makes the combination lawful: it lets independent code link
against the runtime and be distributed under its own terms, so shipping a
GPL-3.0-or-later application next to a GPL-2.0+CPE runtime does not force either
licence onto the other. Each half keeps its own, and both are free software.

**What must be preserved.** `jlink` copies the licence texts of every included
module into `runtime/legal/` (~200 KB, 56 files). Those files are the notices
this section refers to: **they must stay inside every archive.** Do not prune
them to save space — with them the requirement is met, without them the
distribution is not compliant. The packaging scripts in `packaging/` never touch
that folder; nothing else should either.

Adoptium publishes no Temurin build for Windows on ARM64, hence the absence of a
`windows-arm64` archive (see the matrix comment in
`.github/workflows/release.yml`).
