/*
 * AmiJa - a cycle-exact Amiga 500 emulator in Java.
 * Copyright (C) 2026 zolfrik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.zolfrik.amija.assets;

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.config.AmigaConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Manifest of the download assistant: the list of third-party packages AmiJa can fetch,
 * with the official source of each.
 *
 * <p>None of these files is redistributed by AmiJa and none ends up in the jar: the
 * assistant fetches them from the rights holder's site at the user's explicit request. The
 * legal rationale, the licence verdicts and the rules for adding an entry are in
 * {@code THIRD-PARTY-NOTICES.md} sections 4 and 5.
 *
 * <p><b>Never</b> add Kickstart ROMs or disk images here: they remain the user's
 * responsibility and no automation can fetch them.
 *
 * <p>The SHA-256 values were checked by hand on 2026-08-01 by downloading the archives. They
 * are indicative: the URLs are not versioned (they always point to the latest release), so an
 * upstream update will legitimately change them; see {@link AssetPackage#sha256()}.
 *
 * <p>The drive sound samples were here until 2026-08-07, when AmiJa started bringing its
 * own: {@code floppysounds/} is full at installation and there is nothing to download.
 */
public final class AssetCatalog {

    /** Root of the WHDLoad resources, the same one {@code WhdLoadManager} reads. */
    private static final Path WHDLOAD_FILES = AppHome.resolve("whdload", "WHDLoad_files");

    private AssetCatalog() {}

    /**
     * The packages offered to the user, in the order they are shown.
     *
     * @param config configuration from which to read any URL overrides (key
     *               {@code assets.<id>.url}); may be {@code null}
     */
    public static List<AssetPackage> packages(AmigaConfig config) {
        return List.of(whdload(config), whdloadRtb(config));
    }

    /**
     * The packages <b>defined but not offered</b>: the two Mega Bezel ones, hidden from the
     * assistant since 2026-08-09.
     *
     * <p>Not a licensing question (those are in order and stay recorded in
     * {@code THIRD-PARTY-NOTICES.md} section 2.1) but a testing one: the Mega Bezel slang
     * chain has not been verified enough to be offered, and a package that downloads (up to
     * 347 MB) and then does not work is worse than a missing one. The definitions stay intact
     * and alive for exactly that reason: reopening them only takes putting them back in the
     * list of {@link #packages}, rebuilding nothing. Whoever wants to try them meanwhile can
     * install them by hand in the same destination folders described below.
     *
     * @param config as in {@link #packages(AmigaConfig)}
     */
    public static List<AssetPackage> hiddenPackages(AmigaConfig config) {
        return List.of(megaBezel(config), megaBezelCommodore(config));
    }

    // -------------------------------------------------------------------------
    // WHDLoad. The official archive contains C/, S/, Docs/ and the Install script; only C/
    // and S/ are kept: Docs/ is over 500 HTML documentation files, useless inside the
    // generated hard disk, and the .info icons serve no purpose in an unattended boot. The
    // S/WHDLoad.prefs installed here is rewritten anyway by WhdLoadManager with the one
    // generated from the config.
    // -------------------------------------------------------------------------
    private static AssetPackage whdload(AmigaConfig config) {
        return new AssetPackage(
                "whdload",
                url(config, "whdload", "https://whdload.de/whdload/WHDLoad_usr.lha"),
                "093333953737528d79c1eda7d21a16a0aa298698722624e7cfb31f588a0a156d",
                1_499_481L,
                "WHDLoad, © 1995-2026 Bert Jahn",
                true,
                AssetPackage.Format.LHA,
                WHDLOAD_FILES,
                WHDLOAD_FILES.resolve("C").resolve("WHDLoad"),
                List.of("WHDLoad/C/WHDLoad"),
                entry -> {
                    String rel = leafUnder(entry, "WHDLoad/", true);
                    if (rel == null) return null;
                    if (!rel.startsWith("C/") && !rel.startsWith("S/")) return null;
                    return rel.toLowerCase(Locale.ROOT).endsWith(".info") ? null : rel;
                });
    }

    // -------------------------------------------------------------------------
    // The .RTB relocation tables, required by the installs that use the kickemu. They are
    // NOT part of WHDLoad: they come from SoftKick, which the WHDLoad docs
    // (Docs/en/need.html) name as the source. The archive is redistributable only intact,
    // and indeed it is downloaded intact, and the user unpacks it on their own machine.
    // -------------------------------------------------------------------------
    private static AssetPackage whdloadRtb(AmigaConfig config) {
        final String prefix = "Kickstarts/";
        Path dest = WHDLOAD_FILES.resolve("Devs").resolve("Kickstarts");
        return new AssetPackage(
                "whdloadrtb",
                url(config, "whdloadrtb", "https://aminet.net/util/boot/skick346.lha"),
                "02b4d01852d12ab391c6469064f917221a0f7319fd0b3ba6c359403ec1d59f96",
                96_878L,
                "SoftKick, © SinSoft / Pavel Troller",
                true,
                AssetPackage.Format.LHA,
                dest,
                dest.resolve("kick34005.A500.RTB"),
                List.of(prefix + "kick34005.A500.RTB"),
                entry -> {
                    String leaf = leafUnder(entry, prefix);
                    return (leaf != null && leaf.endsWith(".RTB")) ? leaf : null;
                });
    }

