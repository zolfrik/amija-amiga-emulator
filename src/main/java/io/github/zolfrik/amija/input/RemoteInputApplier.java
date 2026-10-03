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

package io.github.zolfrik.amija.input;

import io.github.zolfrik.amija.net.FeedbackPacket;
import io.github.zolfrik.amija.net.GuestInputTarget;

/**
 * Host side of the input: applies to the machine the state sent by the guest
 * ({@code docs/remote-play-plan.md} section 4.3).
 *
 * <h2>One place, once per frame, on the right thread</h2>
 * This class is <b>not</b> called by the network thread. The network thread only deposits
 * the last authenticated state; the <b>emulation thread</b> collects it, at the frame
 * boundary, always in the same place. Two reasons, both important: no concurrent writes to
 * the machine state are introduced, and the quantisation point is reproducible, which is
 * also how local input behaves, since the EDT delivers it in bursts anyway.
 *
 * <h2>Applying a state is not replaying events</h2>
 * What arrives is a snapshot, not a story. It is applied by difference:
 * <ul>
 *   <li><b>keys</b>: {@code bitmap ^ lastBitmap}, and press or release on the bits that
 *       changed. That makes a stuck key impossible: if a packet is lost, the next one still
 *       carries the truth and the difference recovers it;</li>
 *   <li><b>mouse</b>: the <b>signed difference</b> of the cumulative counters, which handles
 *       the 32-bit wrap by itself and recovers the movement of lost packets;</li>
 *   <li><b>joystick and buttons</b>: pure state, simply written;</li>
 *   <li><b>the device plugged in</b>: likewise, and the guest states it. The host assigned
 *       the port, but what to plug into it (mouse, joystick, nothing) the guest chooses in
 *       its own popup, even mid-game. It is set <b>last</b>, after the gestures, and that
 *       matters: the gestures are a <i>difference</i> from the previous packet, so they may
 *       concern the previous device, and {@code GamePort} infers its own from what is done to
 *       it. Releasing fire and emptying the port at the same instant would otherwise leave a
 *       stationary joystick instead of an empty port.</li>
 * </ul>
 *
 * <h2>The trust boundary is here</h2>
 * The guest can send any bitmap: the packet is 44 bytes and anyone can write one. So the
 * prohibitions are <b>here</b>, not on the guest:
 * <ul>
 *   <li><b>CTRL+Amiga+Amiga does not reset someone else's machine</b>, unless the host
 *       explicitly allows it. The filter does not deny the three keys (each is needed alone
 *       by half the programs in the world) but <b>denies the triple</b>: when all three would
 *       be held together, the right Amiga is not delivered. Forbidding the keys would
 *       mutilate the keyboard to prevent a combination;</li>
 *   <li><b>the keyboard is shared only if the host granted it</b>: with
 *       {@code keyboardShared} off, only the game port comes from the guest.</li>
 * </ul>
 * The host's local hotkeys (F12 above all, which would open a modal dialog on someone
 * else's machine) do not even come through here: {@code Amija} intercepts them before the
 * machine's adapters, and the guest writes only to the latter.
 */
public final class RemoteInputApplier implements GuestInputTarget {

    private static final int KEY_COUNT = 128;

    /** The three keys of the reset triple (see {@code Keyboard.KEY_*}). */
    private static final int KEY_CTRL = 0x63;
    private static final int KEY_L_AMIGA = 0x66;
    private static final int KEY_R_AMIGA = 0x67;

    /** The triple, to walk through when looking for the key that would complete it. */
    private static final int[] RESET_KEYS = { KEY_CTRL, KEY_L_AMIGA, KEY_R_AMIGA };

    private final GamePortControls port;
    private final AmigaKeyTarget keyboard;
    private final boolean keyboardShared;
    private final boolean allowReset;

    private final byte[] lastKeys = new byte[FeedbackPacket.KEY_BYTES];
    private int lastMouseX;
    private int lastMouseY;
    private int lastButtons;
    private int lastJoyX;
    private int lastJoyY;
    private int lastSessionGen;
    private boolean started;

    private long applied;
    private long resetCombosBlocked;

