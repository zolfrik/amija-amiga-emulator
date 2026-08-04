package io.github.zolfrik.amija.romgen;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Genera la schermata che la ROM AmiJa mostra quando non c'e' nessun drive
 * configurato: logo su fondo bianco, "AmiJa" sotto, e piu' in piccolo
 * "press F12 for setup".
 *
 * L'immagine viene composta qui, a tempo di build, e finisce nella ROM come
 * blob planare gia' pronto: la ROM deve solo puntarci i BPLxPT.
 *
 * Il testo usa il font 8x8 del progetto ({@link Font8x8}), ingrandito per
 * interi. Un font vettoriale di sistema, ridotto a questa risoluzione, produce
 * lettere sfocate e fuori tono rispetto a una schermata Amiga; un bitmap font
 * scalato 3x resta netto ed e' quello che una macchina dell'epoca mostrerebbe.
 *
 * Formato prodotto: 320x256, {@value #PLANES} bitplane non interlacciati
 * (piano 0 completo, poi piano 1, poi piano 2), preceduti dalla palette a 12
 * bit come {@value #COLORS} word big-endian.
 */
public final class SplashImage {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 256;
    public static final int PLANES = 3;
    public static final int COLORS = 1 << PLANES;
    public static final int BYTES_PER_ROW = WIDTH / 8;
    public static final int PLANE_BYTES = BYTES_PER_ROW * HEIGHT;

    // Le tre tinte del logo sono campionate da logo/AmiJa.png, non stimate a
    // occhio: #0D7ABD, #DE2B31 e #800D2A, arrotondate ai 12 bit dell'OCS.
    private static final int[] PALETTE = {
        0xFFF, // 0 bianco: sfondo
        0x07B, // 1 blu della tazza
        0xD23, // 2 rosso del check
        0x802, // 3 rosso scuro del check
        0x222, // 4 quasi nero: "AmiJa"
        0x777, // 5 grigio: "press F12 for setup"
        0xFFF, // 6 inutilizzato
        0xFFF, // 7 inutilizzato
    };

    private static final Color[] RGB = new Color[COLORS];
    static {
        for (int i = 0; i < COLORS; i++) {
            int c = PALETTE[i];
            RGB[i] = new Color(((c >> 8) & 0xF) * 17, ((c >> 4) & 0xF) * 17, (c & 0xF) * 17);
        }
    }

    private static final String LOGO = "logo/AmiJa.png";
    private static final String LOGO_FALLBACK = "src/main/resources/icons/amija-256.png";

    private static final String TITLE = "AmiJa";
    private static final String HINT = "press F12 for setup";

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "rom/splash.bin");
        byte[] blob = build();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, blob);
        System.out.println("[SPLASH] scritto " + out + " (" + blob.length + " byte)");
    }

    /** Palette + bitplane, nel formato che la ROM si aspetta. */
    public static byte[] build() throws IOException {
        int[][] px = compose();

        byte[] out = new byte[COLORS * 2 + PLANES * PLANE_BYTES];
        for (int i = 0; i < COLORS; i++) {
            out[i * 2] = (byte) (PALETTE[i] >> 8);
            out[i * 2 + 1] = (byte) PALETTE[i];
        }
        int base = COLORS * 2;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int idx = px[y][x];
                for (int p = 0; p < PLANES; p++) {
                    if ((idx & (1 << p)) == 0) continue;
                    out[base + p * PLANE_BYTES + y * BYTES_PER_ROW + (x >> 3)]
                            |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return out;
    }

    /** La schermata come indici di palette, che e' la forma in cui va planarizzata. */
    private static int[][] compose() throws IOException {
        int[][] px = new int[HEIGHT][WIDTH];   // 0 = bianco ovunque

        // --- logo, appiattito su bianco per togliere il canale alfa ---------
        BufferedImage logo = loadLogo();
        int size = 132;
        BufferedImage flat = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.drawImage(logo, 0, 0, size, size, null);
        g.dispose();

        int logoX = (WIDTH - size) / 2;
        int logoY = 14;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                px[logoY + y][logoX + x] = nearest(flat.getRGB(x, y));
            }
        }

        // --- testo, col font del progetto ingrandito per interi -------------
        // Il titolo va proporzionale: a passo fisso e a 4x i vuoti fra le
        // lettere strette diventano voragini.
        int titleW = Font8x8.widthProportional(TITLE, 4, 4);
        Font8x8.drawProportional(px, TITLE, (WIDTH - titleW) / 2, 168, 4, 4, 4);
        Font8x8.draw(px, HINT, (WIDTH - Font8x8.width(HINT, 1)) / 2, 218, 5, 1);
        return px;
    }

    private static BufferedImage loadLogo() throws IOException {
        File f = new File(LOGO);
        if (!f.isFile()) f = new File(LOGO_FALLBACK);
        if (!f.isFile()) throw new IOException("logo non trovato: " + LOGO + " ne' " + LOGO_FALLBACK);
        return ImageIO.read(f);
    }

    /** Indice di palette piu' vicino, in distanza euclidea RGB. */
    private static int nearest(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < COLORS; i++) {
            int dr = r - RGB[i].getRed(), dg = g - RGB[i].getGreen(), db = b - RGB[i].getBlue();
            int d = dr * dr + dg * dg + db * db;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }
}
