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

package io.github.zolfrik.amija.display;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JFileChooser;
import javax.swing.UIManager;
import javax.swing.filechooser.FileSystemView;
import javax.swing.filechooser.FileView;

/**
 * The {@link JFileChooser} used by the "..." buttons of the F12 popup, made usable on huge
 * folders too: those with thousands of ADFs, the norm when keeping a collection.
 *
 * <p><b>The problem.</b> Swing's chooser pays a <em>system call per file</em>, and pays it
 * more than once: inside {@code FileSystemView.getFiles()}, then in {@code accept()}
 * ({@code FileNameExtensionFilter} asks {@code isDirectory()} <em>before</em> looking at the
 * extension) and finally in {@code isTraversable()}. Measured on 20,000 files: listing the
 * names alone costs 45 ms, but one {@code isDirectory()} per file costs 950, and the total
 * opening time reaches 2.0-2.8 s.
 *
 * <p><b>The cure.</b> {@link Scanner#getFiles} lists the folder with
 * {@link Files#walkFileTree} at depth 1: the attributes arrive <em>together</em> with the
 * directory entry (it is what FindFirstFile/FindNextFile already return), so they cost
 * nothing. Each entry becomes a {@link ScannedFile} that remembers those attributes, so no
 * higher layer goes back to the disk. Same 20,000-file folder: listing 50 ms instead of 950,
 * total opening <b>0.32 s instead of 2.5</b>.
 *
 * <p>The instance is single and reused ({@link #acquire()}): building a {@code JFileChooser}
 * with the Windows Look and Feel alone costs ~300 ms, because it fills the "Look in"
 * drop-down by querying the shell.
 *
 * <p><b>What changes compared with the stock chooser.</b> The per-type file icons become
 * generic (the Look and Feel's) and the sorting is purely alphabetical instead of numeric:
 * "Game 10" comes before "Game 9". The latter cannot be avoided: {@code ShellFolder.sort}
 * uses {@code compareToIgnoreCase} hard-wired for files that are not ShellFolders, and the
 * model's sort cannot be overridden without replacing the chooser's UI. On a collection
 * sorted by title the difference does not show. The shortcut bar (Desktop, Documents, This
 * PC, Network) stays; see the note on {@code FileChooser.useShellFolder} in
 * {@link #acquire()}.
 *
 * <p><b>Under FlatLaf.</b> The gain does not depend on the Look and Feel: it is in the
 * {@code walkFileTree} of the {@link Scanner}, not in the UI. Measured on 2026-08-06 on the
 * same 28,416-file folder: opening in <b>364 ms with the Windows LAF, 402 ms with
 * FlatLaf</b>, against 2.5 s before the fix. Building the chooser is even faster under
 * FlatLaf (621 ms against 795), because it does not query the shell.
 */
final class FastFileChooser {

    /** Single instance: rebuilding it at every "..." would cost ~300 ms. */
    private static JFileChooser instance;

    private FastFileChooser() {}

    /**
     * The shared chooser, brought back to the state of a freshly built one. The caller sets
     * title, filter, folder and selection, and then calls
     * {@link JFileChooser#rescanCurrentDirectory()} before showing it.
     */
    static JFileChooser acquire() {
        if (instance == null) {
            instance = new JFileChooser(new Scanner());
            instance.setFileView(new FlatView());
            // Under FlatLaf, the Look and Feel in use, the shortcut bar is there anyway: this
            // line is not what earns it. It matters if the system Look and Feel is used
            // again (the fallback of Amija.applyLookAndFeel), where the Windows UI creates
            // the bar ONLY if this is true; by itself it would be false, because the
            // FileSystemView is not the system one. Measured on 2026-08-06:
            //   Windows LAF: useShellFolder=false -> bar absent, true -> present
            //   FlatLaf:     present in both cases
            instance.putClientProperty("FileChooser.useShellFolder", Boolean.TRUE);
        }
        // State identical to a new chooser's: the current filter goes back to "all files"
        // and the "File type" drop-down does not accumulate the filters of the previous
        // rounds (ROM, ADF, HDF, LHA alternate on the same object).
        instance.resetChoosableFileFilters();
        instance.setSelectedFile(null);
        return instance;
    }

    /**
     * A {@link File} that already knows its attributes, taken from the directory scan:
     * {@code isDirectory()} and company no longer cost a syscall.
     *
     * <p>They are a snapshot of the moment of the scan, which is exactly what the chooser
     * shows, refreshed at every {@code rescanCurrentDirectory()}.
     */
    private static final class ScannedFile extends File {

        private static final long serialVersionUID = 1L;

        private final boolean directory;
        private final boolean hidden;
        private final long size;
        private final long modified;

