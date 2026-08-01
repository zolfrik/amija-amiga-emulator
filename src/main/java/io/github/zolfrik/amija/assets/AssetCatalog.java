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

import io.github.zolfrik.amija.config.AmigaConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Manifest dell'assistente di download: l'elenco dei pacchetti di terze parti che
 * AmiJa sa procurare, con la fonte ufficiale di ciascuno.
 *
 * <p>Nessuno di questi file è ridistribuito da AmiJa e nessuno finisce nel jar:
 * l'assistente li preleva dal sito del titolare dei diritti su richiesta esplicita
 * dell'utente. Il razionale giuridico, i verdetti di licenza e le regole per
 * aggiungere una voce stanno in {@code THIRD-PARTY-NOTICES.md} §4 e §5.
 *
 * <p><b>Mai</b> aggiungere qui ROM Kickstart o immagini disco: restano a carico
 * dell'utente e nessun automatismo può procurarle.
 *
 * <p>Gli SHA-256 sono stati verificati a mano il 2026-08-01 scaricando i tre
 * archivi. Sono indicativi: gli URL non sono versionati (puntano sempre all'ultimo
 * rilascio), quindi un aggiornamento a monte li cambierà legittimamente — vedi
 * {@link AssetPackage#sha256()}.
 */
public final class AssetCatalog {

    /** Radice delle risorse WHDLoad, la stessa che legge {@code WhdLoadManager}. */
    private static final Path WHDLOAD_FILES = Paths.get("whdload", "WHDLoad_files");
    /** Cartella dei campioni audio del drive, la stessa che legge {@code FloppySound}. */
    private static final Path FLOPPY_SOUNDS = Paths.get("floppysounds");

    private AssetCatalog() {}

    /**
     * I pacchetti disponibili, nell'ordine in cui vanno mostrati.
     *
     * @param config configurazione da cui leggere eventuali override degli URL
     *               (chiave {@code assets.<id>.url}); può essere {@code null}
     */
    public static List<AssetPackage> packages(AmigaConfig config) {
        return List.of(floppySounds(config), whdload(config), whdloadRtb(config));
    }

    // -------------------------------------------------------------------------
    // I suoni del meccanismo del drive, dal plugin "drive sounds" di WinUAE.
    // Licenza non dichiarata: non ridistribuibili, ma scaricabili dall'utente.
    // -------------------------------------------------------------------------
    private static AssetPackage floppySounds(AmigaConfig config) {
        final String prefix = "plugins/floppysounds/";
        return new AssetPackage(
                "floppysounds",
                url(config, "floppysounds", "https://download.abime.net/winuae/stuff/drive_sounds.zip"),
                "2efbe66d090c50f5b58e1248a1cab5c1886c7d203bd2f1d26d1ec382b3ffb088",
                10_448_349L,
                "WinUAE drive sounds plugin (Toni Wilen)",
                AssetPackage.Format.ZIP,
                FLOPPY_SOUNDS,
                FLOPPY_SOUNDS.resolve("drive_click_A500.wav"),
                List.of(prefix + "drive_click_A500.wav"),
                entry -> {
                    String leaf = leafUnder(entry, prefix);
                    return (leaf != null && leaf.toLowerCase(Locale.ROOT).endsWith(".wav")) ? leaf : null;
                });
    }

    // -------------------------------------------------------------------------
    // WHDLoad. L'archivio ufficiale contiene C/, S/, Docs/ e l'Install script;
    // teniamo solo C/ e S/ — Docs/ sono oltre 500 file di documentazione HTML,
    // inutili dentro l'hard disk generato, e le icone .info non servono a nulla
    // in un avvio non presidiato. NB: la S/WHDLoad.prefs qui installata viene
    // comunque riscritta da WhdLoadManager con quella generata dalla config.
    // -------------------------------------------------------------------------
    private static AssetPackage whdload(AmigaConfig config) {
        return new AssetPackage(
                "whdload",
                url(config, "whdload", "https://whdload.de/whdload/WHDLoad_usr.lha"),
                "093333953737528d79c1eda7d21a16a0aa298698722624e7cfb31f588a0a156d",
                1_499_481L,
                "WHDLoad, © 1995-2026 Bert Jahn",
                AssetPackage.Format.LHA,
                WHDLOAD_FILES,
                WHDLOAD_FILES.resolve("C").resolve("WHDLoad"),
                List.of("WHDLoad/C/WHDLoad"),
                entry -> {
                    String rel = leafUnder(entry, "WHDLoad/", true);
                    if (rel == null) return null;
                    if (!rel.startsWith("C/") && !rel.startsWith("S/")) return null;
                    return rel.toLowerCase(Locale.ROOT).endsWith(".info") ? null : rel;
                });
    }

    // -------------------------------------------------------------------------
    // Le tabelle di rilocazione .RTB, richieste dagli install che usano il
    // kickemu. NON fanno parte di WHDLoad: vengono da SoftKick, che la doc di
    // WHDLoad (Docs/en/need.html) indica come fonte. L'archivio è ridistribuibile
    // solo intatto — infatti lo scarichiamo intatto, ed è l'utente a scompattarlo
    // sulla propria macchina.
    // -------------------------------------------------------------------------
    private static AssetPackage whdloadRtb(AmigaConfig config) {
        final String prefix = "Kickstarts/";
        Path dest = WHDLOAD_FILES.resolve("Devs").resolve("Kickstarts");
        return new AssetPackage(
                "whdloadrtb",
                url(config, "whdloadrtb", "https://aminet.net/util/boot/skick346.lha"),
                "02b4d01852d12ab391c6469064f917221a0f7319fd0b3ba6c359403ec1d59f96",
                96_878L,
                "SoftKick, © SinSoft / Pavel Troller",
                AssetPackage.Format.LHA,
                dest,
                dest.resolve("kick34005.A500.RTB"),
                List.of(prefix + "kick34005.A500.RTB"),
                entry -> {
                    String leaf = leafUnder(entry, prefix);
                    return (leaf != null && leaf.endsWith(".RTB")) ? leaf : null;
                });
    }

    // -------------------------------------------------------------------------

    /** URL del pacchetto: quello di default, salvo override in {@code amiga.properties}. */
    private static String url(AmigaConfig config, String id, String fallback) {
        return config == null ? fallback : config.assetUrl(id, fallback);
    }

    /** Come {@link #leafUnder(String, String, boolean)}, ma solo per voci non annidate. */
    private static String leafUnder(String entry, String prefix) {
        return leafUnder(entry, prefix, false);
    }

    /**
     * Porzione di {@code entry} che segue {@code prefix}, oppure {@code null} se la
     * voce non sta sotto quel prefisso (o è la directory stessa). Con
     * {@code nested=false} scarta anche le voci in sottocartelle.
     */
    private static String leafUnder(String entry, String prefix, boolean nested) {
        if (!entry.startsWith(prefix)) return null;
        String rest = entry.substring(prefix.length());
        if (rest.isEmpty()) return null;
        return (nested || rest.indexOf('/') < 0) ? rest : null;
    }
}
