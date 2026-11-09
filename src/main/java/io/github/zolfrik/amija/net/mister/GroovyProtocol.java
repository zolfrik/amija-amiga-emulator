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

package io.github.zolfrik.amija.net.mister;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Wire format of the Groovy_MiSTer streaming protocol: command layouts and the status ACK.
 *
 * <p>Everything travels over UDP, little-endian. The MiSTer tells commands apart by the
 * exact datagram length, so every builder here writes exactly the documented number of
 * bytes; a command of any other length is silently ignored on the other side. Frame and
 * audio payloads follow their command as plain datagrams of at most {@code mtu - 28} bytes,
 * with no sequence number: the receiver simply counts bytes against the announced total.
 */
public final class GroovyProtocol {

    private GroovyProtocol() {
    }

    /** Commands and frame data. */
    public static final int PORT = 32100;
    /**
     * Input reports from the MiSTer. The PC registers by sending one byte to this port from
     * the socket that wants them, before {@code CMD_INIT}: the MiSTer reads that byte when the
     * session opens and sends every report of the session to its source address.
     */
    public static final int PORT_INPUTS = 32101;

    /** Joystick report: {@code [u32 frame][u8 order][u16 joy1][u16 joy2]}. */
    public static final int INPUT_JOY_LENGTH = 9;
    /** Joystick report with the analogue axes: eight more {@code s8}. */
    public static final int INPUT_JOY_ANALOG_LENGTH = 17;
    /** Keyboard report: {@code [u32 frame][u8 order][32-byte bitmap of USB HID usages]}. */
    public static final int INPUT_KEYBOARD_LENGTH = 37;
    /** Keyboard report plus a PS/2 mouse packet: {@code [status][dx][dy][wheel]}. */
    public static final int INPUT_MOUSE_LENGTH = 41;
    /** Bytes of the keyboard bitmap; bit {@code n % 8} of byte {@code n / 8} is usage n. */
    public static final int INPUT_KEY_BYTES = 32;

    /** Joystick bits, in the order of the core's button list. */
    public static final int JOY_RIGHT = 1;
    public static final int JOY_LEFT = 1 << 1;
    public static final int JOY_DOWN = 1 << 2;
    public static final int JOY_UP = 1 << 3;
    public static final int JOY_B1 = 1 << 4;
    public static final int JOY_B2 = 1 << 5;
    public static final int JOY_B3 = 1 << 6;

    /** PS/2 mouse status byte: buttons, then the sign of each delta. */
    public static final int PS2_LEFT = 1;
    public static final int PS2_RIGHT = 1 << 1;
    public static final int PS2_MIDDLE = 1 << 2;
    public static final int PS2_X_SIGN = 1 << 4;
    public static final int PS2_Y_SIGN = 1 << 5;

    public static final byte CMD_CLOSE = 1;
    public static final byte CMD_INIT = 2;
    public static final byte CMD_SWITCHRES = 3;
    public static final byte CMD_AUDIO = 4;
    public static final byte CMD_GET_STATUS = 5;
    public static final byte CMD_BLIT_FIELD_VSYNC = 7;
    public static final byte CMD_GET_VERSION = 8;

    public static final int INIT_LENGTH = 5;
    public static final int SWITCHRES_LENGTH = 26;
    public static final int AUDIO_LENGTH = 3;
    /** Raw frame follows. */
    public static final int BLIT_RAW_LENGTH = 8;
    /** Raw mode only: repeat the previous frame of the field, no payload. */
    public static final int BLIT_DUP_LENGTH = 9;
    /** LZ4 block follows. */
    public static final int BLIT_LZ4_LENGTH = 12;
    /** LZ4 block of the byte-wise difference from the previous frame of the field follows. */
    public static final int BLIT_LZ4_DELTA_LENGTH = 13;

    public static final int ACK_LENGTH = 13;
    public static final int VERSION_LENGTH = 1;

    /** IPv4 + UDP header bytes subtracted from the MTU to size a payload datagram. */
    public static final int UDP_IP_OVERHEAD = 28;
    public static final int DEFAULT_MTU = 1500;

    /** Pixel layouts accepted by {@code CMD_INIT}. */
    public enum RgbMode {
        /** Three bytes per pixel, in the order blue, green, red. */
        BGR888(0, 3),
        /** Four bytes per pixel. */
        BGRA8888(1, 4),
        /** One little-endian 16-bit word per pixel: {@code r << 11 | g << 5 | b}. */
        RGB565(2, 2);

        public final int code;
        public final int bytesPerPixel;

        RgbMode(int code, int bytesPerPixel) {
            this.code = code;
            this.bytesPerPixel = bytesPerPixel;
        }

        public static RgbMode fromCode(int code) {
            for (RgbMode m : values()) {
                if (m.code == code) {
                    return m;
                }
            }
            throw new IllegalArgumentException("unknown rgb mode " + code);
        }
    }

    /** Audio sample rates accepted by {@code CMD_INIT}. */
    public enum AudioRate {
        OFF(0, 0), HZ_22050(1, 22050), HZ_44100(2, 44100), HZ_48000(3, 48000);

        public final int code;
        public final int hz;

        AudioRate(int code, int hz) {
            this.code = code;
            this.hz = hz;
        }

