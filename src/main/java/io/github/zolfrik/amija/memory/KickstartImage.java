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

package io.github.zolfrik.amija.memory;

import io.github.zolfrik.amija.common.AppHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Normalises the file of a Kickstart ROM into the image the Amiga ROM socket would
 * really see, and tells its identity.
 *
 * <p>The dumps around are not "clean" images: the same ROM circulates in files with extra
 * headers, appended tails, duplicated or encrypted halves. Loading them as they were meant
 * either a flat refusal (size not allowed by
 * {@link io.github.zolfrik.amija.custom.Gary#setRomSize(int)}) or a black screen with no
 * explanation. This class recognises the variants, brings the image back to the canonical
 * form (a power of two, which the hardware mirroring requires) and tells the user whether
 * it makes sense in the Kickstart socket.
 *
 * <h2>Variants recognised</h2>
 * <ul>
 *   <li><b>Cloanto encrypted</b>: the file starts with {@code AMIROMTYPE1} followed by the
 *       data XORed with {@code rom.key}. The user's key is needed: it is looked for next to
 *       the ROM file and then in the app home. Without the key the image is unusable and
 *       reported as such.</li>
 *   <li><b>Loader header</b>: images "for use with KickIt" prepend an 8-byte header. The
 *       real start is found by looking for the ROM signature.</li>
 *   <li><b>Appended tails</b>: "[with bonus]" or "[o][b]" dumps with a few kB of extra data
 *       at the end: truncated to the power of two.</li>
 *   <li><b>Overdump</b>: 512 KB "[o]" dumps that are two identical copies of the same
 *       256 KB ROM. On the bus the result would be identical (the hardware mirroring does
 *       the same), but collapsing them makes the reported size and base address honest.</li>
 * </ul>
 *
 * <h2>Socket compatibility</h2>
 * The first two longwords of every Amiga ROM are the two vectors the 68000 reads at reset:
 * the initial stack pointer and the PC. The <b>second</b>, the PC, tells at which address
 * the ROM expects to be mapped, and it is the criterion that separates a socket Kickstart
 * from everything else:
 * <ul>
 *   <li>{@code $F80000-$FFFFFF} -> socket Kickstart: what the socket can use;</li>
 *   <li>{@code $F00000-$F7FFFF} -> extended ROM (CDTV/CD32): the <i>additional</i> ROM of
 *       those machines, not a Kickstart;</li>
 *   <li>any other address (typically {@code $200000}) -> image relocated for
 *       ReKick/KickIt, to be loaded into Fast RAM by a program, not put in ROM.</li>
 * </ul>
 *
 * <p>Do not read the <em>first</em> longword as code: in Commodore Kickstarts it is
 * {@code $11114EF9} / {@code $11144EF9} and that {@code $4EF9} <i>looks like</i> a
 * {@code JMP}, but the 68000 loads that longword into A7. DiagROM, which is not Commodore,
 * puts {@code $11144447} ("DG") there and works just as well.
 *
 * @author alessio.carotenuto
 */
public final class KickstartImage {

    /** Signature of the Cloanto encrypted files (followed by the data XORed with {@code rom.key}). */
    private static final String CLOANTO_MAGIC = "AMIROMTYPE1";

    /** Name of the file with the decryption key, if the user has it. */
    public static final String ROM_KEY_FILE = "rom.key";

    /**
     * Minimum size accepted. Below 8 KB there is no sensible Amiga ROM (the smallest is the
     * A1000 bootstrap, exactly 8 KB).
     */
    private static final int MIN_SIZE = 8 * 1024;

    /**
     * Maximum size: the window Gary decodes for the ROM socket ({@code $F80000-$FFFFFF}). A
     * larger image would not be addressable.
     */
    private static final int MAX_SIZE = 512 * 1024;

    /**
     * Identification byte every Amiga ROM opens with (Kickstart, extended ROM, DiagROM). The
     * first two longwords of the image are the two vectors the 68000 reads at reset, SSP and
     * PC, and Commodore also uses the SSP as a marker: {@code $11} followed by a size code
     * ({@code $11} = 256 KB, {@code $14} = 512 KB).
     */
    private static final int ROM_ID = 0x11;

    /**
     * {@code JMP <abs.l>}: in Commodore Kickstarts the initial SSP is {@code $11114EF9} /
     * {@code $11144EF9}, and that {@code $4EF9} is only part of the marker, not an
     * instruction (the 68000 loads that longword into A7). It still tells Commodore images
     * from other ROMs: DiagROM, for instance, uses {@code $11144447} ("DG").
     */
    private static final int JMP_ABS_L = 0x4EF9;

    /** Start of the window decoded for the ROM socket. */
    public static final int SOCKET_WINDOW_START = 0xF80000;

    /** Match word of a {@code struct Resident}. */
    private static final int RTC_MATCHWORD = 0x4AFC;

    // =========================================================================
    // State
    // =========================================================================

    private final Path    source;
    private final byte[]  data;
    private final int     resetPc;
    private final boolean commodoreMarker;
    private final String  identifier;
    private final boolean checksumValid;
    private final String  normalizationNote;

    private KickstartImage(Path source, byte[] data, int resetPc, boolean commodoreMarker,
                           String identifier, boolean checksumValid, String normalizationNote) {
        this.source            = source;
        this.data              = data;
        this.resetPc           = resetPc;
        this.commodoreMarker   = commodoreMarker;
        this.identifier        = identifier;
        this.checksumValid     = checksumValid;
        this.normalizationNote = normalizationNote;
    }

    // =========================================================================
    // Loading
    // =========================================================================

    /**
     * Reads and normalises the given file.
     *
     * @throws IOException                if the file cannot be read
     * @throws UnsupportedRomException    if the content is not a usable Amiga ROM image
     *                                    (encrypted without a key, impossible size,
     *                                    signature missing)
     */
    public static KickstartImage load(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        return of(raw, file);
    }

    /** Variant working on bytes already in memory. {@code source} may be null. */
    public static KickstartImage of(byte[] raw, Path source) throws IOException {
        if (raw == null || raw.length == 0) {
            throw new UnsupportedRomException("il file è vuoto");
        }

        StringBuilder note = new StringBuilder();
        byte[] work = raw;

        // 1. Cloanto encrypted images: without rom.key there is nothing to do.
        if (startsWith(work, CLOANTO_MAGIC)) {
            byte[] key = findRomKey(source);
            if (key == null) {
                throw new UnsupportedRomException(
                        "immagine cifrata Cloanto: serve il file '" + ROM_KEY_FILE
                        + "' accanto alla ROM o nella cartella dell'applicazione");
            }
            work = decrypt(work, CLOANTO_MAGIC.length(), key);
            note.append("decifrata con ").append(ROM_KEY_FILE).append("; ");
        }

        // 2. Loader headers prepended to the real image.
        int start = findImageStart(work);
        if (start > 0) {
            note.append("scartati ").append(start).append(" byte di header; ");
        }
        int available = work.length - start;

        // 3. The hardware mirroring works with masks: the size must be a power of two. The
        //    tails appended to dumps are lost here.
        int size = Math.min(largestPowerOfTwoAtMost(available), MAX_SIZE);
        if (size < MIN_SIZE) {
            throw new UnsupportedRomException(
                    "dimensione utile " + available + " byte: troppo piccola per una ROM Amiga");
        }
        if (size != available) {
            note.append("troncata da ").append(available).append(" a ").append(size)
                .append(" byte; ");
        }
        byte[] image = Arrays.copyOfRange(work, start, start + size);

        // 4. Overdump: identical halves mean a smaller ROM written twice.
        int collapsed = 0;
        while (image.length >= 2 * MIN_SIZE && halvesIdentical(image)) {
            image = Arrays.copyOf(image, image.length / 2);
            collapsed++;
        }
        if (collapsed > 0) {
            note.append("overdump: metà identiche collassate a ").append(image.length)
                .append(" byte; ");
        }

        // 5. Signature. Without the identification byte it is not an Amiga ROM: freezer
        //    cartridges and the halves of an interleaved dump end up here.
        if ((image[0] & 0xFF) != ROM_ID) {
            throw new UnsupportedRomException(String.format(
                    "non è una ROM Amiga: l'immagine dovrebbe iniziare con $11, trovato $%08X",
                    readLong(image, 0)));
        }

        // The reset PC is the second longword: the 68000 reads it from there. An odd value
        // means that longword is not a vector: the image is not bootable (typically half of
        // a byte-interleaved dump).
        int resetPc = readLong(image, 4) & 0xFFFFFF;
        if ((resetPc & 1) != 0) {
            throw new UnsupportedRomException(String.format(
                    "vettore di reset dispari ($%06X): l'immagine non è avviabile "
                    + "(spesso è metà di un dump interlacciato)", resetPc));
        }
        boolean marker = readWord(image, 2) == JMP_ABS_L;
        String id = readIdentifier(image);
        boolean ck = isChecksumValid(image);

        String n = note.length() == 0 ? "" : note.substring(0, note.length() - 2);
        return new KickstartImage(source, image, resetPc, marker, id, ck, n);
    }

    // =========================================================================
    // Interrogazione
    // =========================================================================

    /** The normalised image, ready for {@link ROM#ROM(byte[])}. */
    public byte[] data() { return data; }

    /** Size of the normalised image: always a power of two. */
    public int size() { return data.length; }

    /**
     * Address at which the ROM expects to be mapped, from the base address Gary would use
     * for this size ({@code $1000000 - size}).
     */
    public int mapBase() { return 0x1000000 - data.length; }

    /** Destination of the {@code JMP} of the reset vector. */
    public int resetPc() { return resetPc; }

    /**
     * Identification taken from the resident tag of {@code exec.library} (e.g.
     * {@code "exec 34.2 (28 Oct 1987)"}), or null if not found.
     */
    public String identifier() { return identifier; }

    /**
     * Version of the {@code exec.library} resident in the image ({@code rt_Version}: 34 for
     * Kickstart 1.3, 37 for 2.04, 40 for 3.1), or -1 if the image carries none.
     */
    public int execVersion() { return readExecVersion(data); }

    /** Processor a Kickstart needs, as far as AmiJa can tell. */
    public enum CpuRequirement {
        /** Runs on every processor AmiJa emulates. */
        ANY,
        /** Built for the 68020: its code does not run on a 68000 or a 68010. */
        M68020,
        /**
         * Needs a 68020 with the full 32-bit address bus, or a 68030: the A3000 and A4000
         * ROMs. AmiJa's processors all have a 24-bit address bus, so none can run it.
         */
        UNSUPPORTED
    }

    /**
     * Kickstarts compiled for a processor beyond the 68000, by the CRC32 of the 512 KB image.
     * Every other Kickstart, recognised or not, is taken to run on a 68000.
     */
    private static final Map<Long, CpuRequirement> CPU_BY_CRC = Map.ofEntries(
            // A1200 and CD32: 68EC020.
            Map.entry(0x1483A091L, CpuRequirement.M68020),     // 3.1 40.68 (A1200)
            Map.entry(0xF17FA97FL, CpuRequirement.M68020),     // 3.1.4 (A1200)
            Map.entry(0xB87506A7L, CpuRequirement.M68020),     // 3.1.4.2 (A1200)
            Map.entry(0xBD1FF75EL, CpuRequirement.M68020),     // 3.2 (A1200)
            Map.entry(0x2B653371L, CpuRequirement.M68020),     // 3.2.1 (A1200)
            Map.entry(0x5C40328AL, CpuRequirement.M68020),     // 3.2.2 (A1200)
            Map.entry(0xB18D3B67L, CpuRequirement.M68020),     // 3.2.3 (A1200)
            Map.entry(0x1E62D4A5L, CpuRequirement.M68020),     // 3.1 40.60 (CD32)
            // A3000 and A4000: 32-bit address bus or 68030.
            Map.entry(0xBC0EC13FL, CpuRequirement.UNSUPPORTED), // 1.4 36.16 (A3000)
            Map.entry(0x234A7233L, CpuRequirement.UNSUPPORTED), // 2.04 37.175 (A3000)
            Map.entry(0x9E6AC152L, CpuRequirement.UNSUPPORTED), // 3.0 39.106 (A4000)
            Map.entry(0x2B4566F1L, CpuRequirement.UNSUPPORTED), // 3.1 40.70 (A4000)
            Map.entry(0xEFB239CCL, CpuRequirement.UNSUPPORTED), // 3.1 40.68 (A3000)
            Map.entry(0x43B6DD22L, CpuRequirement.UNSUPPORTED), // 3.1 40.68 (A4000)
            Map.entry(0xD6BAE334L, CpuRequirement.UNSUPPORTED), // 3.1 40.68 (A4000)
            Map.entry(0x75932C3AL, CpuRequirement.UNSUPPORTED), // 3.1 40.70 (A4000T)
            Map.entry(0x3AC99EDCL, CpuRequirement.UNSUPPORTED), // 3.X (A4000)
            Map.entry(0x50C3529CL, CpuRequirement.UNSUPPORTED), // 3.1.4 (A3000)
            Map.entry(0xD47E18FDL, CpuRequirement.UNSUPPORTED), // 3.1.4 (A4000)
            Map.entry(0x75A2B2A5L, CpuRequirement.UNSUPPORTED), // 3.1.4 (A4000T)
            Map.entry(0xBA35F8EBL, CpuRequirement.UNSUPPORTED), // 3.1.4.2 (A3000)
            Map.entry(0x1B84CB33L, CpuRequirement.UNSUPPORTED), // 3.1.4.2 (A4000)
            Map.entry(0xD6D0EF3EL, CpuRequirement.UNSUPPORTED), // 3.1.4.2 (A4000T)
            Map.entry(0xF3AF46CCL, CpuRequirement.UNSUPPORTED), // 3.2 (A3000)
            Map.entry(0x9BB8FC93L, CpuRequirement.UNSUPPORTED), // 3.2 (A4000)
            Map.entry(0x9188A509L, CpuRequirement.UNSUPPORTED), // 3.2 (A4000T)
            Map.entry(0x261339F8L, CpuRequirement.UNSUPPORTED), // 3.2 (Walker)
            Map.entry(0x0078F607L, CpuRequirement.UNSUPPORTED), // 3.2.1 (A3000)
            Map.entry(0xF3CED3B8L, CpuRequirement.UNSUPPORTED), // 3.2.1 (A4000)
            Map.entry(0xAF3452ECL, CpuRequirement.UNSUPPORTED), // 3.2.1 (A4000T)
            Map.entry(0x46335B57L, CpuRequirement.UNSUPPORTED), // 3.2.2 (A3000)
            Map.entry(0x4BEA9798L, CpuRequirement.UNSUPPORTED), // 3.2.2 (A4000)
            Map.entry(0x36BBCD8AL, CpuRequirement.UNSUPPORTED), // 3.2.2 (A4000T)
            Map.entry(0x74C0B23FL, CpuRequirement.UNSUPPORTED), // 3.2.3 (A3000)
            Map.entry(0xB6A4698EL, CpuRequirement.UNSUPPORTED), // 3.2.3 (A4000)
            Map.entry(0x588A5E6DL, CpuRequirement.UNSUPPORTED)  // 3.2.3 (A4000T)
    );

    /** Processor this Kickstart needs (see {@link CpuRequirement}). */
    public CpuRequirement cpuRequirement() {
        CRC32 crc = new CRC32();
        crc.update(data);
        return CPU_BY_CRC.getOrDefault(crc.getValue(), CpuRequirement.ANY);
    }

    /** True if the 32-bit checksum with carry is {@code $FFFFFFFF} (intact ROM). */
    public boolean isChecksumValid() { return checksumValid; }

    /** Description of the transformations applied to the file, empty if none. */
    public String normalizationNote() { return normalizationNote; }

    /** Source file, if known. */
    public Path source() { return source; }

    /** Outcome of the classification: where the ROM expects to live. */
    public enum Compatibility {
        /** Socket Kickstart: mappable at {@code $F80000-$FFFFFF}, fine in the socket. */
        SOCKET_ROM,
        /** CDTV/CD32 extended ROM: mapped at {@code $F00000}, not a Kickstart. */
        EXTENDED_ROM,
        /** Relocated image (ReKick/KickIt): the reset points into RAM, a loader is needed. */
        RELOCATED,
        /** Has the identification byte but the reset vector leads nowhere. */
        NOT_A_KICKSTART
    }

    /** Where the ROM expects to be mapped, from the reset vector. */
    public Compatibility compatibility() {
        // The socket holds a 256 or 512 KB EPROM, and there are no smaller Kickstarts.
        // An image below 256 KB that still resets into the socket window is something else:
        // typically the A1000 bootstrap, which loads the Kickstart from disk into the WCS
        // (writable RAM a stock Amiga does not have).
        if (data.length < 256 * 1024)               return Compatibility.NOT_A_KICKSTART;
        if (resetPc >= SOCKET_WINDOW_START)         return Compatibility.SOCKET_ROM;
        if (resetPc >= 0xF00000)                    return Compatibility.EXTENDED_ROM;
        // ReKick/KickIt images are real Commodore Kickstarts, only relocated into Fast RAM:
        // the $4EF9 marker tells them from a dump that ended up here by chance.
        if (commodoreMarker && resetPc >= 0x200000) return Compatibility.RELOCATED;
        return Compatibility.NOT_A_KICKSTART;
    }

    /**
     * True if the ROM can go in the Kickstart socket: its reset vector points inside the window
     * {@code $F80000-$FFFFFF} that Gary decodes.
     */
    public boolean isSocketRom() {
        return compatibility() == Compatibility.SOCKET_ROM;
    }

    /** Technical reason for the incompatibility for logs and diagnostics, or null if compatible. */
    public String incompatibilityReason() {
        switch (compatibility()) {
            case EXTENDED_ROM:
                return String.format("extended ROM (CDTV/CD32), maps at $%06X", resetPc & 0xF00000);
            case RELOCATED:
                return String.format("relocated image (ReKick/KickIt), reset vector at $%06X", resetPc);
            case NOT_A_KICKSTART:
                if (data.length < 256 * 1024) {
                    return String.format("%d KB: too small for a Kickstart (the Kickstart socket "
                            + "takes 256 or 512 KB)", data.length / 1024);
                }
                return String.format("not a Kickstart: reset vector $%06X points nowhere usable", resetPc);
            default:
                return null;
        }
    }

    /** Summary line for the logs and the configuration popup. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(size() / 1024).append(" KB @ $").append(String.format("%06X", mapBase()));
        if (identifier != null) sb.append(", ").append(identifier);
        sb.append(checksumValid ? ", checksum ok" : ", CHECKSUM ERRATO");
        if (!normalizationNote.isEmpty()) sb.append(" [").append(normalizationNote).append(']');
        return sb.toString();
    }

    /** Thrown when the file is not a usable Amiga ROM image. */
    public static final class UnsupportedRomException extends IOException {
        public UnsupportedRomException(String message) { super(message); }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Offset at which the real image starts. Loader headers have a fixed, known length, so
     * trying the candidate positions and taking the first that shows the ROM signature is
     * enough.
     */
    private static int findImageStart(byte[] b) {
        for (int off : new int[]{0, 8, 512}) {
            if (off + 8 > b.length) continue;
            if ((b[off] & 0xFF) == ROM_ID) return off;
        }
        return 0;
    }

    private static int largestPowerOfTwoAtMost(int n) {
        return n <= 0 ? 0 : Integer.highestOneBit(n);
    }

    private static boolean halvesIdentical(byte[] b) {
        int h = b.length / 2;
        return Arrays.equals(b, 0, h, b, h, b.length);
    }

    private static boolean startsWith(byte[] b, String s) {
        if (b.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((b[i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Looks for {@code rom.key}: first next to the ROM file (where Cloanto distributes it),
     * then in the app home.
     */
    private static byte[] findRomKey(Path romFile) throws IOException {
        if (romFile != null) {
            Path dir = romFile.toAbsolutePath().getParent();
            if (dir != null) {
                Path k = dir.resolve(ROM_KEY_FILE);
                if (Files.isReadable(k)) return nonEmpty(Files.readAllBytes(k));
            }
        }
        Path k = AppHome.resolve(ROM_KEY_FILE);
        if (Files.isReadable(k)) return nonEmpty(Files.readAllBytes(k));
        return null;
    }

    private static byte[] nonEmpty(byte[] key) {
        return (key == null || key.length == 0) ? null : key;
    }

    /** Cyclic XOR with the key: that is all of the Cloanto encryption. */
    private static byte[] decrypt(byte[] src, int off, byte[] key) {
        byte[] out = new byte[src.length - off];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (src[off + i] ^ key[i % key.length]);
        }
        return out;
    }

    /**
     * Kickstart checksum: 32-bit sum of all the longwords <em>with carry</em> (the lost carry
     * comes back as +1), which on an intact ROM gives {@code $FFFFFFFF}. It is the algorithm
     * the Kickstart itself runs at start-up.
     */
    private static boolean isChecksumValid(byte[] b) {
        long sum = 0;
        for (int i = 0; i + 3 < b.length; i += 4) {
            long prev = sum;
            sum = (sum + (readLong(b, i) & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
            if (sum < prev) sum = (sum + 1) & 0xFFFFFFFFL;
        }
        return sum == 0xFFFFFFFFL;
    }

    /**
     * Extracts {@code rt_IdString} from the resident tag of {@code exec.library}.
     *
     * <p>A {@code struct Resident} is recognised because it starts with {@code $4AFC} and is
     * self-referencing: {@code rt_MatchTag} holds its own address. That comparison removes
     * the false positives (the word $4AFC also appears in code) and also tells at which
     * address the ROM thinks it lives, which makes the scan independent of the size.
     */
    private static String readIdentifier(byte[] b) {
        int base = 0x1000000 - b.length;
        for (int off = 0; off + 26 <= b.length; off += 2) {
            if (readWord(b, off) != RTC_MATCHWORD) continue;
            if (readLong(b, off + 2) != base + off) continue;
            String name = readString(b, base, readLong(b, off + 14));
            if (!"exec.library".equals(name)) continue;
            String idString = readString(b, base, readLong(b, off + 18));
            if (idString != null) return idString.trim().replace("\r", "").replace("\n", "");
            return "exec " + (b[off + 11] & 0xFF);
        }
        return null;
    }

    /** {@code rt_Version} of the exec.library resident, or -1 if there is none. */
    private static int readExecVersion(byte[] b) {
        int base = 0x1000000 - b.length;
        for (int off = 0; off + 26 <= b.length; off += 2) {
            if (readWord(b, off) != RTC_MATCHWORD) continue;
            if (readLong(b, off + 2) != base + off) continue;
            if ("exec.library".equals(readString(b, base, readLong(b, off + 14)))) {
                return b[off + 11] & 0xFF;
            }
        }
        return -1;
    }

    /** Reads a NUL-terminated string from a ROM pointer, or null if outside the image. */
    private static String readString(byte[] b, int base, int ptr) {
        int off = ptr - base;
        if (off < 0 || off >= b.length) return null;
        int end = off;
        while (end < b.length && b[end] != 0 && end - off < 128) end++;
        return new String(b, off, end - off, StandardCharsets.ISO_8859_1);
    }

    private static int readWord(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int readLong(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
             | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }
}
