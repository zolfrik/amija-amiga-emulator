/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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

import io.github.zolfrik.amija.i18n.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Description of a third-party package the download assistant can fetch <b>on the user's
 * behalf</b>.
 *
 * <p>AmiJa redistributes nothing: the file travels from the rights holder's server to the
 * user's disk, exactly as if the user clicked the link in a browser. Every package stays
 * subject to its own licence; origin and holder are recorded in
 * {@code THIRD-PARTY-NOTICES.md} section 5, which also sets the six rules every new manifest
 * entry must follow (official source, no circumvention of registrations or paywalls, never
 * ROMs or disk images).
 *
 * @param id             stable identifier; the i18n keys {@code assets.<id>.name} and
 *                       {@code assets.<id>.desc} derive from it
 * @param url            download URL at the official source
 * @param sha256         fingerprint of the file checked by hand, in lower-case hex. It is
 *                       <b>indicative</b>: the URLs are not versioned, so an upstream release
 *                       will legitimately change it. A mismatch is to be reported, not
 *                       treated as a fatal error.
 * @param approxBytes    expected size, used for the progress bar when the server sends no
 *                       {@code Content-Length}
 * @param rightsHolder   rights holder, shown to the user before the download
 * @param preselected    whether the box starts ticked when the package is not installed yet.
 *                       It holds for what is needed to run games; the large, optional packages
 *                       (the shaders) start off, because "Download" must never start hundreds
 *                       of MB the user did not ask for
 * @param format         archive format
 * @param destDir        destination folder, relative to the working dir
 * @param probe          file whose presence means the package is already installed
 * @param requiredEntries entries the archive must contain; if they are missing, the downloaded
 *                       file is not the expected one and the installation stops
 * @param mapper         maps an archive entry to the destination path (relative to
 *                       {@code destDir}), or {@code null} to ignore it
 */
public record AssetPackage(
        String id,
        String url,
        String sha256,
        long approxBytes,
        String rightsHolder,
        boolean preselected,
        Format format,
        Path destDir,
        Path probe,
        List<String> requiredEntries,
        UnaryOperator<String> mapper) {

    /** Formato dell'archivio scaricato. */
    public enum Format { ZIP, LHA }

    public AssetPackage {
        requiredEntries = List.copyOf(requiredEntries);
    }

    /** Readable, localised name of the package. */
    public String displayName() {
        return Messages.t("assets." + id + ".name");
    }

    /** Localised description: what the package is for. */
    public String description() {
        return Messages.t("assets." + id + ".desc");
    }

    /** {@code true} if the package is already installed in the working dir. */
    public boolean installed() {
        return Files.isRegularFile(probe);
    }

    /**
     * Destination path (relative to {@link #destDir()}) for an archive entry, or {@code null}
     * if the entry is to be ignored.
     */
    public String map(String entry) {
        return mapper.apply(entry);
    }
}
