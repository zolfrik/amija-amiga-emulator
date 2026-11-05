# Third-party notices

AmiJa is licensed under the GNU General Public License v3.0 or later
(see [LICENSE](LICENSE)). The third-party components below are redistributed in
this repository, bundled in the release archives, or downloaded on user request.
Each remains under its own licence.

---

## 1. Java dependencies

| Component | Version | Licence | Purpose |
|---|---|---|---|
| LWJGL — `lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, `lwjgl-jawt`, with natives for Windows, Linux and macOS (x64 and ARM64) | 3.3.5 | BSD-3-Clause | OpenGL backend, joysticks |
| `org.lwjglx:lwjgl3-awt` | 0.2.3 | MIT | OpenGL context in Swing |
| `com.formdev:flatlaf`, including its native libraries | 3.6 | Apache-2.0 | GUI look and feel |

All three licences are compatible with GPL-3.0. The shaded `AmiJa.jar` retains
only one `META-INF/LICENSE`; this document is the authoritative record. The
Apache-2.0 text is available at <https://www.apache.org/licenses/LICENSE-2.0>.

No other library is used. Networking, encryption, codecs and the UPnP client are
original code built on the JDK.

---

## 2. Shaders — `shaders/` and `blurs/`

Source: [libretro/glsl-shaders](https://github.com/libretro/glsl-shaders).
Shaders are read at run time and are not included in the jar. Copyright headers
are preserved in every file.

| Shader | Author | Licence |
|---|---|---|
| `crt-geom` | cgwg, Themaister, DOLLS (2010–2012) | GPL-2.0-or-later |
| `crt-pi` | davej (2015–2016) | GPL-2.0-or-later |
| `crt-consumer` and its passes | DariusG (2022–2024) | GPL-2.0-or-later |
| `crt-royale`, `port-helpers/`, mask textures | TroggleMonkey (2014) | GPL-2.0-or-later (`shaders/shaders/crt-royale/LICENSE.TXT`) |
| `crt-geom-mini` | DariusG (2024–2026) | MIT |
| `gizmo-blur` | gizmo98 (2023) | GPL-2.0-or-later |
| `sharpsmoother` | guest(r) (2005–2017) | GPL-2.0-or-later |
| `hash-blur` | Hyllian, David Hoskins | MIT |
| `bilateral`, `blur-gauss-h/v`, `smart-blur` | libretro contributors | GPL-2.0-or-later (collection licence) |

Only shaders licensed GPL-2.0-or-later or under a permissive licence may be
added; GPL-2.0-only material is incompatible with GPL-3.0.

### 2.1 `shaders/shaders_slang/`

Source: [libretro/slang-shaders](https://github.com/libretro/slang-shaders),
kept at upstream paths so that relative references resolve.

| Path | Author | Licence |
|---|---|---|
| `include/blur-functions.h`, `gamma-management.h`, `quad-pixel-communication.h`, `special-functions.h` | TroggleMonkey (2014) | MIT |
| `include/subpixel_masks.h` | hunterk | Public domain |
| `include/compat_macros.inc` | libretro contributors | GPL-2.0-or-later (collection licence) |
| `blurs/shaders/royale/blur9x9.slang`, `vertex-shader-blur-one-pass.h` | TroggleMonkey (2014) | MIT |
| `crt/zfast-crt.slangp`, `crt/shaders/zfast_crt/*` | Greg Hogan (SoltanGris42, 2017); Jose Linares (Dogway, 2023); metallic77 | GPL-2.0-or-later |

`crt/zfast-crt.slangp.params` is project material.

The Mega Bezel (GPL-3.0-or-later) and the packs built on it are not distributed;
`shaders/shaders_slang/bezel/` and `shaders/Mega_Bezel_Packs/` are installed by
the user. TheNamec's Commodore pack (CC BY-NC-ND 4.0) is incompatible with
GPL-3.0 and must never be redistributed, modified or repackaged.

---

## 3. Bundled resources

`src/main/resources/hd/exprom.bin` — boot ROM of the emulated
`virtualhd.device`, from [AmiEmu](https://github.com/mras0/amiemu):

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

All other resources — `icons/`, `messages*.properties`, `version.properties` —
are project material under GPL-3.0-or-later. The drive sounds in `floppysounds/`
are also project material: they are synthesised, not recorded
(see `floppysounds/readme.txt`).

---

## 4. User-supplied files

The following are not distributed with AmiJa. Features that need them are
disabled when they are absent.

| File | Rights holder | Status |
|---|---|---|
| Kickstart ROM | Cloanto / Amiga Corporation | User-supplied (available with [Amiga Forever](https://www.amigaforever.com/)) |
| ADF / IPF / HDF disk images | Respective publishers | User-supplied |
| `whdload/WHDLoad_files/C`, `S` | Bert Jahn | Freeware; downloadable (§5) |
| `whdload/WHDLoad_files/Devs/Kickstarts/*.RTB` | SinSoft / Pavel Troller (SoftKick) | Redistributable as an intact archive only; downloadable (§5) |
| `whdload/WHDLoad_files/Devs/Kickstarts/kick*` | Cloanto / Amiga Corporation | User-supplied |

No AmigaOS binary is required.

---

## 5. Download assistant

On explicit user request, AmiJa downloads the packages below directly from their
official sources and unpacks them locally. AmiJa itself redistributes none of
them.

| Package | URL | Rights holder |
|---|---|---|
| WHDLoad | `whdload.de/whdload/WHDLoad_usr.lha` | Bert Jahn |
| `.RTB` tables | `aminet.net/util/boot/skick346.lha` | SinSoft / Pavel Troller |

Every package must comply with these rules:

1. **Explicit consent** per package, after showing URL, size and rights holder.
2. **No circumvention.** Only direct public downloads; a source that adds
   registration or a paywall is replaced by a browser link.
3. **Official sources only** — no mirrors or third-party archives.
4. **Verification** by SHA-256 and archive structure. A hash mismatch is
   reported, not fatal, since upstream URLs are unversioned.
5. **Actionable errors** — on failure, show the URL and target folder.
6. **Never Kickstart ROMs or disk images.**

---

## 6. Bundled Java runtime

The native archives (`windows-x64`, `linux-x64`, `linux-arm64`) include a Java
runtime in `runtime/`. `AmiJa.jar` and the platform-independent zip do not.

| Component | Version | Licence | Origin |
|---|---|---|---|
| OpenJDK runtime image (`jlink`) | 26 | GPL-2.0 with Classpath Exception | [Eclipse Temurin](https://adoptium.net/) |

Modules included: `java.base`, `java.desktop`, `java.net.http`,
`jdk.unsupported`, `jdk.crypto.ec`, `jdk.zipfs`.

The Classpath Exception permits distributing AmiJa alongside the runtime under
its own licence. The licence notices in `runtime/legal/` are required and must
not be removed.

---

## 7. Project website (`gh-pages` branch)

Not part of any release archive.

| Component | Licence | Origin |
|---|---|---|
| `fonts/Topaz_a500_v1.0.ttf` | GPL with font exception | [rewtnull/amigafonts](https://github.com/rewtnull/amigafonts), dMG/t!s^dS! |
| `sounds/*.wav` | GPL-3.0-or-later | AmiJa (synthesised drive sounds) |
| Pages, styles, scripts, graphics | GPL-3.0-or-later | AmiJa |

The font licence is in `fonts/README.amigafonts.txt` and `fonts/LICENSE.txt`.
The Topaz typeface design is © Amiga Inc.
