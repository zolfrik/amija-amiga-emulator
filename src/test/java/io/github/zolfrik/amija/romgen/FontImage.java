package io.github.zolfrik.amija.romgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Impagina il font di sistema nel formato che la struct TextFont di
 * graphics.library si aspetta.
 *
 * I giochi che chiedono OpenFont non vogliono disegnare testo tramite la
 * libreria: si prendono {@code tf_CharData} e ci blittano sopra da soli.
 * Quindi conta solo che i glifi siano nel layout giusto — tutti i caratteri
 * affiancati su una sola bitmap alta {@value Font8x8#HEIGHT} righe, larga
 * {@code tf_Modulo} byte.
 *
 * Formato prodotto: la bitmap, seguita da un long di CharLoc per carattere
 * (word alta = offset in bit, word bassa = larghezza in bit).
 */
public final class FontImage {

    public static final int MODULO = Font8x8.COUNT * Font8x8.WIDTH / 8;
    public static final int DATA_BYTES = MODULO * Font8x8.HEIGHT;
    public static final int CHARLOC_BYTES = Font8x8.COUNT * 4;

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "rom/font.bin");
        byte[] blob = build();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, blob);
        System.out.printf("[FONT] scritto %s: %d byte di bitmap + %d di CharLoc%n",
                out, DATA_BYTES, CHARLOC_BYTES);
    }

    public static byte[] build() throws IOException {
        byte[] out = new byte[DATA_BYTES + CHARLOC_BYTES];

        // I glifi vanno affiancati in orizzontale: il carattere i occupa i bit
        // da i*8 a i*8+7 di ogni riga.
        for (int i = 0; i < Font8x8.COUNT; i++) {
            byte[] g = Font8x8.glyph(Font8x8.LO_CHAR + i);
            for (int row = 0; row < Font8x8.HEIGHT; row++) {
                out[row * MODULO + i] = g[row];
            }
        }

        int base = DATA_BYTES;
        for (int i = 0; i < Font8x8.COUNT; i++) {
            int bitOffset = i * Font8x8.WIDTH;
            out[base + i * 4]     = (byte) (bitOffset >> 8);
            out[base + i * 4 + 1] = (byte) bitOffset;
            out[base + i * 4 + 2] = 0;
            out[base + i * 4 + 3] = (byte) Font8x8.WIDTH;
        }
        return out;
    }
}
