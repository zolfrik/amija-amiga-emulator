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
 * Downloads and installs an {@link AssetPackage} on the user's behalf.
 *
 * <p>The flow: download into a temporary file (computing the SHA-256 while the bytes flow) ->
 * check -> selective unpacking into the destination folder -> deletion of the temporaries.
 * No external dependency: HTTP from the JDK's client, ZIP from {@code java.util.zip}, LHA from
 * the extractor that already serves the WHDLoad packages.
 *
 * <p><b>The check has two stages, of different severity.</b> The <i>structural</i> check (the
 * expected entries must be there) blocks: if it fails, the downloaded file is not what we
 * thought and installing it would do harm. The SHA-256 comparison is only a warning: the
 * upstream URLs are not versioned, so a new release changes the fingerprint quite
 * legitimately, and treating it as a fatal error would break the assistant at every upstream
 * update.
 */
public final class AssetInstaller {

    /** Introduces itself honestly to the server, so the origin of the traffic is traceable. */
    private static final String USER_AGENT = "AmiJa/1.0 (+https://github.com/zolfrik/amija)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int BUFFER = 64 * 1024;

    private AssetInstaller() {}

    /** Progress feedback to the interface; every method is optional. */
    public interface Progress {
        /** Phase change (download, verification, unpacking...). */
        default void stage(String message) {}
        /** Bytes downloaded so far out of the expected total ({@code total <= 0} if unknown). */
        default void bytes(long done, long total) {}
        /** {@code true} if the user asked to cancel. */
        default boolean cancelled() { return false; }
    }

    /**
     * Outcome of the installation.
     *
     * @param files    number of files written to the destination folder
     * @param warnings non-blocking warnings (e.g. fingerprint mismatch)
     */
    public record Result(int files, List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Downloads and installs the package.
     *
     * @throws IOException if the download fails, if the archive does not contain the expected
     *                     entries or if no file comes out of it. The message always reports
     *                     URL and destination folder, so the user can do by hand what the
     *                     assistant could not.
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
            // Not an error: the URL always points to the latest release, which changes
            // upstream without telling us. Warn and carry on with the structural check.
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
        // The LHA extractor cannot list without extracting: unpack into a temporary area,
        // check the structure and copy only what is needed.
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

    /** Paths of the regular files under {@code root}, relative and with '/' separator. */
    private static List<String> relativePaths(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /** Blocking check: the archive must contain the entries we expect. */
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
     * Resolves {@code rel} inside {@code base}, refusing escapes from the destination folder:
     * a hostile archive can contain entries like {@code ../../x} and would write where it must
     * not (zip slip). The catalogue's mappers would never produce them, but the guard is here
     * because this is where the disk is written.
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
            // Temporary file: if it stays, the operating system will take care of it.
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
            // Likewise: temporary area, its survival is not an error for the user.
        }
    }
}