    /**
     * @param port           the game port assigned to the guest (default: 1, the joystick one)
     * @param keyboard       the Amiga keyboard, or {@code null} not to share it
     * @param allowReset     whether the guest may reset the machine with CTRL+Amiga+Amiga
     */
    public RemoteInputApplier(GamePortControls port, AmigaKeyTarget keyboard,
                              boolean allowReset) {
        this.port = port;
        this.keyboard = keyboard;
        this.keyboardShared = keyboard != null;
        this.allowReset = allowReset;
    }

    /**
     * Applies the state. <b>Called by the emulation thread, once per frame.</b>
     */
    @Override
    public void apply(FeedbackPacket state) {
        if (!started || state.sessionGen != lastSessionGen) {
            // New session (or first application): the mouse counters start from another
            // origin, and taking their difference would teleport the pointer. Start again
            // from the current values, i.e. from no movement.
            resetBaseline(state);
        }

        int dx = state.mouseCounterX - lastMouseX;   // signed: gestisce l'avvolgimento
        int dy = state.mouseCounterY - lastMouseY;
        if (dx != 0 || dy != 0) {
            port.moveMouse(dx, dy);
            lastMouseX = state.mouseCounterX;
            lastMouseY = state.mouseCounterY;
        }

        if (state.joyX != lastJoyX || state.joyY != lastJoyY) {
            port.setJoystickAxes(state.joyX, state.joyY);
            lastJoyX = state.joyX;
            lastJoyY = state.joyY;
        }

        int changed = state.buttons ^ lastButtons;
        if (changed != 0) {
            if ((changed & FeedbackPacket.BUTTON_FIRE) != 0) {
                port.pressJoystickFire((state.buttons & FeedbackPacket.BUTTON_FIRE) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_2) != 0) {
                port.pressJoystickButton2((state.buttons & FeedbackPacket.BUTTON_2) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_3) != 0) {
                port.pressJoystickButton3((state.buttons & FeedbackPacket.BUTTON_3) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_MOUSE_LEFT) != 0) {
                port.pressMouseLeft((state.buttons & FeedbackPacket.BUTTON_MOUSE_LEFT) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_MOUSE_RIGHT) != 0) {
                port.pressMouseRight((state.buttons & FeedbackPacket.BUTTON_MOUSE_RIGHT) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_MOUSE_MIDDLE) != 0) {
                port.pressMouseMiddle((state.buttons & FeedbackPacket.BUTTON_MOUSE_MIDDLE) != 0);
            }
            lastButtons = state.buttons;
        }

        // What the guest has plugged in, and LAST: the releases just applied are the
        // difference from the previous packet, so they may concern the PREVIOUS device, and
        // GamePort infers its own from what is done to it. Letting the packet speak last means
        // that releasing fire and emptying the port at the same instant gives an empty port,
        // not a stationary joystick.
        port.setDevice(hardwareDevice(state.portDevice));

        if (keyboardShared) {
            applyKeys(state);
        }
        applied++;
    }

    /**
     * Brings the machine back to rest: everything released, and the port <b>empty</b>.
     *
     * <p>Must be called when the guest leaves, or the last key it pressed stays pressed
     * forever, not because the protocol lost something but because no state arrives any more
     * to contradict it.
     *
     * <p>The device leaves with the guest too: whoever kept it plugged in is gone, and a port
     * that kept declaring a stationary joystick would be a lie the games read. The port stays
     * <b>reserved</b>, though: the host does not take it back while hosting, because another
     * guest may arrive.
     */
    @Override
    public void releaseAll() {
        port.setJoystickAxes(0, 0);
        port.pressJoystickFire(false);
        port.pressJoystickButton2(false);
        port.pressJoystickButton3(false);
        port.pressMouseLeft(false);
        port.pressMouseRight(false);
        port.pressMouseMiddle(false);
        // After the releases, not before: each of them makes GamePort infer a device.
        port.setDevice(GamePortDevice.NONE);
        if (keyboardShared) {
            for (int k = 0; k < KEY_COUNT; k++) {
                if ((lastKeys[k >> 3] & (1 << (k & 7))) != 0) {
                    keyboard.releaseKey(k);
                }
            }
        }
        java.util.Arrays.fill(lastKeys, (byte) 0);
        lastButtons = 0;
        lastJoyX = 0;
        lastJoyY = 0;
        started = false;
    }

