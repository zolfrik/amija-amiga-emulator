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

package io.github.zolfrik.amija.custom.audio;

import java.util.concurrent.atomic.AtomicLong;

public final class AudioRingBuffer {

    public static final int CAPACITY = 1 << 15;
    private static final int MASK = CAPACITY - 1;

    private final float[] left = new float[CAPACITY];
    private final float[] right = new float[CAPACITY];

    private final AtomicLong writePos = new AtomicLong();
    private final AtomicLong readPos = new AtomicLong();
    private final AtomicLong overflows = new AtomicLong();

    public boolean write(float l, float r) {
        long wp = writePos.get();
        if (wp - readPos.get() >= CAPACITY) {
            readPos.lazySet(wp - CAPACITY + 1);
            overflows.incrementAndGet();
        }

        int idx = (int) (wp & MASK);
        left[idx] = l;
        right[idx] = r;
        writePos.lazySet(wp + 1);
        return true;
    }

    public int read(float[] outL, float[] outR, int count) {
        long rp = readPos.get();
        long available = writePos.get() - rp;
        int toRead = (int) Math.min(count, available);
        for (int i = 0; i < toRead; i++) {
            int idx = (int) ((rp + i) & MASK);
            outL[i] = left[idx];
            outR[i] = right[idx];
        }
        readPos.lazySet(rp + toRead);
        return toRead;
    }

    public long available() {
        return writePos.get() - readPos.get();
    }

    public long capacity() {
        return CAPACITY;
    }

    /** Total number of samples dropped on overflow (diagnostics). */
    public long overflowCount() {
        return overflows.get();
    }

    /**
     * Fill level of the buffer, normalised to [0,1]. 0 = empty (risk of underrun), 1 = full
     * (risk of overflow). Used by the ASR (Adaptive Sample Rate) algorithm to centre the
     * buffer.
     */
    public double fillLevel() {
        return (double) available() / CAPACITY;
    }

    public void clear() {
        long wp = writePos.get();
        readPos.set(wp);
    }
}
