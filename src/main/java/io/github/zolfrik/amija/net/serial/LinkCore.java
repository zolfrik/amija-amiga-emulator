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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One end of a null modem cable between two machines: what keeps them in step.
 *
 * <p>Three rules, and nothing else:
 * <ul>
 *   <li>every event carries the line time at which its machine produced it, and is applied
 *       at the other end at exactly that time plus the delay of the cable, a constant of the
 *       session;</li>
 *   <li>each end tells the other its <i>horizon</i>, the line time before which it has
 *       already sent everything it will ever send;</li>
 *   <li>a machine never runs a cycle at or past the other's horizon plus the delay.</li>
 * </ul>
 * Whatever must be applied before that limit is therefore already here: no event can arrive
 * in the past, and what the two machines do depends on the delay and on their own input,
 * never on how long the transport took. A slow transport stops a machine at its limit; it
 * cannot change what the machine sees.
 *
 * <p>The emulation thread calls everything except {@link #receive} and
 * {@link #receiveHorizon}, which a transport may call from its own thread.
 */
public final class LinkCore {

    /** Where the events and the horizon of this end go: the other end, however it is reached. */
    public interface Transport {
        void send(LineEvent event);
        void sendHorizon(long horizon);
    }

    /** Sees every event at the instant it takes effect at this end, for tests and diagnostics. */
    public interface Recorder {
        void applied(long lineTime, LineEvent event);
    }

    private final LineClock clock;
    private final long delay;
    private final long peerHz;
    private final RemoteLine remote = new RemoteLine();
    private final ConcurrentLinkedQueue<LineEvent> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicLong peerHorizon = new AtomicLong(0);
    private Transport transport;
    private Recorder recorder;
    private long sentHorizon = -1;
    private long lateEvents;
    /** The horizon of the other end as of the last {@link #drain}: the one the limit is computed from. */
    private long usableHorizon;

    /**
     * @param clock  line time of this machine
     * @param delay  delay of the cable in nanoseconds of line time, the same at both ends
     * @param peerHz master cycles per second of the other machine
     */
    public LinkCore(LineClock clock, long delay, long peerHz) {
        if (delay <= 0) throw new IllegalArgumentException("the cable needs a delay");
        this.clock = clock;
        this.delay = delay;
        this.peerHz = peerHz;
    }

    public void setTransport(Transport t) { transport = t; }
    public void setRecorder(Recorder r) { recorder = r; }
    public LineClock clock() { return clock; }

    /**
     * Events that arrived after the instant at which they should have taken effect. The
     * window rule makes this impossible; a count above zero means a machine ran past its limit.
     */
    public long lateEvents() { return lateEvents; }
    public long delay() { return delay; }

    // -- this machine to the cable ------------------------------------------------

    /** This machine does something to the cable at master cycle {@code cycle}. */
    public void emit(LineEvent event) {
        if (transport != null) transport.send(event);
    }

    /**
     * Declares that everything this machine does before master cycle {@code cycle} has been
     * sent. Called as the machine advances; a value already declared is not sent again.
     */
    public void advance(long cycle) {
        long h = clock.lineTime(cycle);
        if (h > sentHorizon) {
            sentHorizon = h;
            if (transport != null) transport.sendHorizon(h);
        }
    }

    // -- the cable to this machine ------------------------------------------------

    /** An event of the other machine has arrived. Any thread. */
    public void receive(LineEvent event) { inbox.add(event); }

    /** The other machine's horizon has moved. Any thread. */
    public void receiveHorizon(long horizon) {
        peerHorizon.accumulateAndGet(horizon, Math::max);
    }

    /**
     * Takes in what has arrived. To be called before the machine runs.
     *
     * @return true if anything on the remote side of the cable changed
     */
    public boolean drain(long nowCycle) {
        boolean changed = false;
        LineEvent e;
        long now = clock.lineTime(nowCycle);
        // The horizon is read BEFORE the events are taken in: a transport queues the events
        // of a frame first and moves the horizon after, so everything a horizon read here
        // promises is already in the inbox. Read after, it could promise an event that is
        // still on its way to the queue.
        long horizon = peerHorizon.get();
        while ((e = inbox.poll()) != null) {
            if (e.time() + delay < now) lateEvents++;
            remote.add(e, delay, peerHz);
            if (recorder != null) recorder.applied(e.time() + delay, e);
            changed = true;
        }
        usableHorizon = horizon;
        remote.prune(now - 1);
        return changed;
    }

    /**
     * The master cycle this machine must not reach: the first one at or past the other's
     * horizon plus the delay of the cable. The horizon is the one seen by the last
     * {@link #drain}.
     */
    public long limitCycle() {
        return clock.cycleAt(usableHorizon + delay);
    }

    // -- the remote side as this machine sees it ----------------------------------

    public boolean rxd(long cycle) { return remote.level(clock.lineTime(cycle)); }

    public long nextSpace(long fromCycle) {
        long t = remote.nextSpace(clock.lineTime(fromCycle));
        return t == Long.MAX_VALUE ? t : Math.max(fromCycle, clock.cycleAt(t));
    }

    public boolean remoteDtr(long cycle) { return remote.dtr(clock.lineTime(cycle)); }
    public boolean remoteRts(long cycle) { return remote.rts(clock.lineTime(cycle)); }
}
