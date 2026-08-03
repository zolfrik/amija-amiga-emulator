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

package io.github.zolfrik.amija.memory;

import io.github.zolfrik.amija.common.AppHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Normalizza il file di una ROM Kickstart nell'immagine che il socket ROM
 * dell'Amiga 500 vedrebbe davvero, e ne dice l'identità.
 *
 * <p>I dump in circolazione non sono immagini "pulite": la stessa ROM gira in file
 * con header aggiuntivi, code appese, metà duplicate o cifrate. Caricarli così
 * com'erano significava o un rifiuto secco (dimensione non ammessa da
 * {@link io.github.zolfrik.amija.custom.Gary#setRomSize(int)}) o uno schermo nero
 * senza spiegazione. Questa classe riconosce le varianti, riporta l'immagine alla
 * forma canonica — una potenza di due, che è ciò che il mirroring hardware richiede —
 * e dice a chi la usa se ha senso metterla nel socket di un A500.
 *
 * <h2>Varianti riconosciute</h2>
 * <ul>
 *   <li><b>Cifrate Cloanto</b> — il file comincia con {@code AMIROMTYPE1} seguito dai
 *       dati in XOR con {@code rom.key}. Servono la chiave dell'utente: viene cercata
 *       accanto al file ROM e poi nella app home. Senza chiave l'immagine è
 *       inutilizzabile e viene segnalata come tale.</li>
 *   <li><b>Header di loader</b> — le immagini "for use with KickIt" antepongono 8 byte
 *       di intestazione. L'inizio vero si trova cercando la firma della ROM.</li>
 *   <li><b>Code appese</b> — dump "[with bonus]" o "[o][b]" con qualche kB di dati
 *       extra in fondo: si tronca alla potenza di due.</li>
 *   <li><b>Overdump</b> — dump "[o]" da 512 KB che sono due copie identiche della
 *       stessa ROM da 256 KB. Sul bus il risultato sarebbe identico (il mirroring
 *       hardware fa la stessa cosa), ma collassarli rende onesta la dimensione
 *       riportata e l'indirizzo base.</li>
 * </ul>
 *
 * <h2>Compatibilità con l'A500</h2>
 * Le prime due longword di ogni ROM Amiga sono i due vettori che il 68000 legge al reset:
 * lo stack pointer iniziale e il PC. È il <b>secondo</b> — il PC — a dire a quale
 * indirizzo la ROM si aspetta di essere mappata, ed è il criterio con cui si distingue un
 * Kickstart da socket da tutto il resto:
 * <ul>
 *   <li>{@code $F80000–$FFFFFF} → Kickstart da socket: è quello che l'A500 può usare;</li>
 *   <li>{@code $F00000–$F7FFFF} → extended ROM (CDTV/CD32): è la ROM <i>aggiuntiva</i>
 *       di quelle macchine, non un Kickstart;</li>
 *   <li>qualunque altro indirizzo (tipicamente {@code $200000}) → immagine rilocata per
 *       ReKick/KickIt, che va caricata in Fast RAM da un programma, non messa in ROM.</li>
 * </ul>
 *
 * <p>Attenzione a non leggere la <em>prima</em> longword come codice: nei Kickstart
 * Commodore vale {@code $11114EF9} / {@code $11144EF9} e quel {@code $4EF9} <i>sembra</i>
 * un {@code JMP}, ma il 68000 quella longword la carica in A7. La DiagROM, che non è
 * Commodore, ci mette {@code $11144447} ("DG") e funziona benissimo lo stesso.
 *
 * @author alessio.carotenuto
 */
public final class KickstartImage {

    /** Firma dei file cifrati Cloanto (seguita dai dati in XOR con {@code rom.key}). */
    private static final String CLOANTO_MAGIC = "AMIROMTYPE1";

    /** Nome del file con la chiave di decifratura, se l'utente la possiede. */
    public static final String ROM_KEY_FILE = "rom.key";

    /**
     * Dimensione minima accettata. Sotto gli 8 KB non c'è alcuna ROM Amiga sensata
     * (la più piccola è il bootstrap dell'A1000, appunto 8 KB).
     */
    private static final int MIN_SIZE = 8 * 1024;

    /**
     * Dimensione massima: la finestra che Gary decodifica per il socket ROM
     * ({@code $F80000–$FFFFFF}). Un'immagine più grande non sarebbe indirizzabile.
     */
    private static final int MAX_SIZE = 512 * 1024;

    /**
     * Byte di identificazione con cui si apre ogni ROM Amiga (Kickstart, extended ROM,
     * DiagROM). Le prime due longword dell'immagine sono i due vettori che il 68000
     * legge al reset — SSP e PC — e Commodore usa lo SSP anche come marcatore:
     * {@code $11} seguito da un codice di dimensione ({@code $11} = 256 KB,
     * {@code $14} = 512 KB).
     */
    private static final int ROM_ID = 0x11;

    /**
     * {@code JMP <abs.l>}: nei Kickstart Commodore lo SSP iniziale vale
     * {@code $11114EF9} / {@code $11144EF9}, e quel {@code $4EF9} è solo parte del
     * marcatore, non un'istruzione (il 68000 quella longword la carica in A7). Serve
     * comunque a distinguere le immagini Commodore da altre ROM: la DiagROM, per dire,
     * usa {@code $11144447} ("DG").
     */
    private static final int JMP_ABS_L = 0x4EF9;

    /** Inizio della finestra che l'A500 decodifica per il socket ROM. */
    public static final int SOCKET_WINDOW_START = 0xF80000;

    /** Word di riconoscimento di una {@code struct Resident}. */
    private static final int RTC_MATCHWORD = 0x4AFC;

    // =========================================================================
    // Stato
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
    // Caricamento
    // =========================================================================

    /**
     * Legge e normalizza il file indicato.
     *
     * @throws IOException                se il file non è leggibile
     * @throws UnsupportedRomException    se il contenuto non è un'immagine ROM Amiga
     *                                    utilizzabile (cifrata senza chiave, dimensione
     *                                    impossibile, firma assente)
     */
    public static KickstartImage load(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        return of(raw, file);
    }

    /** Variante che lavora su byte già in memoria. {@code source} può essere null. */
    public static KickstartImage of(byte[] raw, Path source) throws IOException {
        if (raw == null || raw.length == 0) {
            throw new UnsupportedRomException("il file è vuoto");
        }

        StringBuilder note = new StringBuilder();
        byte[] work = raw;

        // 1. Immagini cifrate Cloanto: senza rom.key non c'è niente da fare.
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

        // 2. Header di loader anteposti all'immagine vera.
        int start = findImageStart(work);
        if (start > 0) {
            note.append("scartati ").append(start).append(" byte di header; ");
        }
        int available = work.length - start;

        // 3. Il mirroring hardware ragiona per maschere: la dimensione dev'essere una
        //    potenza di due. Le code appese ai dump si perdono qui.
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

        // 4. Overdump: metà identiche significano una ROM più piccola scritta due volte.
        int collapsed = 0;
        while (image.length >= 2 * MIN_SIZE && halvesIdentical(image)) {
            image = Arrays.copyOf(image, image.length / 2);
            collapsed++;
        }
        if (collapsed > 0) {
            note.append("overdump: metà identiche collassate a ").append(image.length)
                .append(" byte; ");
        }

        // 5. Firma. Senza il byte di identificazione non è una ROM Amiga: le cartucce
        //    freezer e le metà di un dump interlacciato finiscono qui.
        if ((image[0] & 0xFF) != ROM_ID) {
            throw new UnsupportedRomException(String.format(
                    "non è una ROM Amiga: l'immagine dovrebbe iniziare con $11, trovato $%08X",
                    readLong(image, 0)));
        }

        // Il PC di reset è la seconda longword: è il 68000 a leggerlo da lì. Un valore
        // dispari significa che quella longword non è un vettore — l'immagine non è
        // avviabile (tipicamente è metà di un dump a byte alternati).
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

    /** L'immagine normalizzata, pronta per {@link ROM#ROM(byte[])}. */
    public byte[] data() { return data; }

    /** Dimensione dell'immagine normalizzata: sempre una potenza di due. */
    public int size() { return data.length; }

    /**
     * Indirizzo a cui la ROM si aspetta di essere mappata, dedotto dall'indirizzo
     * base che Gary userebbe per questa dimensione ({@code $1000000 - size}).
     */
    public int mapBase() { return 0x1000000 - data.length; }

    /** Destinazione del {@code JMP} del vettore di reset. */
    public int resetPc() { return resetPc; }

    /**
     * Identificazione ricavata dal resident tag di {@code exec.library}
     * (p.es. {@code "exec 34.2 (28 Oct 1987)"}), oppure null se non trovato.
     */
    public String identifier() { return identifier; }

    /** true se il checksum a 32 bit con riporto vale {@code $FFFFFFFF} (ROM integra). */
    public boolean isChecksumValid() { return checksumValid; }

    /** Descrizione delle trasformazioni applicate al file, vuota se nessuna. */
    public String normalizationNote() { return normalizationNote; }

    /** File di provenienza, se noto. */
    public Path source() { return source; }

    /** Esito della classificazione: dove la ROM si aspetta di vivere. */
    public enum Compatibility {
        /** Kickstart da socket: mappabile in {@code $F80000–$FFFFFF}, va bene sull'A500. */
        SOCKET_ROM,
        /** Extended ROM CDTV/CD32: si mappa a {@code $F00000}, non è un Kickstart. */
        EXTENDED_ROM,
        /** Immagine rilocata (ReKick/KickIt): il reset punta in RAM, serve un loader. */
        RELOCATED,
        /** Ha il byte di identificazione ma il vettore di reset non porta da nessuna parte. */
        NOT_A_KICKSTART
    }

    /** Dove la ROM si aspetta di essere mappata, dedotto dal vettore di reset. */
    public Compatibility compatibility() {
        // Nel socket dell'A500 ci sta una EPROM da 256 o 512 KB, e non esistono
        // Kickstart più piccoli. Un'immagine sotto i 256 KB che pure resetta nella
        // finestra del socket è qualcos'altro — tipicamente il bootstrap dell'A1000,
        // che carica il Kickstart da disco nella WCS (RAM scrivibile che l'A500 non ha).
        if (data.length < 256 * 1024)               return Compatibility.NOT_A_KICKSTART;
        if (resetPc >= SOCKET_WINDOW_START)         return Compatibility.SOCKET_ROM;
        if (resetPc >= 0xF00000)                    return Compatibility.EXTENDED_ROM;
        // Le immagini per ReKick/KickIt sono Kickstart Commodore veri, solo rilocati in
        // Fast RAM: il marcatore $4EF9 le distingue da un dump che è finito qui per caso.
        if (commodoreMarker && resetPc >= 0x200000) return Compatibility.RELOCATED;
        return Compatibility.NOT_A_KICKSTART;
    }

    /**
     * true se la ROM può essere messa nel socket di un A500: il suo vettore di reset
     * punta dentro la finestra {@code $F80000–$FFFFFF} che Gary decodifica.
     */
    public boolean isSocketRom() {
        return compatibility() == Compatibility.SOCKET_ROM;
    }

    /** Motivo tecnico dell'incompatibilità per log e diagnostica, o null se compatibile. */
    public String incompatibilityReason() {
        switch (compatibility()) {
            case EXTENDED_ROM:
                return String.format("extended ROM (CDTV/CD32), maps at $%06X", resetPc & 0xF00000);
            case RELOCATED:
                return String.format("relocated image (ReKick/KickIt), reset vector at $%06X", resetPc);
            case NOT_A_KICKSTART:
                if (data.length < 256 * 1024) {
                    return String.format("%d KB: too small for a Kickstart (the A500 socket "
                            + "takes 256 or 512 KB)", data.length / 1024);
                }
                return String.format("not a Kickstart: reset vector $%06X points nowhere usable", resetPc);
            default:
                return null;
        }
    }

    /** Riga di riepilogo per i log e per il popup di configurazione. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(size() / 1024).append(" KB @ $").append(String.format("%06X", mapBase()));
        if (identifier != null) sb.append(", ").append(identifier);
        sb.append(checksumValid ? ", checksum ok" : ", CHECKSUM ERRATO");
        if (!normalizationNote.isEmpty()) sb.append(" [").append(normalizationNote).append(']');
        return sb.toString();
    }

    /** Sollevata quando il file non è un'immagine ROM Amiga utilizzabile. */
    public static final class UnsupportedRomException extends IOException {
        public UnsupportedRomException(String message) { super(message); }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Offset a cui comincia l'immagine vera. Gli header dei loader hanno lunghezza
     * fissa e nota, quindi basta provare le posizioni candidate e prendere la prima
     * che espone la firma della ROM.
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
     * Cerca {@code rom.key}: prima accanto al file ROM (dove Cloanto lo distribuisce),
     * poi nella app home.
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

    /** XOR ciclico con la chiave: la cifratura Cloanto è tutta qui. */
    private static byte[] decrypt(byte[] src, int off, byte[] key) {
        byte[] out = new byte[src.length - off];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (src[off + i] ^ key[i % key.length]);
        }
        return out;
    }

    /**
     * Checksum del Kickstart: somma a 32 bit di tutte le longword <em>con riporto</em>
     * (il riporto perso rientra come +1), che su una ROM integra dà {@code $FFFFFFFF}.
     * È l'algoritmo che il Kickstart stesso esegue all'avvio.
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
     * Estrae {@code rt_IdString} del resident tag di {@code exec.library}.
     *
     * <p>Una {@code struct Resident} si riconosce perché comincia con {@code $4AFC} ed
     * è autoreferenziale: {@code rt_MatchTag} contiene il proprio indirizzo. Quel
     * confronto elimina i falsi positivi (la word $4AFC compare anche nel codice) e in
     * più dice a che indirizzo la ROM crede di stare, il che rende la scansione
     * indipendente dalla dimensione.
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

    /** Legge una stringa NUL-terminata da un puntatore ROM, o null se fuori immagine. */
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
