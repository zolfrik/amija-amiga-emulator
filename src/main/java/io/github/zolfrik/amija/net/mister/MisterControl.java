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

package io.github.zolfrik.amija.net.mister;

import java.io.IOException;
import java.util.List;

/**
 * What the F12 popup can do to the MiSTer output ({@code docs/groovy-mister-plan.md} Phase 7).
 *
 * <p>The output lives outside the popup and survives its closing, as remote play does. Start
 * and stop act at once, with the popup still open, so the outcome can be read in
 * {@link #misterStatus()} while the user looks at it.
 */
public interface MisterControl {

    /** True while the output runs, connected to the MiSTer or retrying. */
    boolean isMisterRunning();

    /**
     * Starts the output with the {@code mister.*} values of the configuration.
     *
     * @return {@code null} if it started, otherwise the message to show the user
     */
    String startMister();

    /** Stops the output; the picture and the sound go back to the PC only. */
    void stopMister();

    /** Moves the picture on the tube of a running output; ignored otherwise. */
    void setMisterGeometry(GroovyModeline.Geometry geometry);

    /** A readable status line: off, connecting, connected, or why not. */
    String misterStatus();

    /**
     * Searches the PC's networks for MiSTers running the Groovy core. Blocks for a fraction of
     * a second; meant for when the output is stopped (see {@link MisterDiscovery}).
     */
    default List<MisterDiscovery.Found> searchMisters() throws IOException {
        return MisterDiscovery.search(MisterDiscovery.DEFAULT_TIMEOUT_MS);
    }
}
