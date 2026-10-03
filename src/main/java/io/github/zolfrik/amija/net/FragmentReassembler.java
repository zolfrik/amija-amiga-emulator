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
 * Puts back together the fragments of a video frame ({@code docs/remote-play-plan.md} section
 * 4.1).
 *
 * <h2>All or nothing</h2>
 * An incomplete frame is <b>never shown</b> and not ACKed: the host notices and compresses the
 * next one against an older reference. So there is no retransmission to ask for and no
 * partial reassembly to draw, which would be latency and screen garbage respectively.
 *
 * <h2>Why four slots and not one</h2>
 * With one only, two overlapping frames (normal: the last fragment of N arrives after the first
 * of N+1, and on a link with jitter it happens all the time) would destroy each other. With
 * four, an incomplete frame waits until {@code N+4} arrives, ~80 ms: more than needed, and in
 * any case a <b>bounded, constant</b> time.
 *
 * <h2>Everything is bounded up front</h2>
 * {@code fragCount} comes from the network and is never used to size anything: the buffers are
 * allocated at construction for {@link RemotePlayProtocol#MAX_FRAGMENTS}, and a larger count is
 * an error, not a request. The fragments' length is constrained too: all but the last must be
 * <b>exactly</b> {@link RemotePlayProtocol#FRAGMENT_DATA} bytes, which makes the reassembly offset
 * a product instead of a sum of received lengths, and so impossible to shift with tailored
 * fragments.
 */
public final class FragmentReassembler {

    /** A frame being reassembled. */
    public static final class Slot {
        long frameId = -1;
        int fragCount;
        int received;
        int totalLength = -1;
        final boolean[] have = new boolean[RemotePlayProtocol.MAX_FRAGMENTS];
        final byte[] data = new byte[RemotePlayProtocol.MAX_FRAGMENTS
                * RemotePlayProtocol.FRAGMENT_DATA];

        public long frameId() {
            return frameId;
        }

        public byte[] data() {
            return data;
        }

        public int length() {
            return totalLength;
        }

        boolean complete() {
            return fragCount > 0 && received == fragCount && totalLength >= 0;
        }

        void reset(long id, int count) {
            frameId = id;
            fragCount = count;
            received = 0;
            totalLength = -1;
            java.util.Arrays.fill(have, 0, count, false);
        }
    }

    private final Slot[] slots = new Slot[ReferenceRing.SLOTS];
    private long droppedIncomplete;
    private long duplicates;

    public FragmentReassembler() {
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
    }

    /**
     * Takes a fragment.
     *
     * @param plain plaintext payload of the datagram: {@code frameId:u32, fragIdx:u16,
     *              fragCount:u16} followed by the data
     * @return the slot if the frame is now complete, otherwise {@code null}
     * @throws ProtocolException if the fragment is malformed or inconsistent
     */
    public Slot accept(byte[] plain, int length) throws ProtocolException {
        if (length < RemotePlayProtocol.FRAGMENT_HEADER) {
            throw new ProtocolException("frammento di " + length + " byte: manca l'intestazione");
        }
        long frameId = ((plain[0] & 0xFFL)) | ((plain[1] & 0xFFL) << 8)
                | ((plain[2] & 0xFFL) << 16) | ((plain[3] & 0xFFL) << 24);
        int fragIdx = (plain[4] & 0xFF) | ((plain[5] & 0xFF) << 8);
        int fragCount = (plain[6] & 0xFF) | ((plain[7] & 0xFF) << 8);
        int dataLength = length - RemotePlayProtocol.FRAGMENT_HEADER;

        if (fragCount < 1 || fragCount > RemotePlayProtocol.MAX_FRAGMENTS) {
            throw new ProtocolException("fragCount " + fragCount + " fuori da [1, "
                    + RemotePlayProtocol.MAX_FRAGMENTS + "]");
        }
        if (fragIdx >= fragCount) {
            throw new ProtocolException("fragIdx " + fragIdx + " oltre fragCount " + fragCount);
        }
        if (dataLength > RemotePlayProtocol.FRAGMENT_DATA) {
            throw new ProtocolException("frammento di " + dataLength + " byte, massimo "
                    + RemotePlayProtocol.FRAGMENT_DATA);
        }
        boolean last = fragIdx == fragCount - 1;
        if (!last && dataLength != RemotePlayProtocol.FRAGMENT_DATA) {
            throw new ProtocolException("frammento intermedio di " + dataLength
                    + " byte: devono essere esattamente " + RemotePlayProtocol.FRAGMENT_DATA);
        }
        if (last && dataLength < 1) {
            throw new ProtocolException("ultimo frammento vuoto");
        }

        Slot slot = slots[(int) Math.floorMod(frameId, slots.length)];
        if (slot.frameId != frameId) {
            if (slot.frameId >= 0 && !slot.complete()) {
                droppedIncomplete++;
            }
            slot.reset(frameId, fragCount);
        } else if (slot.fragCount != fragCount) {
            throw new ProtocolException("fragCount incoerente per il frame " + frameId
                    + ": " + slot.fragCount + " poi " + fragCount);
        }
        if (slot.have[fragIdx]) {
            duplicates++;
            return null;
        }

        System.arraycopy(plain, RemotePlayProtocol.FRAGMENT_HEADER, slot.data,
                fragIdx * RemotePlayProtocol.FRAGMENT_DATA, dataLength);
        slot.have[fragIdx] = true;
        slot.received++;
        if (last) {
            slot.totalLength = fragIdx * RemotePlayProtocol.FRAGMENT_DATA + dataLength;
        }
        return slot.complete() ? slot : null;
    }

    /** Frames abandoned because their slot was needed by a newer frame. */
    public long droppedIncomplete() {
        return droppedIncomplete;
    }

    /** Fragments already seen: normal on a link that duplicates, suspicious if many. */
    public long duplicates() {
        return duplicates;
    }

    public void reset() {
        for (Slot s : slots) {
            s.frameId = -1;
            s.fragCount = 0;
            s.received = 0;
            s.totalLength = -1;
        }
    }
}