    // -------------------------------------------------------------------------
    // HyperspaceMadness's Mega Bezel: the slang shader chain that draws a monitor around the
    // image, with a generated frame, reflections and phosphor mask. HIDDEN from the user; see
    // hiddenPackages(). It is GPLv3, redistributable, so here the choice of downloading it
    // instead of versioning it is not legal but about weight: 14.7 MB, 12 of them textures
    // alone, and those who do not use shaders do not need it.
    //
    // The zip has a root folder with the version number inside; the mapper removes it, so
    // the tree ends up in shaders/shaders_slang/bezel/Mega_Bezel/, where its #includes that
    // leave the package (../../../../include/, ../../../../blurs/) find the libretro common
    // files AmiJa brings with it.
    // -------------------------------------------------------------------------
    private static AssetPackage megaBezel(AmigaConfig config) {
        final String version = "Mega_Bezel_V1.17.1_2024-04-14";
        Path dest = AppHome.resolve("shaders", "shaders_slang", "bezel");
        return new AssetPackage(
                "megabezel",
                url(config, "megabezel",
                        "https://github.com/HyperspaceMadness/Mega_Bezel/releases/download/"
                        + version + "/" + version + ".zip"),
                null,
                14_730_259L,
                "HyperspaceMadness, GPL-3.0-or-later",
                false,
                AssetPackage.Format.ZIP,
                dest,
                dest.resolve("Mega_Bezel").resolve("Presets").resolve("MBZ__5__POTATO.slangp"),
                List.of(version + "/Mega_Bezel/Presets/MBZ__5__POTATO.slangp"),
                entry -> leafUnder(entry, version + "/", true));
    }

    // -------------------------------------------------------------------------
    // TheNamec's Commodore pack: photorealistic 4K frames for Amiga
    // 500/500+/600/1200/CD32/CDTV and the Commodore monitors, to be mounted on top of the
    // Mega Bezel AmiJa installs in shaders/shaders_slang/bezel/. HIDDEN from the user along
    // with the Mega Bezel; see hiddenPackages().
    //
    // It is the only entry of the catalogue AmiJa could not redistribute even if it wanted
    // to: it is CC BY-NC-ND 4.0, and the NonCommercial and NoDerivatives clauses are
    // incompatible with the project's GPL-3.0-or-later. Two consequences follow that must
    // NOT be "optimised" away:
    //   - it is unpacked VERBATIM (identity mapper). The other packages filter out useless
    //     entries; here filtering would produce a derivative work.
    //   - the destination is Mega_Bezel_Packs/thenamec-commodore-pack, and the folder name
    //     is constrained: the pack's presets climb seven levels to find
    //     shaders_slang/bezel/Mega_Bezel, so the root must be exactly two folders below
    //     shaders/.
    //
    // The sha256 is not fixed: the archive is 347 MB and was not downloaded to compute it.
    // The URL is anchored to the RC4.1 tag, though, so the content does not change under
    // our feet, and requiredEntries checks the integrity.
    // -------------------------------------------------------------------------
    private static AssetPackage megaBezelCommodore(AmigaConfig config) {
        Path dest = AppHome.resolve("shaders", "Mega_Bezel_Packs", "thenamec-commodore-pack");
        return new AssetPackage(
                "megabezelcommodore",
                url(config, "megabezelcommodore",
                        "https://github.com/TheNamec/megabezel-commodore-pack/releases/download"
                        + "/RC4.1/thenamec-commodore-pack-RC4.1_4K.zip"),
                null,
                347_570_318L,
                "TheNamec, CC BY-NC-ND 4.0",
                false,
                AssetPackage.Format.ZIP,
                dest,
                dest.resolve("presets").resolve("Commodore_Amiga500")
                    .resolve("Bezel_Beige-POTATO").resolve("MBZ_SHARP_POTATO")
                    .resolve("OVERLAY_CURVED_DAY.slangp"),
                List.of("presets/Commodore_Amiga500/",
                        "res/thenamec/shaders/base/mbz_5_potato_gdv/preset.slangp"),
                entry -> entry);
    }

    // -------------------------------------------------------------------------

    /** URL of the package: the default one, unless overridden in {@code amiga.properties}. */
    private static String url(AmigaConfig config, String id, String fallback) {
        return config == null ? fallback : config.assetUrl(id, fallback);
    }

    /** Like {@link #leafUnder(String, String, boolean)}, but only for non-nested entries. */
    private static String leafUnder(String entry, String prefix) {
        return leafUnder(entry, prefix, false);
    }

    /**
     * Part of {@code entry} that follows {@code prefix}, or {@code null} if the entry is not
     * under that prefix (or is the directory itself). With {@code nested=false} it also
     * discards entries in subfolders.
     */
    private static String leafUnder(String entry, String prefix, boolean nested) {
        if (!entry.startsWith(prefix)) return null;
        String rest = entry.substring(prefix.length());
        if (rest.isEmpty()) return null;
        return (nested || rest.indexOf('/') < 0) ? rest : null;
    }
}
