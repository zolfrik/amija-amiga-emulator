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

package io.github.zolfrik.amija.assets;

import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.whdload.LhaExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scarica e installa un {@link AssetPackage} per conto dell'utente.
 *
 * <p>Il flusso è: download in un file temporaneo (calcolando lo SHA-256 mentre i
 * byte scorrono) → verifica → scompattazione selettiva nella cartella di
 * destinazione → cancellazione dei temporanei. Nessuna dipendenza esterna: HTTP dal
 * client del JDK, ZIP da {@code java.util.zip}, LHA dall'estrattore che già serve i
 * pacchetti WHDLoad.
 *
 * <p><b>La verifica è in due tempi, con severità diverse.</b> Il controllo
 * <i>strutturale</i> (le voci attese devono esserci) è bloccante: se manca, il file
 * scaricato non è quello che credevamo e installarlo sarebbe un danno. Il confronto
 * dello SHA-256 è invece solo un avvertimento: gli URL a monte non sono versionati,
 * quindi una nuova release cambia l'impronta in modo del tutto legittimo, e
 * trattarlo come errore fatale romperebbe l'assistente a ogni aggiornamento altrui.
 */
public final class AssetInstaller {

    /** Si presenta onestamente al server, così l'origine del traffico è tracciabile. */
    private static final String USER_AGENT = "AmiJa/1.0 (+https://github.com/zolfrik/amija)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int BUFFER = 64 * 1024;

    private AssetInstaller() {}

    /** Riscontro dell'avanzamento verso l'interfaccia; tutti i metodi sono opzionali. */
    public interface Progress {
        /** Cambio di fase (download, verifica, scompattazione...). */
        default void stage(String message) {}
        /** Byte scaricati finora su totale atteso ({@code total <= 0} se ignoto). */
        default void bytes(long done, long total) {}
        /** {@code true} se l'utente ha chiesto di annullare. */
        default boolean cancelled() { return false; }
    }

