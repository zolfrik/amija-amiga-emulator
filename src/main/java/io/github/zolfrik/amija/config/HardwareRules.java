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

package io.github.zolfrik.amija.config;

import io.github.zolfrik.amija.cpu.CpuModel;
import io.github.zolfrik.amija.custom.FastRam;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.memory.KickstartImage;
import io.github.zolfrik.amija.memory.KickstartImage.CpuRequirement;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The limits within which the emulated machine works, whatever the configuration asks for.
 *
 * <p>Two kinds of rule live here. The memory sizes are brought back into the range the
 * address decoding can serve: a value outside it cannot describe a working machine, so it is
 * corrected rather than refused. The Kickstart, instead, sets requirements on the rest of
 * the machine (a processor, the ability to boot a hard disk) that only the user can resolve,
 * by changing either side: {@link #conflicts} names them, and the machine is not built while
 * one stands.
 */
public final class HardwareRules {

    /** Chip RAM: a power of two from 256 KB (the smallest Kickstart can boot in) to 2 MB. */
    public static final int CHIP_RAM_MIN_KB = 256;
    public static final int CHIP_RAM_MAX_KB = 2048;
    /** Slow RAM: 256 KB steps, the granularity at which the Kickstart sizes it. */
    public static final int SLOW_RAM_STEP_KB = 256;
    public static final int SLOW_RAM_MAX_KB = 1792;
    /** Fast RAM: the largest Zorro II board. */
    public static final int FAST_RAM_MAX_KB = 8192;
    /** First exec.library version whose Kickstart mounts the hard disks (Kickstart 2.0). */
    public static final int HARD_DISK_MIN_EXEC = 36;

    private HardwareRules() {}

    /** Chip RAM size the machine is built with for a requested size, in KB. */
    public static int chipRamKb(int kb) {
        if (kb <= CHIP_RAM_MIN_KB) return CHIP_RAM_MIN_KB;
        if (kb >= CHIP_RAM_MAX_KB) return CHIP_RAM_MAX_KB;
        return Integer.highestOneBit(kb);
    }

    /** Slow RAM size the machine is built with for a requested size, in KB (0 = none). */
    public static int slowRamKb(int kb) {
        if (kb <= 0) return 0;
        return Math.min(kb, SLOW_RAM_MAX_KB) / SLOW_RAM_STEP_KB * SLOW_RAM_STEP_KB;
    }

    /** Fast RAM size the machine is built with for a requested size, in KB (0 = none). */
    public static int fastRamKb(int kb) {
        int clamped = Math.max(0, Math.min(kb, FAST_RAM_MAX_KB));
        return FastRam.normalizeBytes(clamped * 1024) / 1024;
    }

    // =========================================================================
    // Kickstart requirements
    // =========================================================================

    /**
     * What a Kickstart demands of the rest of the machine.
     *
     * @param cpu         processor it needs
     * @param execVersion exec.library version, -1 if unknown
     * @param builtin     the built-in AmiJa ROM, which has no AmigaDOS
     */
    public record RomFacts(CpuRequirement cpu, int execVersion, boolean builtin) {

        static final RomFacts BUILTIN = new RomFacts(CpuRequirement.ANY, -1, true);
        static final RomFacts UNKNOWN = new RomFacts(CpuRequirement.ANY, -1, false);

        /** True if this Kickstart mounts and boots hard disks and WHDLoad. */
        public boolean bootsHardDisks() {
            return !builtin && (execVersion < 0 || execVersion >= HARD_DISK_MIN_EXEC);
        }
    }

    private record CacheKey(String path, long modified, long length) { }

    private static final Map<CacheKey, RomFacts> CACHE = new ConcurrentHashMap<>();

    /**
     * Requirements of the Kickstart at {@code path}. An empty path or a missing file is the
     * built-in ROM; a file that cannot be read sets no requirement (the ROM check reports it).
     * The answer is cached per file and modification time.
     */
    public static RomFacts romFacts(String path) {
        if (path == null || path.isBlank()) return RomFacts.BUILTIN;
        File f = new File(path.trim());
        if (!f.isFile()) return RomFacts.BUILTIN;
        CacheKey key = new CacheKey(f.getAbsolutePath(), f.lastModified(), f.length());
        return CACHE.computeIfAbsent(key, k -> {
            try {
                KickstartImage image = KickstartImage.load(Paths.get(k.path()));
                return new RomFacts(image.cpuRequirement(), image.execVersion(), false);
            } catch (IOException | RuntimeException e) {
                return RomFacts.UNKNOWN;
            }
        });
    }

    /** A requirement of the Kickstart the rest of the machine does not meet. */
    public enum Conflict {
        /** The Kickstart is built for the 68020 and the processor is older. */
        CPU_TOO_OLD,
        /** The Kickstart needs a processor AmiJa does not emulate (A3000/A4000 ROMs). */
        ROM_UNSUPPORTED,
        /** A hard disk or WHDLoad is mounted and the Kickstart cannot boot it. */
        HARD_DISK_NEEDS_KS2
    }

    /** The smallest processor of the given family that meets the requirement. */
    public static CpuModel satisfying(CpuRequirement req, CpuModel cpu) {
        return req == CpuRequirement.M68020 && cpu != CpuModel.M68020 ? CpuModel.M68020 : cpu;
    }

    /**
     * The conflicts between a Kickstart and the machine around it; empty if it can boot.
     *
     * @param hardDisks a hard disk or the WHDLoad boot is active
     */
    public static List<Conflict> conflicts(RomFacts rom, CpuModel cpu, boolean hardDisks) {
        List<Conflict> out = new ArrayList<>();
        if (rom.cpu() == CpuRequirement.UNSUPPORTED) {
            out.add(Conflict.ROM_UNSUPPORTED);
        } else if (rom.cpu() == CpuRequirement.M68020 && cpu != CpuModel.M68020) {
            out.add(Conflict.CPU_TOO_OLD);
        }
        if (hardDisks && !rom.bootsHardDisks()) out.add(Conflict.HARD_DISK_NEEDS_KS2);
        return out;
    }

    /**
     * The message that tells the user what clashes and how to resolve it, one paragraph per
     * conflict.
     *
     * @param rom      path of the Kickstart in use (empty for the built-in ROM)
     * @param cpu      processor in use
     * @param autoMode the machine configures itself, so the fix is the ROM box, not the CPU
     * @param atBoot   the message is shown at start-up, outside the configuration popup
     */
    public static String describe(List<Conflict> conflicts, String rom, CpuModel cpu,
            boolean autoMode, boolean atBoot) {
        String name = romFacts(rom).builtin() ? Messages.t("hw.rom.builtin") : rom.trim();
        StringBuilder sb = new StringBuilder(Messages.t("hw.conflict.title"));
        for (Conflict c : conflicts) {
            sb.append("\n\n").append(switch (c) {
                case CPU_TOO_OLD -> Messages.t("hw.conflict.cpu", name, cpu.partNumber());
                case ROM_UNSUPPORTED -> Messages.t("hw.conflict.unsupported", name);
                case HARD_DISK_NEEDS_KS2 -> Messages.t(autoMode
                        ? "hw.conflict.harddisk.auto" : "hw.conflict.harddisk", name);
            });
        }
        if (atBoot) sb.append("\n\n").append(Messages.t("hw.conflict.f12"));
        return sb.toString();
    }

    /** {@link #describe(List, String, CpuModel, boolean, boolean)} for the machine of {@code cfg}. */
    public static String describe(List<Conflict> conflicts, AmigaConfig cfg, boolean atBoot) {
        return describe(conflicts, cfg.kickstartPath(), cfg.cpuModel(), cfg.autoEnabled(), atBoot);
    }
}
