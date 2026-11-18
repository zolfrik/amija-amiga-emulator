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

package io.github.zolfrik.amija.net.serial;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * What the other machine has put on the cable, as this end sees it: the level of its TXD and
 * its DTR and RTS outputs as functions of line time. Events are added with the delay of the
 * cable already applied; nothing here knows about the network.
 */
final class RemoteLine {

    /** A stretch of levels: {@code edge[k]} is where level k starts, {@code edge[bits]} where the stretch ends. */
    private record Stretch(long[] edge, int pattern, int bits) { }

    private record Change(long time, int value) { }

    private final ArrayDeque<Stretch> stretches = new ArrayDeque<>();
    private final List<Change> breaks = new ArrayList<>();
    private final List<Change> controls = new ArrayList<>();

    /** Adds an event of the other machine; {@code delay} is the delay of the cable, {@code hz} the sender's clock. */
    void add(LineEvent e, long delay, long hz) {
        long at = e.time() + delay;
        switch (e.kind()) {
            case LineEvent.TX -> {
                long[] edge = new long[e.bits() + 1];
                long cycles = 0;
                for (int k = 0; k <= e.bits(); k++) {
                    edge[k] = at + LineClock.cyclesToNs(cycles, hz);
                    cycles += k == 0 ? e.first() : e.bit();
                }
                stretches.addLast(new Stretch(edge, e.pattern(), e.bits()));
            }
            case LineEvent.BREAK -> breaks.add(new Change(at, e.pattern()));
            case LineEvent.CONTROL -> controls.add(new Change(at, e.pattern()));
            default -> throw new IllegalArgumentException("unknown line event " + e.kind());
        }
    }

    /** Forgets what ended before {@code now}; the state at {@code now} itself is kept. */
    void prune(long now) {
        while (stretches.size() > 1) {
            Stretch s = stretches.peekFirst();
            if (s.edge[s.bits] > now) break;
            stretches.removeFirst();
        }
        pruneChanges(breaks, now);
        pruneChanges(controls, now);
    }

    private static void pruneChanges(List<Change> list, long now) {
        while (list.size() > 1 && list.get(1).time <= now) list.remove(0);
    }

    private static int valueAt(List<Change> list, long time) {
        int v = 0;
        for (Change c : list) {
            if (c.time > time) break;
            v = c.value;
        }
        return v;
    }

    /** Level of the remote TXD at {@code time}: true is mark. */
    boolean level(long time) {
        if (valueAt(breaks, time) != 0) return false;
        Stretch hit = null;
        for (Stretch s : stretches) {
            if (s.edge[0] > time) break;
            hit = s;
        }
        if (hit == null || time >= hit.edge[hit.bits]) return true;
        int k = 0;
        while (time >= hit.edge[k + 1]) k++;
        return ((hit.pattern >>> k) & 1) != 0;
    }

    /** First line time, not before {@code from}, at which the remote TXD is low; MAX_VALUE if none is known. */
    long nextSpace(long from) {
        if (valueAt(breaks, from) != 0) return from;
        long best = Long.MAX_VALUE;
        for (Change c : breaks) {
            if (c.time > from && c.value != 0) {
                best = c.time;
                break;
            }
        }
        Stretch[] all = stretches.toArray(new Stretch[0]);
        for (int i = 0; i < all.length; i++) {
            Stretch s = all[i];
            long bound = i + 1 < all.length ? all[i + 1].edge[0] : Long.MAX_VALUE;
            for (int k = 0; k < s.bits; k++) {
                if (((s.pattern >>> k) & 1) == 0 && s.edge[k + 1] > from) {
                    long t = Math.max(s.edge[k], from);
                    if (t < bound && t < best) best = t;
                    break;
                }
            }
        }
        return best;
    }

    boolean dtr(long time) { return (valueAt(controls, time) & 1) != 0; }

    boolean rts(long time) { return (valueAt(controls, time) & 2) != 0; }

    void clear() {
        stretches.clear();
        breaks.clear();
        controls.clear();
    }
}
