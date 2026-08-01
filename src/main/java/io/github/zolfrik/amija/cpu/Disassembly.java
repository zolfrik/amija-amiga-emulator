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

package io.github.zolfrik.amija.cpu;

public class Disassembly {

    public static String getSizeSuffix(int size) {
        return switch (size) {
            case 1 -> ".b";
            case 2 -> ".w";
            case 4 -> ".l";
            default -> "";
        };
    }

    public static String formatEA(int mode, int reg, int size, int ext1, int ext2) {
        return switch (mode) {
            case 0 -> "D" + reg;
            case 1 -> "A" + reg;
            case 2 -> "(A" + reg + ")";
            case 3 -> "(A" + reg + ")+";
            case 4 -> "-(A" + reg + ")";
            case 5 -> "$" + formatHex((short) ext1, 4) + "(A" + reg + ")";
            case 6 -> formatIndexed(reg, ext1, false);
            case 7 -> switch (reg) {
                case 0 -> "$" + formatHex((short) ext1, 4) + ".w";
                case 1 -> "$" + formatHex((ext1 << 16) | (ext2 & 0xFFFF), 8) + ".l";
                case 2 -> "$" + formatHex((short) ext1, 4) + "(PC)";
                case 3 -> formatIndexed(-1, ext1, true);
                case 4 -> formatImmediate(size, ext1, ext2);
                default -> "???";
            };
            default -> "???";
        };
    }

    private static String formatImmediate(int size, int ext1, int ext2) {
        int val = (size == 4) ? ((ext1 << 16) | (ext2 & 0xFFFF)) :
                  (size == 2) ? (short) ext1 : (byte) ext1;
        return "#$" + formatHex(val, size * 2);
    }

    public static String formatHex(int val, int chars) {
        String hex = Integer.toHexString(val).toUpperCase();
        if (hex.length() > chars) {
            hex = hex.substring(hex.length() - chars);
        } else {
            StringBuilder sb = new StringBuilder();
            while (sb.length() + hex.length() < chars) {
                sb.append('0');
            }
            sb.append(hex);
            hex = sb.toString();
        }
        return hex;
    }

    private static String formatIndexed(int reg, int ext, boolean isPC) {
        int isA = (ext >> 15) & 1;
        int regIdx = (ext >> 12) & 7;
        int isLong = (ext >> 11) & 1;
        int disp8 = (byte) (ext & 0xFF);

        String base = isPC ? "PC" : "A" + reg;
        String indexReg = (isA == 1 ? "A" : "D") + regIdx;
        String indexSize = (isLong == 1 ? ".l" : ".w");

        return "$" + formatHex(disp8, 2) + "(" + base + ", " + indexReg + indexSize + ")";
    }

    public static String formatMovemList(int mask) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        // Registers are D0-D7, A0-A7
        for (int i = 0; i < 8; i++) {
            if (((mask >> i) & 1) != 0) {
                if (!first) sb.append("/");
                sb.append("D").append(i);
                first = false;
            }
        }
        for (int i = 0; i < 8; i++) {
            if (((mask >> (i + 8)) & 1) != 0) {
                if (!first) sb.append("/");
                sb.append("A").append(i);
                first = false;
            }
        }
        return sb.toString();
    }

    public static String formatMovemListPreDec(int mask) {
        // For -(An) mode, the bits are reversed: A7-A0, D7-D0
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < 8; i++) {
            if (((mask >> i) & 1) != 0) {
                if (!first) sb.append("/");
                sb.append("A").append(7 - i);
                first = false;
            }
        }
        for (int i = 0; i < 8; i++) {
            if (((mask >> (i + 8)) & 1) != 0) {
                if (!first) sb.append("/");
                sb.append("D").append(7 - i);
                first = false;
            }
        }
        return sb.toString();
    }
}
