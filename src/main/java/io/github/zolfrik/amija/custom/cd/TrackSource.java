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

package io.github.zolfrik.amija.custom.cd;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Random access to one file of a CD image, in a folder or inside a zip archive.
 *
 * <p>A zip entry is compressed: a read near its start (the headers of a sheet or a WAVE file)
 * is served by inflating the entry from the start; the first read further in extracts the
 * entry to a temporary file, deleted at {@link #close()}.
 */
public abstract class TrackSource implements Closeable {

    /** Size in bytes. */
    public abstract long size();

    /** Reads up to {@code len} bytes at {@code pos}; returns the bytes read. */
    public abstract int read(long pos, byte[] dst, int off, int len) throws IOException;

    /** The folder or archive the files of an image are looked up in. */
    public interface Container extends Closeable {
        /** The first file whose name satisfies {@code test}, or {@code null}. */
        String find(Predicate<String> test);

        byte[] readAll(String name) throws IOException;

        /** The file {@code name} refers to, or {@code null}. */
        TrackSource open(String name) throws IOException;
    }

    private static String baseName(String name) {
        int p = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return p < 0 ? name : name.substring(p + 1);
    }

    // ---------------------------------------------------------------------------------------
    // Folder
    // ---------------------------------------------------------------------------------------

    static Container folder(Path dir) {
        return new Container() {
            private Path resolve(String name) {
                Path p = dir.resolve(name);
                if (Files.isRegularFile(p)) return p;
                p = dir.resolve(baseName(name));
                return Files.isRegularFile(p) ? p : null;
            }

            @Override
            public String find(Predicate<String> test) {
                try (var s = Files.list(dir)) {
                    return s.map(p -> p.getFileName().toString()).filter(test).sorted().findFirst().orElse(null);
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public byte[] readAll(String name) throws IOException {
                Path p = resolve(name);
                if (p == null) throw new IOException("not found: " + name);
                return Files.readAllBytes(p);
            }

            @Override
            public TrackSource open(String name) throws IOException {
                Path p = resolve(name);
                return p == null ? null : new FileSource(p, false);
            }

            @Override
            public void close() {
            }
        };
    }

    private static final class FileSource extends TrackSource {
        private final Path path;
        private final boolean temporary;
        private final RandomAccessFile file;

        FileSource(Path path, boolean temporary) throws IOException {
            this.path = path;
            this.temporary = temporary;
            this.file = new RandomAccessFile(path.toFile(), "r");
        }

        @Override
        public long size() {
            try {
                return file.length();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public synchronized int read(long pos, byte[] dst, int off, int len) throws IOException {
            if (pos < 0 || pos >= file.length()) return 0;
            file.seek(pos);
            int total = 0;
            while (total < len) {
                int n = file.read(dst, off + total, len - total);
                if (n < 0) break;
                total += n;
            }
            return total;
        }

        @Override
        public void close() throws IOException {
            file.close();
            if (temporary) Files.deleteIfExists(path);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Zip
    // ---------------------------------------------------------------------------------------

    static Container zip(Path archive) throws IOException {
        ZipFile zip = new ZipFile(archive.toFile());
        return new Container() {
            private ZipEntry entry(String name) {
                ZipEntry e = zip.getEntry(name);
                if (e != null) return e;
                String base = baseName(name);
                for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                    ZipEntry z = en.nextElement();
                    if (!z.isDirectory() && baseName(z.getName()).equalsIgnoreCase(base)) return z;
                }
                return null;
            }

            @Override
            public String find(Predicate<String> test) {
                String found = null;
                for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                    ZipEntry z = en.nextElement();
                    if (z.isDirectory() || !test.test(z.getName())) continue;
                    if (found == null || z.getName().compareTo(found) < 0) found = z.getName();
                }
                return found;
            }

            @Override
            public byte[] readAll(String name) throws IOException {
                ZipEntry e = entry(name);
                if (e == null) throw new IOException("not found in archive: " + name);
                try (InputStream in = zip.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }

            @Override
            public TrackSource open(String name) {
                ZipEntry e = entry(name);
                return e == null ? null : new ZipEntrySource(zip, e);
            }

            @Override
            public void close() throws IOException {
                zip.close();
            }
        };
    }

    private static final class ZipEntrySource extends TrackSource {
        /** Reads that end within this many bytes are served by inflating from the start. */
        private static final int HEAD = 64 * 1024;

        private final ZipFile zip;
        private final ZipEntry entry;
        private FileSource extracted;

        ZipEntrySource(ZipFile zip, ZipEntry entry) {
            this.zip = zip;
            this.entry = entry;
        }

        @Override
        public long size() {
            return entry.getSize();
        }

        @Override
        public synchronized int read(long pos, byte[] dst, int off, int len) throws IOException {
            if (extracted == null && pos + len <= HEAD) {
                try (InputStream in = zip.getInputStream(entry)) {
                    in.skipNBytes(Math.min(pos, entry.getSize()));
                    return Math.max(0, in.readNBytes(dst, off, len));
                }
            }
            if (extracted == null) {
                Path tmp = Files.createTempFile("amija-cd", ".track");
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                tmp.toFile().deleteOnExit();
                extracted = new FileSource(tmp, true);
            }
            return extracted.read(pos, dst, off, len);
        }

        @Override
        public synchronized void close() throws IOException {
            if (extracted != null) extracted.close();
            extracted = null;
        }

        @Override
        public String toString() {
            return entry.getName().toLowerCase(Locale.ROOT);
        }
    }
}