    /**
     * Esito dell'installazione.
     *
     * @param files    numero di file scritti nella cartella di destinazione
     * @param warnings avvertimenti non bloccanti (p.es. impronta difforme)
     */
    public record Result(int files, List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Scarica e installa il pacchetto.
     *
     * @throws IOException se il download fallisce, se l'archivio non contiene le voci
     *                     attese o se non se ne ricava alcun file. Il messaggio riporta
     *                     sempre URL e cartella di destinazione, così l'utente può fare
     *                     a mano ciò che l'assistente non è riuscito a fare.
     */
    public static Result install(AssetPackage pkg, Progress progress) throws IOException {
        Progress p = (progress != null) ? progress : new Progress() {};
        List<String> warnings = new ArrayList<>();
        Path archive = null;
        try {
            p.stage(Messages.t("assets.stage.download", pkg.displayName()));
            archive = download(pkg, p, warnings);

            p.stage(Messages.t("assets.stage.verify", pkg.displayName()));
            int written = switch (pkg.format()) {
                case ZIP -> installZip(pkg, archive, p);
                case LHA -> installLha(pkg, archive, p);
            };
            if (written == 0) {
                throw new IOException(Messages.t("assets.error.empty", pkg.url(),
                        pkg.destDir().toAbsolutePath().toString()));
            }
            return new Result(written, warnings);
        } finally {
            deleteQuietly(archive);
        }
    }

    // =========================================================================
    // Download
    // =========================================================================

    private static Path download(AssetPackage pkg, Progress p, List<String> warnings) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(pkg.url()))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(Messages.t("assets.error.cancelled"), ie);
        }
        if (response.statusCode() != 200) {
            throw new IOException(Messages.t("assets.error.http",
                    String.valueOf(response.statusCode()), pkg.url()));
        }

        long total = response.headers().firstValueAsLong("content-length").orElse(pkg.approxBytes());
        MessageDigest digest = sha256();
        Path tmp = Files.createTempFile("amija-asset-", ".bin");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[BUFFER];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (p.cancelled()) throw new IOException(Messages.t("assets.error.cancelled"));
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                p.bytes(done, total);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (pkg.sha256() != null && !pkg.sha256().equalsIgnoreCase(actual)) {
            // Non è un errore: l'URL punta sempre all'ultimo rilascio, che a monte
            // cambia senza avvisarci. Si avverte e si prosegue col controllo strutturale.
            warnings.add(Messages.t("assets.warn.hash", pkg.displayName(), actual, pkg.sha256()));
        }
        return tmp;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 è obbligatorio in ogni JDK", e);
        }
    }

    // =========================================================================
    // Scompattazione
    // =========================================================================

    private static int installZip(AssetPackage pkg, Path archive, Progress p) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<String> names = zip.stream().map(ZipEntry::getName).toList();
            requireEntries(pkg, names);

            p.stage(Messages.t("assets.stage.extract", pkg.displayName()));
            Path base = base(pkg);
            int written = 0;
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.isDirectory()) continue;
                String rel = pkg.map(entry.getName());
                if (rel == null) continue;
                if (p.cancelled()) throw new IOException(Messages.t("assets.error.cancelled"));
                Path dest = resolveSafe(base, rel);
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                written++;
            }
            return written;
        }
    }

    private static int installLha(AssetPackage pkg, Path archive, Progress p) throws IOException {
        // L'estrattore LHA non sa elencare senza estrarre: si scompatta in un'area
        // temporanea, si verifica la struttura e si copia solo ciò che serve.
        Path work = Files.createTempDirectory("amija-asset-");
        try {
            LhaExtractor.extract(archive.toString(), work);
            List<String> names = relativePaths(work);
            requireEntries(pkg, names);

            p.stage(Messages.t("assets.stage.extract", pkg.displayName()));
            Path base = base(pkg);
            int written = 0;
            for (String name : names) {
                String rel = pkg.map(name);
                if (rel == null) continue;
                if (p.cancelled()) throw new IOException(Messages.t("assets.error.cancelled"));
                Path dest = resolveSafe(base, rel);
                Files.createDirectories(dest.getParent());
                Files.copy(work.resolve(name), dest, StandardCopyOption.REPLACE_EXISTING);
                written++;
            }
            return written;
        } finally {
            deleteRecursively(work);
        }
    }

    /** Percorsi dei file regolari sotto {@code root}, relativi e con separatore '/'. */
    private static List<String> relativePaths(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /** Verifica bloccante: l'archivio deve contenere le voci che ci aspettiamo. */
    private static void requireEntries(AssetPackage pkg, List<String> names) throws IOException {
        for (String required : pkg.requiredEntries()) {
            if (!names.contains(required)) {
                throw new IOException(Messages.t("assets.error.structure",
                        pkg.displayName(), required, pkg.url()));
            }
        }
    }

    private static Path base(AssetPackage pkg) throws IOException {
        Path base = pkg.destDir().toAbsolutePath().normalize();
        Files.createDirectories(base);
        return base;
    }

    /**
     * Risolve {@code rel} dentro {@code base} rifiutando le fughe dalla cartella di
     * destinazione: un archivio ostile può contenere voci tipo {@code ../../x} e
     * scriverebbe dove non deve (zip slip). I mapper del catalogo non le produrrebbero
     * mai, ma la guardia sta qui perché è qui che si scrive su disco.
     */
    private static Path resolveSafe(Path base, String rel) throws IOException {
        Path dest = base.resolve(rel).normalize();
        if (!dest.startsWith(base)) {
            throw new IOException(Messages.t("assets.error.path", rel));
        }
        return dest;
    }

    // =========================================================================

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // File temporaneo: se resta, ci penserà il sistema operativo.
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // Idem: area temporanea, la sua sopravvivenza non è un errore per l'utente.
        }
    }
}
