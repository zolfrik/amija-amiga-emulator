package io.github.zolfrik.amija.romgen;

import io.github.zolfrik.amija.romgen.asm.Asm68k;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Costruisce l'immagine della ROM AmiJa: genera lo splash, assembla
 * {@code rom/amija.asm} e impagina il risultato nella dimensione del socket.
 *
 * Uso: RomBuilder [file-di-uscita]
 */
public final class RomBuilder {

    /** Dimensione del socket: 512 KB, base $F80000. */
    public static final int ROM_SIZE = 512 * 1024;
    public static final int ROM_BASE = 0xF80000;

    public static final Path SOURCE = Path.of("rom/amija.asm");
    public static final Path SPLASH = Path.of("rom/splash.bin");
    public static final Path FONT = Path.of("rom/font.bin");
    /** Include generato col tag $VER: la versione sta in {@link RomVersion}. */
    public static final Path VERSION = Path.of("rom/version.i");
    /** L'immagine finisce nelle risorse: e' da li' che BuiltinRom la carica. */
    public static final Path DEFAULT_OUTPUT = Path.of("src/main/resources/rom/amija.rom");

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : DEFAULT_OUTPUT.toString());
        byte[] rom = build(true);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, rom);
        System.out.println("[ROM] scritto " + out + " (" + rom.length + " byte)");
        System.exit(0);
    }

    /** Assembla la ROM e la restituisce gia' impaginata a {@link #ROM_SIZE}. */
    public static byte[] build(boolean verbose) throws IOException {
        byte[] splash = SplashImage.build();
        Files.createDirectories(SPLASH.toAbsolutePath().getParent());
        Files.write(SPLASH, splash);
        byte[] font = FontImage.build();
        Files.write(FONT, font);
        Files.writeString(VERSION, RomVersion.includeSource());

        Asm68k.Image img = Asm68k.assembleFile(SOURCE);
        if (img.org() != ROM_BASE) {
            throw new IOException("la ROM deve essere assemblata a "
                    + Integer.toHexString(ROM_BASE) + ", non " + Integer.toHexString(img.org()));
        }
        if (img.data().length > ROM_SIZE) {
            throw new IOException("immagine troppo grande: " + img.data().length + " > " + ROM_SIZE);
        }

        byte[] rom = new byte[ROM_SIZE];
        Arrays.fill(rom, (byte) 0xFF);          // le celle non usate leggono $FF
        System.arraycopy(img.data(), 0, rom, 0, img.data().length);

        if (verbose) {
            System.out.printf("[ROM] %s%n", RomVersion.TAG);
            System.out.printf("[ROM] codice+dati: %d byte su %d (%.1f%% usato)%n",
                    img.data().length, ROM_SIZE, 100.0 * img.data().length / ROM_SIZE);
            System.out.printf("[ROM] splash: %d byte%n", splash.length);
            Map<String, Integer> sym = img.symbols();
            for (String k : new String[]{"reset", "romVersionTag", "showSplash", "copperList", "splashData"}) {
                Integer v = sym.get(k);
                if (v != null) System.out.printf("[ROM]   %-14s = $%06X%n", k, v);
            }
        }
        return rom;
    }
}
