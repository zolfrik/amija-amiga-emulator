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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Connects to the first reachable address of an invitation
 * ({@code docs/remote-play-r1-plan.md} section 1.3).
 *
 * <p>The attempts are staggered in the manner of "happy eyeballs" (RFC 8305): the local
 * network first, then IPv6, then public IPv4, the unverified addresses last, each one
 * {@value #STAGGER_MS} ms after the previous, or at once when the previous fails. Only the
 * <b>TCP connection</b> is raced: the first that connects wins and every other socket is closed
 * before a byte is written, so the host runs one handshake, not one per address.
 */
public final class CandidateDialer {

    /** Delay between the start of one attempt and the next while the first is pending. */
    static final int STAGGER_MS = 150;

    /** The winning connection, still silent, and the address it reached. */
    public record Connected(Socket socket, InviteCode.Candidate candidate) {
    }

    /** One address tried, and why it failed. */
    public record Attempt(InviteCode.Candidate candidate, IOException failure) {
    }

    /** No address answered; one line per candidate is in {@link #attempts()}. */
    public static final class AllFailedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient List<Attempt> attempts;

        AllFailedException(List<Attempt> attempts) {
            super("no address of the invitation answered");
            this.attempts = List.copyOf(attempts);
        }

        public List<Attempt> attempts() {
            return attempts;
        }

        /**
         * True if there was at least one public address and every public address timed out:
         * the host's port is not reachable from the Internet (firewall, router, or the
         * operator's carrier-grade NAT), as opposed to a host that refused.
         */
        public boolean publicUnreachable() {
            boolean any = false;
            for (Attempt a : attempts) {
                InviteCode.Scope s = a.candidate().scope();
                if (s == InviteCode.Scope.PUBLIC_VERIFIED || s == InviteCode.Scope.PUBLIC_UNVERIFIED) {
                    any = true;
                    if (!(a.failure() instanceof SocketTimeoutException)) {
                        return false;
                    }
                }
            }
            return any;
        }
    }

    private record Outcome(int index, Socket socket, IOException failure) {
    }

    /** The handshake run on a connected socket; it owns the socket from then on. */
    @FunctionalInterface
    public interface Handshaker<T> {
        T run(Socket socket, InviteCode.Candidate candidate) throws IOException;
    }

    /**
     * Connects and runs the handshake, moving to the next address when the handshake fails.
     *
     * <p>The first address that accepts a TCP connection is not necessarily the host: a
     * local-network address of the invitation can belong to another machine on the guest's own
     * network when both use the same private range. So a failed handshake does not end the
     * attempt; the remaining addresses are dialled again without the one that failed. Local
     * network addresses that are not on a network of this machine are not tried at all, unless
     * they are all the invitation has.
     *
     * @throws Handshake.RejectedException if no address completed the handshake and a host
     *                                     refused, with its reason
     * @throws AllFailedException          if no address answered at all
     */
    public static <T> T connect(List<InviteCode.Candidate> candidates, int timeoutMs,
                                Handshaker<T> handshaker) throws IOException {
        List<InviteCode.Candidate> remaining = reachable(candidates);
        List<Attempt> attempts = new ArrayList<>();
        IOException handshakeFailure = null;
        while (!remaining.isEmpty()) {
            Connected won;
            try {
                won = dial(remaining, timeoutMs);
            } catch (AllFailedException e) {
                attempts.addAll(e.attempts());
                break;
            }
            try {
                return handshaker.run(won.socket(), won.candidate());
            } catch (IOException e) {
                closeQuietly(won.socket());
                RemotePlaySettings.log("handshake with %s failed: %s",
                        won.candidate().endpoint(), e.getMessage());
                attempts.add(new Attempt(won.candidate(), e));
                // A refusal from a host is the most telling outcome; keep the first one.
                if (handshakeFailure == null
                        || (e instanceof Handshake.RejectedException
                            && !(handshakeFailure instanceof Handshake.RejectedException))) {
                    handshakeFailure = e;
                }
                remaining = new ArrayList<>(remaining);
                remaining.remove(won.candidate());
            }
        }
        if (handshakeFailure != null) {
            throw handshakeFailure;
        }
        throw new AllFailedException(attempts);
    }

    /**
     * The candidates worth dialling: a local-network address that is on none of this
     * machine's networks leads, at best, nowhere and, at worst, to a stranger.
     */
    static List<InviteCode.Candidate> reachable(List<InviteCode.Candidate> candidates) {
        List<InviteCode.Candidate> kept = new ArrayList<>(candidates.size());
        for (InviteCode.Candidate c : candidates) {
            if (c.scope() != InviteCode.Scope.LAN
                    || io.github.zolfrik.amija.net.nat.LocalAddresses.isLocalNetwork(c.address())) {
                kept.add(c);
            }
        }
        return kept.isEmpty() ? new ArrayList<>(candidates) : kept;
    }

    private CandidateDialer() {
    }

    /** The order the attempts start in; stable within a tier. */
    public static List<InviteCode.Candidate> order(List<InviteCode.Candidate> candidates) {
        List<InviteCode.Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(CandidateDialer::tier));
        return sorted;
    }

    private static int tier(InviteCode.Candidate c) {
        return switch (c.scope()) {
            case LAN, OVERLAY -> 0;
            case PUBLIC_VERIFIED -> c.isIpv6() ? 1 : 2;
            case PUBLIC_UNVERIFIED -> c.isIpv6() ? 3 : 4;
        };
    }

    /**
     * Opens a TCP connection to the first candidate that accepts one.
     *
     * @param timeoutMs overall limit; an attempt still pending at that point counts as timed out
     * @throws AllFailedException     if no candidate connected, with every cause
     * @throws InterruptedIOException if the calling thread is interrupted
     */
    public static Connected dial(List<InviteCode.Candidate> candidates, int timeoutMs)
            throws IOException {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no candidates");
        }
        List<InviteCode.Candidate> ordered = order(candidates);
        int n = ordered.size();
        Socket[] sockets = new Socket[n];
        IOException[] failures = new IOException[n];
        boolean[] finished = new boolean[n];
        LinkedBlockingQueue<Outcome> outcomes = new LinkedBlockingQueue<>();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        int started = 0;
        int pending = 0;
        long nextStart = System.nanoTime();
        try {
            while (true) {
                long now = System.nanoTime();
                if (started < n && (pending == 0 || now - nextStart >= 0)) {
                    int remaining = (int) Math.max(1, (deadline - now) / 1_000_000L);
                    sockets[started] = new Socket();
                    start(started, ordered.get(started), sockets[started], remaining, outcomes);
                    RemotePlaySettings.log("trying %s (%s)", ordered.get(started).endpoint(),
                            ordered.get(started).scope());
                    started++;
                    pending++;
                    nextStart = now + STAGGER_MS * 1_000_000L;
                    continue;
                }
                if (pending == 0) {
                    break;   // every candidate started and failed
                }
                long until = started < n ? Math.min(nextStart - now, deadline - now)
                                         : deadline - now;
                if (deadline - now <= 0) {
                    break;
                }
                Outcome o = outcomes.poll(Math.max(0, until), TimeUnit.NANOSECONDS);
                if (o == null) {
                    continue;   // time to start the next one, or the deadline
                }
                pending--;
                finished[o.index()] = true;
                if (o.socket() != null) {
                    // The winner. Nothing has been written on any socket yet.
                    sockets[o.index()] = null;
                    RemotePlaySettings.log("reached %s", ordered.get(o.index()).endpoint());
                    return new Connected(o.socket(), ordered.get(o.index()));
                }
                failures[o.index()] = o.failure();
                nextStart = System.nanoTime();   // a failure starts the next at once
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("connection cancelled");
        } finally {
            for (Socket s : sockets) {
                closeQuietly(s);
            }
        }
        List<Attempt> attempts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            IOException f = failures[i];
            if (f == null && i < started && !finished[i]) {
                f = new SocketTimeoutException("connect timed out");
            }
            if (f != null) {
                attempts.add(new Attempt(ordered.get(i), f));
            }
        }
        throw new AllFailedException(Collections.unmodifiableList(attempts));
    }

    private static void start(int index, InviteCode.Candidate c, Socket socket, int timeoutMs,
                              LinkedBlockingQueue<Outcome> outcomes) {
        Thread t = new Thread(() -> {
            try {
                socket.connect(new InetSocketAddress(c.address(), c.tcpPort()), timeoutMs);
                outcomes.add(new Outcome(index, socket, null));
            } catch (IOException e) {
                outcomes.add(new Outcome(index, null, e));
            }
        }, "amija-remoteplay-dial");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // A losing attempt: whatever state it is in, it is not wanted.
        }
    }
}
