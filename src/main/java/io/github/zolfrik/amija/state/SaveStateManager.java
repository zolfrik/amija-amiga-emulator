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

package io.github.zolfrik.amija.state;

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.cpu.Motorola68000;
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.custom.FastRam;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.memory.RAM;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Salvataggio e ripristino dell'intero stato della macchina A500 su file. Ci sono
 * {@link #SLOT_COUNT} slot indipendenti, uno per cartella ({@code savestate/Slot1/} …
 * {@code savestate/Slot5/}); quale sia quello attivo lo dice la configurazione
 * ({@code savestate.slot}), qui si ricevono solo numeri di slot. Formato binario custom
 * compresso (gzip), <b>non</b> serializzazione Java: lo stato è raccolto dal
 * {@link ReflectiveState} sui componenti reali della macchina.
 *
 * <p>Header: magic {@code A500SAVE}, versione formato, l'istante del salvataggio, un
 * <b>fingerprint</b> della configurazione (chipset, dimensioni RAM, modo video, Kickstart,
 * dischi montati) e una copia integrale di {@code amiga.properties}. Al caricamento un
 * fingerprint diverso viene rifiutato con {@link IncompatibleStateException}; la copia
 * della configurazione è lì perché il chiamante possa allora <i>ricostruire</i> la
 * macchina giusta ({@link #readInfo}) invece di limitarsi a dire di no.
 *
 * <p>Lo snapshot va preso/applicato a <b>macchina in pausa</b> (come quando il popup F12
 * è aperto). Sia in salvataggio sia in caricamento la macchina viene prima portata a un
 * <b>confine pulito</b> (inizio frame + confine d'istruzione della CPU), così la coda-bus
 * del 68000 e i recorder per-scanline di Denise — grafi transitori non serializzati — sono
 * vuoti e non c'è nulla da perdere.
 */
public final class SaveStateManager {

    private static final byte[] MAGIC = "A500SAVE".getBytes(StandardCharsets.US_ASCII);
    /**
     * Versione del formato. 2: aggiunge all'header l'istante del salvataggio e la copia
     * di {@code amiga.properties}. 3: aggiunge le schede AutoConfig (FastRam,
     * HdController) fra i root, così la mappa di memoria dinamica si ricostruisce al
     * load. Gli slot di versione precedente non si caricano più — sono un'istantanea
     * sola e la si rifà premendo di nuovo "Save State".
     */
    private static final int VERSION = 3;

    /** Quanti slot di stato esistono. Sono numerati da 1 a {@code SLOT_COUNT}. */
    public static final int SLOT_COUNT = 5;

    /** Cartella che contiene le cartelle degli slot, relativa alla home. */
    public static final String STATE_DIR = "savestate";

    /** Nome del file di stato, dentro la cartella del proprio slot. */
    private static final String STATE_NAME = "state.a5s";

    /**
     * Screenshot che accompagna lo stato, dentro la cartella del proprio slot: nome
     * fisso, così ogni salvataggio sovrascrive l'immagine precedente di quello slot.
     */
    private static final String SHOT_NAME = "state.png";

    /**
     * Lo slot unico di prima che gli slot fossero cinque. Se esiste viene trasferito
     * nello Slot 1 da {@link #migrateLegacySlot()}: era l'unica partita congelata
     * dell'utente e non c'è ragione di perderla.
     */
    private static final String LEGACY_FILE = "savestate/state1.a5s";
    private static final String LEGACY_SHOT = "savestate/state1.png";

    private SaveStateManager() {}

    /**
     * Il numero di slot riportato dentro l'intervallo valido. Un valore fuori scala
     * arriva solo da un {@code amiga.properties} scritto a mano: si preferisce ricadere
     * su uno slot esistente piuttosto che sollevare un'eccezione in faccia all'utente.
     */
    public static int clampSlot(int slot) {
        return Math.min(SLOT_COUNT, Math.max(1, slot));
    }

    /** La cartella dello slot, risolta nella home dell'applicazione ({@link AppHome}). */
    public static File slotDir(int slot) {
        return AppHome.file(STATE_DIR + "/Slot" + clampSlot(slot));
    }

    /** Il file di stato dello slot, risolto nella home ({@link AppHome}). */
    public static File slotFile(int slot) {
        return new File(slotDir(slot), STATE_NAME);
    }

    /** Lo screenshot dello slot, risolto nella home ({@link AppHome}). */
    public static File slotScreenshot(int slot) {
        return new File(slotDir(slot), SHOT_NAME);
    }

    /** {@code true} se in quello slot c'è uno stato da caricare. */
    public static boolean slotPresent(int slot) {
        return slotFile(slot).isFile();
    }

    /**
     * Svuota uno slot: stato e screenshot. Ritorna {@code true} se c'era uno stato e ora
     * non c'è più. La cartella resta: è dove il prossimo salvataggio scriverà.
     */
    public static boolean deleteSlot(int slot) throws IOException {
        boolean had = slotPresent(slot);
        Files.deleteIfExists(slotFile(slot).toPath());
        Files.deleteIfExists(slotScreenshot(slot).toPath());
        return had;
    }

    /**
     * Porta nello Slot 1 lo stato scritto quando lo slot era uno solo
     * ({@code savestate/state1.a5s}). Non fa niente se quel file non c'è o se lo Slot 1
     * è già occupato — in quel caso l'utente ha già salvato nel nuovo schema e sarebbe la
     * copia vecchia a essere di troppo, non il contrario. Da chiamare una volta all'avvio;
     * un errore qui non impedisce nulla e viene solo segnalato a console.
     */
    public static void migrateLegacySlot() {
        File legacy = AppHome.file(LEGACY_FILE);
        if (!legacy.isFile() || slotPresent(1)) return;
        try {
            Files.createDirectories(slotDir(1).toPath());
            Files.move(legacy.toPath(), slotFile(1).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            File shot = AppHome.file(LEGACY_SHOT);
            if (shot.isFile()) {
                Files.move(shot.toPath(), slotScreenshot(1).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("[STATE] stato preesistente spostato nello Slot 1");
        } catch (IOException e) {
            System.err.println("[STATE] impossibile spostare lo stato preesistente"
                    + " nello Slot 1: " + e.getMessage());
        }
    }

    /** Rifiuto di uno slot incompatibile con la macchina/versione correnti. */
    public static final class IncompatibleStateException extends IOException {
        public IncompatibleStateException(String message) { super(message); }
    }

    /**
     * L'intestazione di uno slot, letta senza toccare la macchina.
     *
     * @param savedAt istante del salvataggio (millisecondi epoch)
     * @param config  {@code amiga.properties} com'era al salvataggio: la macchina che
     *                questo stato pretende
     */
    public record SlotInfo(long savedAt, String fingerprint, Properties config) { }

    // =========================================================================
    // Salvataggio
    // =========================================================================

    public static void save(File file, Motorola68000 cpu, AbstractAgnus agnus,
            GamePort port1, GamePort port2, AmigaConfig cfg) throws IOException {
        advanceToBoundary(agnus, cpu);
        List<Object> roots = buildRoots(cpu, agnus, port1, port2);
        byte[] configBlob = encodeConfig(cfg.snapshot());

        // Il temporaneo DEVE stare accanto alla destinazione: altrove finirebbe su un
        // altro file system e lo spostamento smetterebbe di essere atomico.
        Path target = file.toPath().toAbsolutePath();
        Path dir = target.getParent() != null ? target.getParent() : Path.of(".");
        Files.createDirectories(dir);
        // Si scrive su un temporaneo accanto alla destinazione e lo si sposta alla fine.
        // Scrivendo direttamente sullo slot, un'interruzione a meta' — crash, disco pieno,
        // la JVM che esce — distruggerebbe la partita che c'era senza averne salvata una
        // nuova, cioe' perderebbe tutto il valore della funzione.
        // Con lo spostamento o si vede lo slot nuovo o si vede ancora quello vecchio.
        Path tmp = Files.createTempFile(dir, file.getName(), ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp))))) {
                out.write(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(System.currentTimeMillis());
                out.writeUTF(fingerprint(cfg));
                out.writeInt(configBlob.length);
                out.write(configBlob);
                out.writeInt(roots.size());
                for (Object r : roots) {
                    out.writeUTF(r.getClass().getName());
                    ReflectiveState.write(out, r);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    // =========================================================================
    // Caricamento
    // =========================================================================

    public static void load(File file, Motorola68000 cpu, AbstractAgnus agnus,
            GamePort port1, GamePort port2, AmigaConfig cfg) throws IOException {
        // Porta la macchina CORRENTE a un confine pulito: così la coda-bus del 68000
        // (oggetto transitorio non presente nello slot) è vuota prima di sovrascrivere i
        // registri, evitando che operazioni bus in sospeso corrompano il resume.
        advanceToBoundary(agnus, cpu);

        try (DataInputStream in = openSlot(file)) {
            SlotInfo info = readHeader(in);
            if (!info.fingerprint().equals(fingerprint(cfg))) {
                throw new IncompatibleStateException(Messages.t("savestate.err.machine"));
            }
            List<Object> roots = buildRoots(cpu, agnus, port1, port2);
            int n = in.readInt();
            if (n != roots.size()) {
                throw new IncompatibleStateException(Messages.t("savestate.err.structure"));
            }
            for (int i = 0; i < n; i++) {
                String cls = in.readUTF();
                Object r = roots.get(i);
                if (!cls.equals(r.getClass().getName())) {
                    throw new IncompatibleStateException(
                            Messages.t("savestate.err.component", i, cls));
                }
                ReflectiveState.read(in, r);
            }
            // Le Region di Gary non stanno nello slot (sono oggetti): si riderivano
            // dallo stato appena ripristinato delle schede. Vedi buildRoots().
            agnus.rebuildAutoconfigMapping();
        }
    }

    /**
     * Legge la sola intestazione dello slot: quando è stato salvato e com'era fatta la
     * macchina. Non tocca nulla — serve al popup F12 per descrivere lo slot e a chi deve
     * ricostruire la macchina prima di ricaricarlo.
     */
    public static SlotInfo readInfo(File file) throws IOException {
        try (DataInputStream in = openSlot(file)) {
            return readHeader(in);
        }
    }

    private static DataInputStream openSlot(File file) throws IOException {
        return new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(file))));
    }

    /**
     * Tetto alla fotografia di {@code amiga.properties} dentro lo slot. Il file vero sta
     * sotto i 20 KB; 4 MB e' abbondante per qualunque crescita futura e lontano dal punto
     * in cui allocare alla cieca fa danno.
     */
    private static final int MAX_CONFIG_BYTES = 4 << 20;

    /** Consuma magic, versione e intestazione, lasciando lo stream sui componenti. */
    private static SlotInfo readHeader(DataInputStream in) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IncompatibleStateException(Messages.t("savestate.err.badfile"));
        }
        int ver = in.readInt();
        if (ver != VERSION) {
            throw new IncompatibleStateException(
                    Messages.t("savestate.err.version", ver, VERSION));
        }
        long savedAt = in.readLong();
        String fp = in.readUTF();
        // La lunghezza la dichiara il file: senza tetto, uno slot troncato o non-slot
        // dava OutOfMemoryError (un Error, quindi fuori dal catch di chi carica) oppure
        // NegativeArraySizeException, invece del messaggio «slot non compatibile».
        int configBytes = in.readInt();
        if (configBytes < 0 || configBytes > MAX_CONFIG_BYTES) {
            throw new IncompatibleStateException(Messages.t("savestate.err.badfile"));
        }
        byte[] blob = new byte[configBytes];
        in.readFully(blob);
        return new SlotInfo(savedAt, fp, decodeConfig(blob));
    }

    // =========================================================================
    // Configurazione dentro lo slot
    // =========================================================================

    private static byte[] encodeConfig(Properties props) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        props.store(buf, "amiga.properties as of this save state");
        return buf.toByteArray();
    }

    private static Properties decodeConfig(byte[] blob) throws IOException {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(blob));
        return p;
    }

    // =========================================================================
    // Costruzione della lista dei root (identica in save e load)
    // =========================================================================

    private static List<Object> buildRoots(Motorola68000 cpu, AbstractAgnus agnus,
            GamePort port1, GamePort port2) {
        List<Object> roots = new ArrayList<>();
        add(roots, cpu);
        add(roots, agnus);
        add(roots, field(agnus, "gary"));   // decoder indirizzi: flip-flop OVL, reset/halt…
        add(roots, field(agnus, "copper"));
        add(roots, field(agnus, "blitter"));
        add(roots, field(agnus, "denise"));

        Object paula = field(agnus, "paula");
        add(roots, paula);
        // Canali audio di Paula: inner class con stato DMA vivo (periodo, puntatore,
        // contatori) che persiste tra i frame → ognuno è un root a sé.
        Object channels = field(paula, "audioChannels");
        if (channels instanceof Object[]) {
            for (Object ch : (Object[]) channels) add(roots, ch);
        }

        addCia(roots, field(agnus, "ciaA"));
        addCia(roots, field(agnus, "ciaB"));
        add(roots, field(agnus, "keyboard"));
        add(roots, field(agnus, "batteryClock"));
        add(roots, port1);
        add(roots, port2);

        add(roots, field(agnus, "chipRAM"));
        add(roots, field(agnus, "slowRAM")); // può essere null (nessuna Slow RAM)
        Object fast = field(agnus, "fastRam");
        if (fast instanceof FastRam) {
            RAM fr = ((FastRam) fast).ram();
            add(roots, fr); // null se nessuna Fast RAM
        }
        // ⛔ Le SCHEDE AutoConfig, non solo la loro RAM. FastRam e HdController tengono
        // in campi primitivi lo stato che il Kickstart ha scritto durante l'AutoConfig
        // — a che punto della catena sono e a quale indirizzo base sono finite — e senza
        // di loro uno stato salvato a macchina già configurata si ripristinava con le
        // schede "da configurare" e la loro finestra di memoria sparita. La mappa di
        // Gary, che è una List e quindi il walker non la vede proprio, viene ricostruita
        // da questo stato in coda al load (agnus.rebuildAutoconfigMapping()).
        for (Object dev : agnus.autoConfigDevicesForState()) add(roots, dev);

        add(roots, agnus.getDf0());
        add(roots, agnus.getDf1());
        add(roots, agnus.getDf2());
        add(roots, agnus.getDf3());
        return roots;
    }

    /** Aggiunge un root non-null (i componenti opzionali assenti vengono saltati). */
    private static void add(List<Object> roots, Object o) {
        if (o != null) roots.add(o);
    }

    /**
     * Aggiunge una CIA e il suo {@code CIAPinSet}, inclusi i 5 pin (flag/tod/cnt/sp/irq):
     * i loro livelli di segnale (linea IRQ verso Paula, handshake tastiera, ingressi disk
     * /RDY-/TK0-/WPRO-/CHNG, OVL, LED) sono stato persistente in oggetti annidati che il
     * walker, fermandosi alle primitive, non cattura da solo.
     */
    private static void addCia(List<Object> roots, Object cia) {
        if (cia == null) return;
        add(roots, cia);
        Object pins = field(cia, "pinSet");
        if (pins == null) return;
        add(roots, pins);
        for (String p : new String[] { "flagPin", "todPin", "cntPin", "spPin", "irqPin" }) {
            add(roots, field(pins, p));
        }
    }

    // =========================================================================
    // Confine pulito
    // =========================================================================

    /**
     * Fa avanzare la macchina (a passi di color-clock) fino all'inizio del frame con la
     * CPU a un confine d'istruzione. Meno di un frame di tempo emulato. Va chiamato a
     * macchina in pausa (nessun altro thread tocca Agnus).
     */
    private static void advanceToBoundary(AbstractAgnus agnus, Motorola68000 cpu) {
        final int GUARD = 8_000_000; // ampio margine: un intero frame è ~70k color-clock
        for (int i = 0; i < GUARD; i++) {
            if (agnus.getVpos() == 0 && cpu.isAtInstructionBoundary()) return;
            agnus.risingEdge();
            agnus.fallingEdge();
        }
    }

    // =========================================================================
    // Fingerprint di compatibilità
    // =========================================================================

    private static String fingerprint(AmigaConfig c) {
        StringBuilder b = new StringBuilder(128);
        b.append("v").append(VERSION).append(';');
        b.append("chipset=").append(c.chipset()).append(';');
        b.append("chip=").append(c.chipRamKb()).append(';');
        b.append("slow=").append(c.slowRamKb()).append(';');
        b.append("fast=").append(c.fastRamKb()).append(';');
        b.append("video=").append(c.videoMode()).append(';');
        b.append("ks=").append(baseName(c.kickstartPath())).append(';');
        for (int i = 0; i < 4; i++) {
            b.append("df").append(i).append('=').append(baseName(c.driveDisk(i))).append(';');
        }
        return b.toString();
    }

    private static String baseName(String path) {
        if (path == null || path.isBlank()) return "";
        return new File(path.trim()).getName();
    }

    // =========================================================================
    // Riflessione: lettura di un campo (anche privato, anche nelle superclassi)
    // =========================================================================

    private static Object field(Object owner, String name) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                // prova nella superclasse
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("save state: campo inaccessibile " + name, e);
            }
        }
        throw new IllegalStateException("save state: campo assente " + name
                + " in " + owner.getClass().getName());
    }
}
