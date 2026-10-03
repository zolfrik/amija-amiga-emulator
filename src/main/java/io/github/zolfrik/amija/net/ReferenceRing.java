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

package io.github.zolfrik.amija.net;

/**
 * Ring of reference frames, kept identical on both sides of the session
 * ({@code docs/remote-play-plan.md} section 4.1).
 *
 * <h2>The mechanism that makes retransmissions, NACKs and periodic keyframes unnecessary</h2>
 * The host always compresses against <b>the last frame the guest confirmed having in full</b>.
 * If a frame is lost, the guest simply does not confirm it: the host notices and compresses the
 * next against an older reference. The delta is larger (measured: at most double, and less the
 * more expensive the scene already is) but <b>correct</b>. The system repairs itself and
 * degrades smoothly.
 *
 * <p>Four slots, indexed by {@code frameId % 4}: they hold the last four frames sent. If the
 * guest falls more than four frames behind, the reference it confirmed has already been evicted,
 * and then (and only then) a keyframe is sent.
 *
 * <p>Indexing by remainder is also what lets the guest <b>decode in place</b>: when
 * {@code frameId} and {@code refFrameId} fall in the same slot (i.e. are exactly 4 apart) the
 * decoding overwrites its own reference, which is safe by {@code FrameDecoder}'s contract and
 * also exactly what is wanted: that reference is about to leave the ring on both sides.
 */
public final class ReferenceRing {

    public static final int SLOTS = 4;

    private final long[] ids = new long[SLOTS];
    private final int[][] frames = new int[SLOTS][];
    private final int pixelCount;

    public ReferenceRing(int pixelCount) {
        this.pixelCount = pixelCount;
        for (int i = 0; i < SLOTS; i++) {
            ids[i] = -1;
            frames[i] = new int[pixelCount];
        }
    }

    private static int slot(long frameId) {
        return (int) Math.floorMod(frameId, SLOTS);
    }

    /** The buffer of a slot, valid or not: used to decode into it before validating it. */
    public int[] buffer(long frameId) {
        return frames[slot(frameId)];
    }

    /** The frame requested, or {@code null} if the ring no longer holds it. */
    public int[] get(long frameId) {
        int s = slot(frameId);
        return ids[s] == frameId ? frames[s] : null;
    }

    public boolean holds(long frameId) {
        return ids[slot(frameId)] == frameId;
    }

    /** Copies {@code pixels} into the slot of {@code frameId}, evicting what was there. */
    public void store(long frameId, int[] pixels) {
        int s = slot(frameId);
        System.arraycopy(pixels, 0, frames[s], 0, pixelCount);
        ids[s] = frameId;
    }

    /**
     * Declares valid the slot of {@code frameId}, whose buffer has already been filled through
     * {@link #buffer}. Avoids the copy on the guest side, which decodes straight into the ring.
     */
    public void commit(long frameId) {
        ids[slot(frameId)] = frameId;
    }

    public void clear() {
        for (int i = 0; i < SLOTS; i++) {
            ids[i] = -1;
        }
    }
}
