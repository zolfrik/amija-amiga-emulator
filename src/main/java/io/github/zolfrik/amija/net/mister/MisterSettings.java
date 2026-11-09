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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Function;

/**
 * How to reach the MiSTer and what to send it.
 *
 * @param host        the MiSTer's address, or {@code null} to search the PC's networks for it
 * @param bind        local address to send from, or {@code null} to pick the interface on
 *                    the MiSTer's subnet
 * @param mtu         link MTU
 * @param compression frame encoding
 * @param color       pixel layout choice
 * @param geometry    position and width of the picture on the tube
 * @param audio       whether the audio goes to the MiSTer (it stays on the PC while the
 *                    MiSTer is unreachable or has audio off in its menu)
 * @param audioLeadMs audio the MiSTer holds right after each block, in milliseconds
 * @param inputs      whose controls drive the machine: the MiSTer's or the PC's
 */
public record MisterSettings(InetAddress host, InetAddress bind, int mtu,
        Compression compression, Color color, GroovyModeline.Geometry geometry,
        boolean audio, int audioLeadMs, Inputs inputs) {

    /** As the canonical constructor, with the PC's controls. */
    public MisterSettings(InetAddress host, InetAddress bind, int mtu,
            Compression compression, Color color, GroovyModeline.Geometry geometry,
            boolean audio, int audioLeadMs) {
        this(host, bind, mtu, compression, color, geometry, audio, audioLeadMs, Inputs.PC);
    }

    /** The host value that asks for the MiSTer to be searched for, as an empty one does. */
    public static final String AUTO_HOST = "auto";

    public static final int AUDIO_LEAD_DEFAULT_MS = 40;
    public static final int AUDIO_LEAD_MIN_MS = 10;
    public static final int AUDIO_LEAD_MAX_MS = 150;

    public enum Compression {
        RAW, LZ4, LZ4_DELTA;

        static Compression parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "raw" -> RAW;
                case "lz4" -> LZ4;
                case "lz4delta" -> LZ4_DELTA;
                default -> throw new IllegalArgumentException(
                        "unknown compression: " + s + " (raw, lz4, lz4delta)");
            };
        }
    }

    /** Whose controls drive the machine while the picture is on the MiSTer. */
    public enum Inputs {
        /**
         * The devices plugged into the MiSTer, each port taking the device chosen for it
         * among the game ports; the PC's controls do not reach the machine.
         */
        MISTER,
        /** The PC's own, as when the picture stays on the PC; the MiSTer sends none. */
        PC;

        static Inputs parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "mister" -> MISTER;
                case "pc" -> PC;
                default -> throw new IllegalArgumentException(
                        "unknown controls: " + s + " (mister, pc)");
            };
        }
    }

    public enum Color {
        /** RGB565 for the 12-bit OCS/ECS palette, BGR888 for AGA. */
        AUTO, RGB565, BGR888;

        static Color parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "565" -> RGB565;
                case "888" -> BGR888;
                default -> throw new IllegalArgumentException(
                        "unknown colour: " + s + " (auto, 565, 888)");
            };
        }

        public GroovyProtocol.RgbMode resolve(boolean aga) {
            return switch (this) {
                case AUTO -> aga ? GroovyProtocol.RgbMode.BGR888 : GroovyProtocol.RgbMode.RGB565;
                case RGB565 -> GroovyProtocol.RgbMode.RGB565;
                case BGR888 -> GroovyProtocol.RgbMode.BGR888;
            };
        }
    }

    public MisterSettings {
        GroovyProtocol.chunkSize(mtu);
        if (compression == null || color == null || geometry == null || inputs == null) {
            throw new IllegalArgumentException(
                    "compression, color, geometry and inputs are required");
        }
        if (audioLeadMs < AUDIO_LEAD_MIN_MS || audioLeadMs > AUDIO_LEAD_MAX_MS) {
            throw new IllegalArgumentException("audio lead out of range: " + audioLeadMs
                    + " ms (" + AUDIO_LEAD_MIN_MS + "-" + AUDIO_LEAD_MAX_MS + ")");
        }
    }

    private static int intValue(Function<String, String> values, String prefix, String key,
            int dflt) {
        String v = values.apply(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prefix + key + " is not a number: " + v, e);
        }
    }

    private static boolean parseBoolean(String key, String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "on", "1" -> true;
            case "false", "off", "0" -> false;
            default -> throw new IllegalArgumentException(key + " is not valid: " + v
                    + " (true, false)");
        };
    }

    /**
     * Reads {@code amija.mister.*}; {@code null} when {@code amija.mister.host} is not set.
     *
     * @throws IllegalArgumentException on a malformed value
     */
    public static MisterSettings fromSystemProperties() {
        String host = System.getProperty("amija.mister.host");
        if (host == null || host.isBlank()) {
            return null;
        }
        return from(key -> {
            String v = System.getProperty("amija.mister." + key);
            if (v == null) {
                return null;
            }
            v = v.trim();
            return v.isEmpty() ? null : v;
        }, "amija.mister.");
    }

    /**
     * Builds the settings from named values ({@code host}, {@code bind}, {@code mtu},
     * {@code compression}, {@code color}, {@code hshift}, {@code vshift},
     * {@code audio}, {@code audiolead}, {@code inputs}); a missing value takes its default.
     * A missing host, or {@code auto}, means the MiSTer is searched for.
     *
     * @param values the value of each name, trimmed, or {@code null} when absent
     * @param prefix how the names are spelled where they come from, for the error messages
     * @throws IllegalArgumentException on a malformed value
     */
    public static MisterSettings from(Function<String, String> values, String prefix) {
        String host = values.apply("host");
        if (host != null && host.equalsIgnoreCase(AUTO_HOST)) {
            host = null;
        }
        String bind = values.apply("bind");
        String comp = values.apply("compression");
        String color = values.apply("color");
        int mtu = intValue(values, prefix, "mtu", GroovyProtocol.DEFAULT_MTU);
        int hShift = intValue(values, prefix, "hshift", 0);
        int vShift = intValue(values, prefix, "vshift", 0);
        String audio = values.apply("audio");
        int audioLead = intValue(values, prefix, "audiolead", AUDIO_LEAD_DEFAULT_MS);
        String inputs = values.apply("inputs");
        try {
            return new MisterSettings(host == null ? null : InetAddress.getByName(host),
                    bind == null ? null : InetAddress.getByName(bind), mtu,
                    comp == null ? Compression.LZ4_DELTA : Compression.parse(comp),
                    color == null ? Color.AUTO : Color.parse(color),
                    new GroovyModeline.Geometry(hShift, vShift),
                    audio == null || parseBoolean(prefix + "audio", audio), audioLead,
                    inputs == null ? Inputs.PC : Inputs.parse(inputs));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unknown address: " + e.getMessage(), e);
        }
    }
}
