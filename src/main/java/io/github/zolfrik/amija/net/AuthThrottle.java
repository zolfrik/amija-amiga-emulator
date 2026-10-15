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

import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How fast the join code may be got wrong, per address ({@code docs/remote-play-plan.md}
 * section 8).
 *
 * <h2>The code has ~40 bits, but attempts would otherwise be unlimited</h2>
 * The handshake is already expensive to guess (PBKDF2 with {@value SessionCrypto#PBKDF2_ROUNDS}
 * rounds), but that slowness alone does not close two different things:
 * <ul>
 *   <li><b>guessing</b>. An 8-symbol code is handy to read over the phone precisely because it
 *       is short, and the same session keeps it for as long as it hosts. Without a brake,
 *       whoever tries has all the hours of play available;</li>
 *   <li><b>making the victim work</b>. The derivation costs ~60 ms of CPU <i>on the host</i>,
 *       paid <b>before</b> knowing whether the code is right: the attacker chooses how much work
 *       to cause, for free. A loop of connections keeps the accepting thread busy and steals
 *       time from the emulation, which runs on the same machine.</li>
 * </ul>
 *
 * <h2>The brake grows, and does not touch whoever mistypes</h2>
 * The first {@value #FREE_ATTEMPTS} errors cost nothing: a code misread over the phone is the
 * normal case, and punishing it would turn a defence into a failure. After that, every error
 * doubles the wait ({@value #FIRST_BACKOFF_MS} ms, then double, up to {@value #MAX_BACKOFF_MS}),
 * and during that wait the refusal comes <b>before</b> the derivation, so it no longer costs
 * anything to whoever suffers it. A successful handshake resets everything: whoever has the
 * code does not carry the earlier errors along.
 *
 * <p>The count is <b>per address</b>. It is not a barrier against someone with many (the code's
 * length is that) but against the only attacker who always shows up from the same place, the
 * form in which the problem really exists on a home network.
 *
 * <p>The table is capped at {@value #MAX_TRACKED} addresses, cleared of expired entries before
 * growing: without that, it would be the memory an attacker can make it allocate.
 */
final class AuthThrottle {

    /** Errors that cost nothing: a code misread over the phone is fine. */
    static final int FREE_ATTEMPTS = 5;

    /** Wait after the first error past the threshold. Doubles at every further error. */
    static final long FIRST_BACKOFF_MS = 2_000;

    /** Ceiling of the wait: beyond it, the distracted user would be punished as much as the attacker. */
    static final long MAX_BACKOFF_MS = 5 * 60_000;

    /** After this much silence an address is clean again: yesterday's errors do not count. */
    static final long FORGET_MS = 30 * 60_000;

    /** Indirizzi seguiti contemporaneamente. */
    static final int MAX_TRACKED = 256;

    private static final class Entry {
        int failures;
        long blockedUntilNanos;
        long lastSeenNanos;
    }

    private final Map<InetAddress, Entry> entries = new ConcurrentHashMap<>();

    /** Time source, replaceable in tests: {@link System#nanoTime} in production. */
    private final java.util.function.LongSupplier clock;

    AuthThrottle() {
        this(System::nanoTime);
    }

    AuthThrottle(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Milliseconds left before this address may retry, or 0 if it may at once.
     *
     * <p>Must be called <b>before</b> the key derivation: that is where the cost lies that the
     * host must be spared.
     */
    long retryAfterMillis(InetAddress peer) {
        if (peer == null) {
            return 0;
        }
        Entry e = entries.get(peer);
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            long remaining = e.blockedUntilNanos - clock.getAsLong();
            return remaining > 0 ? (remaining / 1_000_000L) + 1 : 0;
        }
    }

    /** A handshake that did not complete: the next attempt may cost a wait. */
    void recordFailure(InetAddress peer) {
        if (peer == null) {
            return;
        }
        long now = clock.getAsLong();
        Entry e = entries.get(peer);
        if (e == null) {
            // The new entry is added only if there is room, and room is sought first by
            // throwing away the forgettable ones. If there is none, this address is not
            // tracked: see evictIfCrowded for why a live one is not evicted.
            evictIfCrowded(now);
            if (entries.size() >= MAX_TRACKED) {
                return;
            }
            e = entries.computeIfAbsent(peer, k -> new Entry());
        }
        synchronized (e) {
            e.lastSeenNanos = now;
            e.failures++;
            if (e.failures > FREE_ATTEMPTS) {
                int over = e.failures - FREE_ATTEMPTS;
                // 2s, 4s, 8s... and then the cap. The shift stops at 30 so it does not wrap.
                long backoff = FIRST_BACKOFF_MS << Math.min(over - 1, 30);
                if (backoff <= 0 || backoff > MAX_BACKOFF_MS) {
                    backoff = MAX_BACKOFF_MS;
                }
                e.blockedUntilNanos = now + backoff * 1_000_000L;
            }
        }
    }

    /** Successful handshake: whoever knows the code does not carry the earlier errors. */
    void recordSuccess(InetAddress peer) {
        if (peer != null) {
            entries.remove(peer);
        }
    }

    /** How many addresses are being tracked. Diagnostics. */
    int tracked() {
        return entries.size();
    }

    /**
     * Throws away the forgettable entries, and only if the table is full.
     *
     * <p>If after the cleanup it is still full, the new entry is <b>not</b> added at the expense
     * of an old one, and that is the choice that counts: evicting to make room would mean that
     * whoever is trying exhaustively can <b>get itself forgotten</b> by filling the table from
     * other addresses, i.e. the brake would disarm exactly under the attack it exists for.
     * Better to stop tracking newcomers: the cap is high enough that this happens only under
     * attack, and there the only thing that must hold is that whoever is already blocked stays
     * blocked. Whoever is not tracked is still not free to guess: ahead lie the ~40 bits of the
     * code and the ~60 ms of PBKDF2 per attempt.
     */
    private void evictIfCrowded(long now) {
        if (entries.size() < MAX_TRACKED) {
            return;
        }
        Iterator<Map.Entry<InetAddress, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            boolean stale;
            synchronized (e) {
                stale = now - e.lastSeenNanos > FORGET_MS * 1_000_000L
                        && e.blockedUntilNanos - now <= 0;
            }
            if (stale) {
                it.remove();
            }
        }
    }
}
