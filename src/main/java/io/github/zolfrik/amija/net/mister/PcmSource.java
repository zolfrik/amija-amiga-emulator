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

/** Where the audio sent to the MiSTer comes from. */
@FunctionalInterface
public interface PcmSource {

    /**
     * Fills {@code pcm} with exactly {@code frames} interleaved stereo S16LE samples at
     * {@link MisterOutput#AUDIO_RATE}, padding with silence what the emulation has not
     * produced yet.
     *
     * @return the samples that were real audio, not padding
     */
    int render(byte[] pcm, int frames);
}
