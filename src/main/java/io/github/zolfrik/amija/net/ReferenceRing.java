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
 * <p>Four slots for the guest ({@link #SLOTS}), eight for the spectators
 * ({@link #SPECTATOR_SLOTS}), indexed by {@code frameId % slots}: they hold the last frames sent.
 * If the guest falls further behind than that, the reference it confirmed has already been
 * evicted, and then (and only then) a keyframe is sent.
 *
 * <p>Indexing by remainder is also what lets the guest <b>decode in place</b>: when
 * {@code frameId} and {@code refFrameId} fall in the same slot (i.e. are a ring apart) the
 * decoding overwrites its own reference, which is safe by {@code FrameDecoder}'s contract and
 * also exactly what is wanted: that reference is about to leave the ring on both sides.
 */
public final class ReferenceRing {

    /** Slots of the guest's ring: the guest confirms every frame it receives. */
    public static final int SLOTS = 4;

    /**
     * Slots of the spectators' ring. A spectator's report reaches the host a couple of frames
     * late, and the reference must be a frame <b>every</b> spectator still holds: with four slots
     * the margin is a single frame, and every loss would end in a keyframe.
     */
    public static final int SPECTATOR_SLOTS = 8;

    private final int slots;
    private final long[] ids;
    private final int[][] frames;
    private final int pixelCount;

    public ReferenceRing(int pixelCount) {
        this(pixelCount, SLOTS);
    }

    public ReferenceRing(int pixelCount, int slots) {
        if (slots < 1 || slots > 64) {
            throw new IllegalArgumentException("anello di " + slots + " posti");
        }
        this.slots = slots;
        this.pixelCount = pixelCount;
        this.ids = new long[slots];
        this.frames = new int[slots][];
        for (int i = 0; i < slots; i++) {
            ids[i] = -1;
            frames[i] = new int[pixelCount];
        }
    }

    public int slots() {
        return slots;
    }

    private int slot(long frameId) {
        return (int) Math.floorMod(frameId, (long) slots);
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
        return frameId >= 0 && ids[slot(frameId)] == frameId;
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

    /**
     * Which of the {@code slots} frames up to {@code newest} the ring holds: bit {@code i} is
     * frame {@code newest - i}.
     */
    public int heldMask(long newest) {
        int mask = 0;
        for (int i = 0; i < slots && newest - i >= 0; i++) {
            if (holds(newest - i)) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    public void clear() {
        java.util.Arrays.fill(ids, -1);
    }
}
