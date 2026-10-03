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
 * What the F12 popup can do to remote play ({@code docs/remote-play-plan.md} section 9).
 *
 * <h2>The dialog commands, it does not own</h2>
 * Server and session live in {@code Amija} and <b>survive the closing of the popup</b>: the
 * dialog switches them on and off and reads their state. If it owned them, closing the
 * window would close the game, and the popup, being modal, is exactly what the host keeps
 * opening and closing.
 *
 * <h2>Connecting yes, unmounting the machine no</h2>
 * {@link #startHosting()} and {@link #stopHosting()} act <b>at once</b>, with the popup still
 * open: hosting does not touch the machine, which keeps existing and running.
 *
 * <p>{@link #requestJoin} acts at once too (it really opens the connection), but what it does
 * <b>not</b> do is unmount the machine: a guest emulates nothing (section 3), and unmounting
 * a machine from inside a modal dialog, on the EDT, while the emulation thread runs, is the
 * way to discover a series of intermittent failures. So the session opens with the popup
 * still open, the video starts arriving, and the local machine is unmounted only when the
 * user <b>confirms with OK</b>: that transition is done by whoever owns the machine, where
 * it already does it for the cold restart.
 *
 * <p>The reason for this shape: if the button closed the popup and the connection happened
 * afterwards, <b>a failure would have no window left in which to be told</b>. Here the
 * attempt shows in {@link #status()} while it is in progress, and its outcome stays there
 * afterwards.
 */
public interface RemotePlayControl {

    /** True if this machine is serving (or waiting for) a guest. */
    boolean isHosting();

    /** True if a guest is connected now. */
    boolean hasGuest();

    /** True if a session to a host is open: this machine is its guest. */
    boolean isGuest();

    /**
     * The game port involved in remote play (0 or 1), or {@code -1} if no role is active.
     *
     * <p>Who fills it is said by {@link #isHosting()} and {@link #isGuest()}, and the meaning
     * changes accordingly: <b>hosting</b> it is the port given away, and its drop-down in the
     * popup must be off because the other side fills it; as a <b>guest</b> it is the only port
     * that can be filled, and it is the <i>other</i> drop-downs that mean nothing.
     *
     * <p>Two questions, one answer, on purpose: the assignment is <b>one</b> (the host decides
     * it, the guest receives it in the WELCOME), and two separate methods would be two truths
     * that can diverge.
     */
    int remoteGamePort();

    /**
     * True while a connection attempt is <b>in flight</b>.
     *
     * <p>The handshake can take seconds (TCP connection, PBKDF2 with 120k iterations, and up
     * to {@code Handshake.HANDSHAKE_TIMEOUT_MS} of waiting on every read), so it cannot happen
     * on the EDT: the popup would freeze exactly when it must say what is happening. This is
     * what the popup looks at to disable the button and show "connecting".
     */
    boolean isJoining();

    /** The code to pass to the other player, or {@code null} if not hosting. */
    String joinCode();

    /** Address to give to someone on the same network, e.g. {@code 192.168.1.20:7788}. */
    String lanAddress();

    /**
     * Public address, or {@code null} if not known <b>or not usable</b>.
     *
     * <p>The router tells it through UPnP (section 7.1). Until it has answered, or if it
     * answered with an address behind the operator's NAT, the row is not shown: better no
     * information than an address the other player could not reach, because a wrong address
     * sends people looking for the problem where it is not.
     */
    String internetAddress();

    /**
     * How the port opening on the router went, as a readable line, or {@code null} if there
     * is nothing to say.
     *
     * <p>Separate from {@link #status()} because it answers another question: that one says
     * whether someone is connected, this one whether someone <b>could</b> connect from
     * outside. Above all, the most important diagnosis (the CGNAT of section 7.3) is bad news
     * that arrives before anything has happened, and it must be said there, not kept for the
     * moment a "Connect" on the other side times out.
     */
    String routerStatus();

    /** A readable status line: connected, RTT, bandwidth, losses. */
    String status();

    /**
     * Starts hosting, at once.
     *
     * @return {@code null} if it started, otherwise the message to show the user
     */
    String startHosting();

    /** Stops hosting, at once. A connected guest is said goodbye to and disconnected. */
    void stopHosting();

    /**
     * Starts connecting to a host, <b>in the background</b>: returns at once, and the popup
     * stays open to tell how it goes.
     *
     * <p>The outcome arrives two ways, and both are needed: {@link #status()} says it while the
     * user watches, and a failure also makes an error message appear, because the user may
     * have pressed and looked away, and a status line nobody watches has warned nobody.
     *
     * @return {@code null} if the attempt started, otherwise what is wrong with the
     *         parameters (empty address, malformed code): that is known at once and is not
     *         worth opening a socket to find out
     */
    String requestJoin(String address, String joinCode);

    /**
     * Leaves the session.
     *
     * @return {@code true} if getting back to being a machine requires <b>closing the
     *         popup</b>: this machine is a full guest with nothing left to emulate, so it
     *         must be rebuilt, and rebuilding it from inside a modal dialog is exactly what
     *         is avoided. {@code false} if instead the session had been opened from the
     *         popup and the local machine was never unmounted: there is nothing to rebuild
     *         and the popup can stay open.
     */
    boolean requestLeave();
}