        public static AudioRate fromCode(int code) {
            for (AudioRate r : values()) {
                if (r.code == code) {
                    return r;
                }
            }
            throw new IllegalArgumentException("unknown audio rate " + code);
        }
    }

    /** Payload bytes carried by one data datagram. */
    public static int chunkSize(int mtu) {
        if (mtu < 576 || mtu > 9000) {
            throw new IllegalArgumentException("mtu out of range: " + mtu);
        }
        return mtu - UDP_IP_OVERHEAD;
    }

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static byte[] init(boolean lz4, AudioRate audio, int channels, RgbMode rgb) {
        if (channels < 0 || channels > 2) {
            throw new IllegalArgumentException("channels " + channels);
        }
        return new byte[]{CMD_INIT, (byte) (lz4 ? 1 : 0), (byte) audio.code,
                (byte) (audio == AudioRate.OFF ? 0 : channels), (byte) rgb.code};
    }

    public static byte[] switchres(GroovyModeline m) {
        byte[] b = new byte[SWITCHRES_LENGTH];
        le(b).put(CMD_SWITCHRES).putDouble(m.pclockMHz())
                .putShort((short) m.hActive()).putShort((short) m.hBegin())
                .putShort((short) m.hEnd()).putShort((short) m.hTotal())
                .putShort((short) m.vActive()).putShort((short) m.vBegin())
                .putShort((short) m.vEnd()).putShort((short) m.vTotal())
                .put((byte) m.interlace());
        return b;
    }

    public static byte[] audio(int bytes) {
        if (bytes < 0 || bytes > 0xFFFF) {
            throw new IllegalArgumentException("audio block of " + bytes + " bytes");
        }
        byte[] b = new byte[AUDIO_LENGTH];
        le(b).put(CMD_AUDIO).putShort((short) bytes);
        return b;
    }

    /**
     * Writes a blit header into {@code out} and returns its length.
     *
     * @param compressedSize ignored for raw and duplicate frames
     */
    public static int blit(byte[] out, BlitKind kind, int frame, int field, int vSync,
            int compressedSize) {
        ByteBuffer b = le(out);
        b.put(CMD_BLIT_FIELD_VSYNC).putInt(frame).put((byte) field).putShort((short) vSync);
        switch (kind) {
            case RAW -> {
            }
            case DUPLICATE -> b.put((byte) 1);
            case LZ4 -> b.putInt(compressedSize);
            case LZ4_DELTA -> b.putInt(compressedSize).put((byte) 1);
        }
        return b.position();
    }

    /**
     * Writes the delta payload of {@link BlitKind#LZ4_DELTA}: {@code out[i] = cur[i] - prev[i]}
     * modulo 256. The MiSTer adds it back byte by byte to the previous frame of the field.
     *
     * @return the number of positions where the two frames are equal
     */
    public static int delta(byte[] cur, byte[] prev, byte[] out, int length) {
        int same = 0;
        for (int i = 0; i < length; i++) {
            int d = cur[i] - prev[i];
            out[i] = (byte) d;
            if (d == 0) {
                same++;
            }
        }
        return same;
    }

    /** How the frame data of a blit is encoded. */
    public enum BlitKind {
        RAW, DUPLICATE, LZ4, LZ4_DELTA
    }

    /**
     * Status the MiSTer returns after {@code CMD_INIT}, {@code CMD_GET_STATUS} and every blit.
     *
     * @param frameEcho  frame number of the blit being acknowledged (0 for INIT/STATUS)
     * @param vSyncEcho  raster line requested by that blit
     * @param frame      frame counter of the video generator
     * @param vCount     raster line the beam is on
     * @param bits       status flags, see the accessors
     */
    public record Ack(int frameEcho, int vSyncEcho, int frame, int vCount, int bits) {

        public static Ack parse(byte[] b, int off, int len) {
            if (len != ACK_LENGTH) {
                throw new IllegalArgumentException("ack of " + len + " bytes");
            }
            ByteBuffer bb = ByteBuffer.wrap(b, off, len).order(ByteOrder.LITTLE_ENDIAN);
            int fe = bb.getInt();
            int ve = bb.getShort() & 0xFFFF;
            int fr = bb.getInt();
            int vc = bb.getShort() & 0xFFFF;
            return new Ack(fe, ve, fr, vc, bb.get() & 0xFF);
        }

        public byte[] encode() {
            byte[] b = new byte[ACK_LENGTH];
            le(b).putInt(frameEcho).putShort((short) vSyncEcho).putInt(frame)
                    .putShort((short) vCount).put((byte) bits);
            return b;
        }

        public boolean vramReady() {
            return (bits & 0x01) != 0;
        }

        public boolean vramEndFrame() {
            return (bits & 0x02) != 0;
        }

        public boolean vramSynced() {
            return (bits & 0x04) != 0;
        }

        public boolean frameskip() {
            return (bits & 0x08) != 0;
        }

        public boolean vblank() {
            return (bits & 0x10) != 0;
        }

        public boolean field1() {
            return (bits & 0x20) != 0;
        }

        /** Audio is accepted only once this is set. */
        public boolean audio() {
            return (bits & 0x40) != 0;
        }

        public boolean vramQueue() {
            return (bits & 0x80) != 0;
        }
    }
}