        ScannedFile(File parent, String name, BasicFileAttributes attrs) {
            super(parent, name);
            this.directory = attrs.isDirectory();
            this.hidden = attrs instanceof DosFileAttributes dos && dos.isHidden();
            this.size = attrs.size();
            this.modified = attrs.lastModifiedTime().toMillis();
        }

        @Override public boolean isDirectory() { return directory; }
        @Override public boolean isFile() { return !directory; }
        @Override public boolean exists() { return true; }
        @Override public boolean isHidden() { return hidden; }
        @Override public long length() { return size; }
        @Override public long lastModified() { return modified; }
    }

    /** Lists a folder in a single pass, attributes included. */
    private static final class Scanner extends FileSystemView {

        /** For everything but the file listing the system behaviour applies. */
        private final FileSystemView system = FileSystemView.getFileSystemView();

        @Override
        public File[] getFiles(File dir, boolean useFileHiding) {
            Path root;
            try {
                root = dir.toPath();
            } catch (InvalidPathException e) {
                // "This PC", "Network": virtual shell folders, whose path is a placeholder
                // ("ShellFolder: 0x11") and not a Windows path. Their content (the drives,
                // the network machines) exists only for the shell, so it is delegated here.
                // Without this branch the exception left getFiles and killed Swing's loading
                // thread: no error on screen, just the listing staying empty.
                //
                // The delegation must be protected too: FileSystemView.getFiles goes through
                // ShellFolder.getShellFolder, which on a File that is not a ShellFolder
                // retries the same Paths.get and throws the same exception.
                try {
                    return system.getFiles(dir, useFileHiding);
                } catch (RuntimeException ignored) {
                    return new File[0];
                }
            }
            List<File> out = new ArrayList<>(1024);
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 1,
                        new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) {
                        // The starting folder is not a child of itself; for subfolders the
                        // entry is enough, not the content.
                        if (p.equals(root)) return FileVisitResult.CONTINUE;
                        add(out, dir, p, a, useFileHiding);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                        // At the depth limit subfolders also come through here, depending on
                        // the JDK version.
                        add(out, dir, p, a, useFileHiding);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path p, IOException e) {
                        return FileVisitResult.CONTINUE;   // unreadable entry: skipped
                    }
                });
            } catch (IOException | RuntimeException e) {
                // Folder gone, permissions, invalid path: empty listing, as the stock chooser
                // does.
                return new File[0];
            }
            return out.toArray(new File[0]);
        }

        private static void add(List<File> out, File parent, Path p, BasicFileAttributes a,
                boolean useFileHiding) {
            Path name = p.getFileName();
            if (name == null) return;
            ScannedFile f = new ScannedFile(parent, name.toString(), a);
            if (useFileHiding && f.isHidden()) return;
            out.add(f);
        }

        @Override
        public String getSystemDisplayName(File f) {
            // Only our entries skip the shell: the sidebar shortcuts must stay "This PC",
            // "Network"... and only the shell has their names.
            if (f instanceof ScannedFile) {
                String name = f.getName();
                return name.isEmpty() ? f.getAbsolutePath() : name;
            }
            return system.getSystemDisplayName(f);
        }

        @Override
        public Boolean isTraversable(File f) {
            return f instanceof ScannedFile ? f.isDirectory() : system.isTraversable(f);
        }

        @Override public File createNewFolder(File containingDir) throws IOException {
            return system.createNewFolder(containingDir);
        }
        @Override public File[] getRoots() { return system.getRoots(); }
        @Override public File getHomeDirectory() { return system.getHomeDirectory(); }
        @Override public File getDefaultDirectory() { return system.getDefaultDirectory(); }
    }

    /**
     * Spares the shell the question "what icon and what name does this file have", which
     * would otherwise come once per drawn row.
     */
    private static final class FlatView extends FileView {

        private final Icon fileIcon = UIManager.getIcon("FileView.fileIcon");
        private final Icon directoryIcon = UIManager.getIcon("FileView.directoryIcon");

        // null = "I do not know", and the chooser falls back on the system behaviour: what
        // the sidebar entries, which are not ScannedFiles, need.

        @Override
        public Icon getIcon(File f) {
            if (!(f instanceof ScannedFile)) return null;
            return f.isDirectory() ? directoryIcon : fileIcon;
        }

        @Override
        public String getName(File f) {
            if (!(f instanceof ScannedFile)) return null;
            String name = f.getName();
            return name.isEmpty() ? f.getAbsolutePath() : name;
        }

        @Override public String getTypeDescription(File f) { return null; }
        @Override public String getDescription(File f) { return null; }
    }
}
