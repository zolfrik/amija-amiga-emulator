package io.github.zolfrik.amija.romgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Il font di sistema 8x8 di AmiJa, letto da {@code rom/font8x8.txt}.
 *
 * Tenere i glifi in un file di testo invece che in un array Java serve a chi
 * dovra' ritoccarli: una riga per carattere, otto byte, uno per riga di pixel.
 * E' anche l'unica forma in cui un errore di disegno si vede leggendo.
 *
 * Il font e' opera originale di questo progetto: nessun vincolo di terzi, e
 * quindi ridistribuibile insieme all'emulatore.
 */
public final class Font8x8 {

    public static final int LO_CHAR = 0x20;
    public static final int HI_CHAR = 0x7F;
    public static final int COUNT = HI_CHAR - LO_CHAR + 1;
    public static final int WIDTH = 8;
    public static final int HEIGHT = 8;

    public static final Path SOURCE = Path.of("rom/font8x8.txt");

    private static byte[][] glyphs;

    private Font8x8() {}

    /** Glifi indicizzati per codice: {@code glyph(c)[riga]} e' una riga di 8 pixel. */
    public static synchronized byte[] glyph(int c) throws IOException {
        if (glyphs == null) glyphs = parse(SOURCE);
        if (c < LO_CHAR || c > HI_CHAR) return glyphs[0];
        return glyphs[c - LO_CHAR];
    }

    private static byte[][] parse(Path file) throws IOException {
        byte[][] out = new byte[COUNT][HEIGHT];
        int seen = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;

            int colon = line.indexOf(':');
            if (colon < 0) throw new IOException("riga senza ':' in " + file + ": " + raw);
            int code = Integer.parseInt(line.substring(0, colon).trim(), 16);
            String[] parts = line.substring(colon + 1).trim().split("\\s+");
            if (parts.length != HEIGHT) {
                throw new IOException("il carattere $" + Integer.toHexString(code)
                        + " ha " + parts.length + " byte invece di " + HEIGHT);
            }
            if (code < LO_CHAR || code > HI_CHAR) {
                throw new IOException("carattere fuori intervallo: $" + Integer.toHexString(code));
            }
            for (int i = 0; i < HEIGHT; i++) {
                out[code - LO_CHAR][i] = (byte) Integer.parseInt(parts[i], 16);
            }
            seen++;
        }
        if (seen != COUNT) {
            throw new IOException("attesi " + COUNT + " caratteri in " + file + ", trovati " + seen);
        }
        return out;
    }

    /**
     * Disegna una stringa in un buffer di indici di colore, ingrandita di
     * {@code scale}. Ritorna la larghezza usata in pixel.
     */
    public static int draw(int[][] target, String text, int x, int y, int color, int scale)
            throws IOException {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            byte[] g = glyph(text.charAt(i));
            for (int row = 0; row < HEIGHT; row++) {
                int bits = g[row] & 0xFF;
                for (int col = 0; col < WIDTH; col++) {
                    if ((bits & (0x80 >> col)) == 0) continue;
                    for (int sy = 0; sy < scale; sy++) {
                        for (int sx = 0; sx < scale; sx++) {
                            int px = cursor + col * scale + sx;
                            int py = y + row * scale + sy;
                            if (py < 0 || py >= target.length) continue;
                            if (px < 0 || px >= target[py].length) continue;
                            target[py][px] = color;
                        }
                    }
                }
            }
            cursor += WIDTH * scale;
        }
        return cursor - x;
    }

    /** Larghezza in pixel che occuperebbe {@code text} a quella scala. */
    public static int width(String text, int scale) {
        return text.length() * WIDTH * scale;
    }

    /**
     * Come {@link #draw}, ma avanzando della larghezza reale di ogni glifo.
     *
     * Il font e' a passo fisso perche' i giochi lo pretendono, ma un titolo
     * grande a passo fisso ha buchi evidenti fra le lettere strette: qui si
     * misura l'inchiostro e si avanza di quello.
     */
    public static int drawProportional(int[][] target, String text, int x, int y,
                                       int color, int scale, int gap) throws IOException {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            byte[] g = glyph(ch);
            int[] span = inkSpan(g);
            if (span == null) {                       // spazio: avanza e basta
                cursor += (WIDTH / 2) * scale + gap;
                continue;
            }
            for (int row = 0; row < HEIGHT; row++) {
                int bits = g[row] & 0xFF;
                for (int col = span[0]; col <= span[1]; col++) {
                    if ((bits & (0x80 >> col)) == 0) continue;
                    for (int sy = 0; sy < scale; sy++) {
                        for (int sx = 0; sx < scale; sx++) {
                            int px = cursor + (col - span[0]) * scale + sx;
                            int py = y + row * scale + sy;
                            if (py < 0 || py >= target.length) continue;
                            if (px < 0 || px >= target[py].length) continue;
                            target[py][px] = color;
                        }
                    }
                }
            }
            cursor += (span[1] - span[0] + 1) * scale + gap;
        }
        return cursor - x - gap;
    }

    /** Larghezza del testo disegnato in proporzionale. */
    public static int widthProportional(String text, int scale, int gap) throws IOException {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            int[] span = inkSpan(glyph(text.charAt(i)));
            w += (span == null ? WIDTH / 2 : span[1] - span[0] + 1) * scale + gap;
        }
        return Math.max(0, w - gap);
    }

    /** Colonne estreme in cui il glifo ha pixel accesi, o null se e' vuoto. */
    private static int[] inkSpan(byte[] g) {
        int first = WIDTH, last = -1;
        for (int row = 0; row < HEIGHT; row++) {
            int bits = g[row] & 0xFF;
            for (int col = 0; col < WIDTH; col++) {
                if ((bits & (0x80 >> col)) == 0) continue;
                if (col < first) first = col;
                if (col > last) last = col;
            }
        }
        return last < 0 ? null : new int[]{first, last};
    }
}