    /** How many times the guest state has been applied. */
    public long applied() {
        return applied;
    }

    /** How many times the reset triple was denied: diagnostics, not an error. */
    public long resetCombosBlocked() {
        return resetCombosBlocked;
    }

    // =====================================================================

    private void applyKeys(FeedbackPacket state) {
        int blocked = allowReset ? -1 : keyToWithhold(state.keys);
        if (blocked >= 0) {
            resetCombosBlocked++;
        }
        for (int b = 0; b < FeedbackPacket.KEY_BYTES; b++) {
            int now = state.keys[b] & 0xFF;
            if (blocked >= 0 && b == (blocked >> 3)) {
                now &= ~(1 << (blocked & 7));
            }
            int diff = now ^ (lastKeys[b] & 0xFF);
            if (diff == 0) {
                continue;
            }
            for (int bit = 0; bit < 8; bit++) {
                if ((diff & (1 << bit)) == 0) {
                    continue;
                }
                int raw = (b << 3) | bit;
                if ((now & (1 << bit)) != 0) {
                    keyboard.pressKey(raw);
                } else {
                    keyboard.releaseKey(raw);
                }
            }
            lastKeys[b] = (byte) now;
        }
    }

    /**
     * Which key of the reset triple must not be delivered, or {@code -1} if none.
     *
     * <h2>Two ways to form the triple, not one</h2>
     * The obvious case is the guest holding all three keys: there <b>only</b> the right Amiga
     * is denied, because forbidding the three keys would mutilate the keyboard to prevent a
     * combination, and CTRL and the left Amiga are needed alone by half the programs in the
     * world.
     *
     * <p>The less obvious case is the triple <b>split across the two keyboards</b>: the host
     * holds CTRL and one Amiga key on its own account (it happens, they are common
     * modifiers) and the guest only has to bring the third key. None of its packets contains
     * the triple, so checking the received bitmap alone sees nothing strange, yet someone
     * else's machine resets. The missing piece is the host's state, which this object does
     * not have: it is asked of the keyboard, the only one that knows, and the key that
     * <b>would complete</b> the triple is held back, whichever of the three it is.
     *
     * <p>Only what the guest <b>is starting to press</b> is looked at: a key it was already
     * holding completes nothing that has not already happened, and holding it back would
     * produce a release the guest did not ask for.
     */
    private int keyToWithhold(byte[] keys) {
        if (isSet(keys, KEY_CTRL) && isSet(keys, KEY_L_AMIGA) && isSet(keys, KEY_R_AMIGA)) {
            return KEY_R_AMIGA;
        }
        for (int raw : RESET_KEYS) {
            if (isSet(keys, raw) && !isSet(lastKeys, raw) && keyboard.resetComboPendingOn(raw)) {
                return raw;
            }
        }
        return -1;
    }

    private static boolean isSet(byte[] keys, int raw) {
        return (keys[raw >> 3] & (1 << (raw & 7))) != 0;
    }

    /**
     * The device matching the packet's code.
     *
     * <p>A code outside the catalogue never gets here ({@code FeedbackPacket.readFrom} refuses
     * it), but this is still the trust boundary, and a {@code default} choosing the empty
     * port is the only answer that invents no device on anyone's behalf.
     */
    private static GamePortDevice hardwareDevice(int wire) {
        return switch (wire) {
            case FeedbackPacket.DEVICE_MOUSE -> GamePortDevice.MOUSE;
            case FeedbackPacket.DEVICE_JOYSTICK -> GamePortDevice.JOYSTICK;
            default -> GamePortDevice.NONE;
        };
    }

    private void resetBaseline(FeedbackPacket state) {
        lastSessionGen = state.sessionGen;
        lastMouseX = state.mouseCounterX;
        lastMouseY = state.mouseCounterY;
        releaseAll();
        started = true;
    }
}
