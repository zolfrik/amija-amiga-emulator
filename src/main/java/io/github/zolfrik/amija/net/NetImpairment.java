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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <b>Simulated</b> loss, delay and jitter on the media channel ({@code docs/remote-play-plan.md}
 * section 13).
 *
 * <h2>Why it is indispensable and not a luxury</h2>
 * Nothing is ever lost on a loopback. But all of this protocol's robustness (the reference to
 * the last ACK, the all-or-nothing reassembly, the fallback keyframe) is <b>code that never
 * runs on a perfect loopback</b>. Without a way to break the link on command, those branches
 * would stay untested until the first real game on a real network, exactly when they cannot
 * be observed.
 *
 * <p>Applied on <b>output</b>, right before the {@code send}: it does not matter on which side a
 * packet is dropped, and so the receiver stays pure production code, without a single test
 * {@code if} on the hot path.
 *
 * <p>Configured from the command line, following the project convention for diagnostic
 * properties (only {@code -D}, never in the popup):
 * <pre>
 *   -Damija.remoteplay.simloss=0.05      fraction of datagrams dropped (0..1)
 *   -Damija.remoteplay.simlatency=60     base delay, ms
 *   -Damija.remoteplay.simjitter=10      random variation on the delay, ms
 * </pre>
 *
 * <p>{@link #NONE} is not a configuration with zero values: it is an object that does
 * <i>nothing</i>, allocates no scheduler and consults no random generator. When the
 * simulation is off (i.e. always, except in tests) the cost is a virtual call the JIT
 * devirtualises.
 */
public class NetImpairment {

    /** No alteration: the normal case, and it must cost nothing. */
    public static final NetImpairment NONE = new NetImpairment();

    private NetImpairment() {
    }

    /** Sends, or simulates the fate the link would have given the datagram. */
    public void send(DatagramSocket socket, DatagramPacket packet) throws IOException {
        socket.send(packet);
    }

    /** Stops the scheduler, if one was created. */
    public void close() {
    }

    /** True if something is altered: used only to decide what to write in the logs. */
    public boolean active() {
        return false;
    }

    /**
     * Suspends or resumes the alteration, with the session open.
     *
     * <p>Used by the tests that must <b>close cleanly</b>: the guarantee of section 4.2 is that
     * inner gaps become silence of the right length, but the last block of a test, if lost,
     * has no successor that could notice. Switching the loss off for the tail makes exact an
     * assertion that would otherwise be a coin toss, instead of weakening it until it says
     * nothing.
     */
    public void setEnabled(boolean enabled) {
    }

    /**
     * Reads the configuration from the {@code -D} properties.
     *
     * @return {@link #NONE} if no property is set
     */
    public static NetImpairment fromSystemProperties() {
        double loss = doubleProperty("amija.remoteplay.simloss", 0);
        int latency = (int) doubleProperty("amija.remoteplay.simlatency", 0);
        int jitter = (int) doubleProperty("amija.remoteplay.simjitter", 0);
        return of(loss, latency, jitter);
    }

    /**
     * @param lossProbability fraction of datagrams to drop, 0..1
     * @param latencyMs       base delay applied to every datagram
     * @param jitterMs        random variation <b>added</b> to the delay, 0..jitterMs
     */
    public static NetImpairment of(double lossProbability, int latencyMs, int jitterMs) {
        if (lossProbability <= 0 && latencyMs <= 0 && jitterMs <= 0) {
            return NONE;
        }
        return new Simulated(lossProbability, latencyMs, jitterMs);
    }

    private static double doubleProperty(String name, double fallback) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[NET] valore non numerico per " + name + ": " + v);
            return fallback;
        }
    }

    // =====================================================================

    private static final class Simulated extends NetImpairment {

        private final double lossProbability;
        private final int latencyMs;
        private final int jitterMs;
        private final Random random = new Random();
        private final ScheduledExecutorService scheduler;
        private volatile boolean enabled = true;
        private long dropped;
        private long delayed;

        Simulated(double lossProbability, int latencyMs, int jitterMs) {
            this.lossProbability = lossProbability;
            this.latencyMs = Math.max(0, latencyMs);
            this.jitterMs = Math.max(0, jitterMs);
            this.scheduler = (this.latencyMs > 0 || this.jitterMs > 0)
                    ? Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "amija-net-impairment");
                        t.setDaemon(true);
                        return t;
                    })
                    : null;
            System.out.printf("[NET] impairment simulato: perdita %.1f%%, ritardo %d ms"
                    + " (+jitter %d ms)%n", lossProbability * 100, this.latencyMs, this.jitterMs);
        }

        @Override
        public void send(DatagramSocket socket, DatagramPacket packet) throws IOException {
            if (!enabled) {
                socket.send(packet);
                return;
            }
            if (lossProbability > 0 && random.nextDouble() < lossProbability) {
                dropped++;
                return;
            }
            if (scheduler == null) {
                socket.send(packet);
                return;
            }
            // The DatagramPacket and its array are reused by the caller at every send: handing
            // it to the scheduler as it is, the content of another packet would leave. The copy
            // is the price of the simulation, and it is only here.
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, copy.length);
            DatagramPacket delayedPacket =
                    new DatagramPacket(copy, copy.length, packet.getSocketAddress());
            long delay = latencyMs + (jitterMs > 0 ? random.nextInt(jitterMs + 1) : 0);
            try {
                scheduler.schedule(() -> {
                    try {
                        socket.send(delayedPacket);
                    } catch (IOException ignored) {
                        // The socket closed while the packet was waiting: the end of the
                        // session, not a failure to report.
                    }
                }, delay, TimeUnit.MILLISECONDS);
                delayed++;
            } catch (RejectedExecutionException e) {
                // The session is closing and the scheduler has already stopped. A packet that
                // does not leave during the close is a lost packet, which is exactly what this
                // class simulates: counting it as such is the truth, an exception would be
                // noise on a thread about to end anyway.
                dropped++;
            }
        }

        @Override
        public boolean active() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void close() {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            System.out.printf("[NET] impairment: %d datagrammi buttati, %d ritardati%n",
                    dropped, delayed);
        }
    }
}
