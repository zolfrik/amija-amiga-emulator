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
 * Il {@link JFileChooser} usato dai pulsanti «...» del popup F12, reso utilizzabile
 * anche sulle cartelle enormi — quelle da migliaia di ADF, che sono la norma quando si
 * tiene una collezione.
 *
 * <p><b>Il problema.</b> Il chooser di Swing paga una <em>chiamata di sistema per
 * file</em>, e la paga più di una volta: dentro {@code FileSystemView.getFiles()}, poi
 * in {@code accept()} — {@code FileNameExtensionFilter} interroga {@code isDirectory()}
 * <em>prima</em> di guardare l'estensione — e infine in {@code isTraversable()}.
 * Misurato su 20.000 file: elencare i soli nomi costa 45 ms, ma una {@code isDirectory()}
 * per file ne costa 950, e il totale di apertura arriva a 2,0-2,8 s.
 *
 * <p><b>La cura.</b> {@link Scanner#getFiles} elenca la cartella con
 * {@link Files#walkFileTree} a profondità 1: gli attributi arrivano <em>insieme</em> alla
 * voce di directory (è quello che restituisce già FindFirstFile/FindNextFile), quindi
 * costano zero. Ogni voce diventa uno {@link ScannedFile} che quegli attributi se li
 * ricorda, così nessuno strato più in alto torna a interrogare il disco. Stessa cartella
 * da 20.000 file: elenco 50 ms invece di 950, apertura totale <b>0,32 s invece di 2,5</b>.
 *
 * <p>L'istanza è unica e viene riusata ({@link #acquire()}): costruire un
 * {@code JFileChooser} col Look and Feel Windows costa da solo ~300 ms, perché popola la
 * tendina «Cerca in» interrogando la shell.
 *
 * <p><b>Cosa cambia rispetto al chooser di serie.</b> Le icone per tipo di file
 * diventano generiche (quelle del Look and Feel) e l'ordinamento è alfabetico puro
 * anziché numerico: «Game 10» viene prima di «Game 9». Quest'ultimo non è aggirabile —
 * {@code ShellFolder.sort} usa {@code compareToIgnoreCase} cablato per i file che non
 * sono ShellFolder, e il sort del modello non è sovrascrivibile senza rimpiazzare la UI
 * del chooser. Su una collezione ordinata per titolo la differenza non si vede. La barra
 * dei collegamenti (Desktop, Documenti, Questo PC, Rete) resta: la si conserva forzando
 * {@code FileChooser.useShellFolder}, che è la condizione con cui la UI di Windows decide
 * se crearla.
 */
final class FastFileChooser {

    /** Istanza unica: ricostruirla a ogni «...» costerebbe ~300 ms. */
    private static JFileChooser instance;

    private FastFileChooser() {}

    /**
     * Il chooser condiviso, riportato allo stato di uno appena costruito. Il chiamante
     * imposta titolo, filtro, cartella e selezione, e poi invoca
     * {@link JFileChooser#rescanCurrentDirectory()} prima di mostrarlo.
     */
    static JFileChooser acquire() {
        if (instance == null) {
            instance = new JFileChooser(new Scanner());
            instance.setFileView(new FlatView());
            // La UI di Windows crea la barra dei collegamenti solo se questo è vero;
            // di suo sarebbe falso, perché il FileSystemView non è quello di sistema.
            instance.putClientProperty("FileChooser.useShellFolder", Boolean.TRUE);
        }
        // Stato identico a quello di un chooser nuovo: il filtro corrente torna
        // "tutti i file" e la tendina "Tipo file" non accumula i filtri dei giri
        // precedenti (ROM, ADF, HDF, LHA si alternano sullo stesso oggetto).
        instance.resetChoosableFileFilters();
        instance.setSelectedFile(null);
        return instance;
    }

    /**
     * Un {@link File} che conosce già i propri attributi, presi dalla scansione della
     * directory: {@code isDirectory()} e compagni non costano più una syscall.
     *
     * <p>Sono una fotografia del momento della scansione — è esattamente ciò che il
     * chooser mostra, e viene rinfrescata a ogni {@code rescanCurrentDirectory()}.
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

    /** Elenca una cartella in una sola passata, attributi compresi. */
    private static final class Scanner extends FileSystemView {

        /** Per tutto ciò che non è l'elenco dei file vale il comportamento di sistema. */
        private final FileSystemView system = FileSystemView.getFileSystemView();

        @Override
        public File[] getFiles(File dir, boolean useFileHiding) {
            Path root = dir.toPath();
            List<File> out = new ArrayList<>(1024);
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 1,
                        new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) {
                        // La cartella di partenza non è un suo stesso figlio; per le
                        // sottocartelle basta la voce, non il contenuto.
                        if (p.equals(root)) return FileVisitResult.CONTINUE;
                        add(out, dir, p, a, useFileHiding);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                        // Arrivate al limite di profondità anche le sottocartelle
                        // passano di qui, a seconda della versione del JDK.
                        add(out, dir, p, a, useFileHiding);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path p, IOException e) {
                        return FileVisitResult.CONTINUE;   // voce illeggibile: si salta
                    }
                });
            } catch (IOException | RuntimeException e) {
                // Cartella sparita, permessi, percorso non valido: elenco vuoto, come
                // fa il chooser di serie.
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
            // Solo le nostre voci saltano la shell: le scorciatoie della barra laterale
            // devono restare "Questo PC", "Rete"… e quelle il nome ce l'hanno solo lì.
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
     * Evita alla shell la domanda «che icona e che nome ha questo file», che altrimenti
     * arriverebbe una volta per riga disegnata.
     */
    private static final class FlatView extends FileView {

        private final Icon fileIcon = UIManager.getIcon("FileView.fileIcon");
        private final Icon directoryIcon = UIManager.getIcon("FileView.directoryIcon");

        // null = "non lo so", e il chooser ricade sul comportamento di sistema: è quello
        // che serve alle voci della barra laterale, che non sono ScannedFile.

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
