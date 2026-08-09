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

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.config.AmigaConfig;

import java.nio.file.Path;
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
 * <p>Gli SHA-256 sono stati verificati a mano il 2026-08-01 scaricando gli
 * archivi. Sono indicativi: gli URL non sono versionati (puntano sempre all'ultimo
 * rilascio), quindi un aggiornamento a monte li cambierà legittimamente — vedi
 * {@link AssetPackage#sha256()}.
 *
 * <p>I campioni audio del drive stavano qui fino al 2026-08-07, quando AmiJa ha
 * cominciato a portarsi i propri: ora {@code floppysounds/} è pieno all'installazione
 * e non c'è più niente da scaricare.
 */
public final class AssetCatalog {

    /** Radice delle risorse WHDLoad, la stessa che legge {@code WhdLoadManager}. */
    private static final Path WHDLOAD_FILES = AppHome.resolve("whdload", "WHDLoad_files");

    private AssetCatalog() {}

    /**
     * I pacchetti offerti all'utente, nell'ordine in cui vanno mostrati.
     *
     * @param config configurazione da cui leggere eventuali override degli URL
     *               (chiave {@code assets.<id>.url}); può essere {@code null}
     */
    public static List<AssetPackage> packages(AmigaConfig config) {
        return List.of(whdload(config), whdloadRtb(config));
    }

    /**
     * I pacchetti <b>definiti ma non offerti</b>: i due del Mega Bezel, nascosti
     * dall'assistente dal 2026-08-09.
     *
     * <p>Non è una questione di licenze — quelle sono a posto e restano registrate
     * in {@code THIRD-PARTY-NOTICES.md} §2.1 — ma di collaudo: la catena slang del
     * Mega Bezel non è ancora stata verificata abbastanza da poterla proporre, e
     * un pacchetto che si scarica (fino a 347 MB) per poi non funzionare è peggio
     * di un pacchetto assente. Le definizioni restano intatte e vive proprio per
     * questo: per riaprirle basta rimetterle nella lista di {@link #packages},
     * senza ricostruire nulla. Chi vuole provarle intanto può installarle a mano
     * nelle stesse cartelle di destinazione descritte qui sotto.
     *
     * @param config come in {@link #packages(AmigaConfig)}
     */
    public static List<AssetPackage> hiddenPackages(AmigaConfig config) {
        return List.of(megaBezel(config), megaBezelCommodore(config));
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
                true,
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
                true,
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
    // Il Mega Bezel di HyperspaceMadness: la catena shader slang che disegna un
    // monitor attorno all'immagine, con cornice generata, riflessi e maschera
    // fosfori. NASCOSTO all'utente — vedi hiddenPackages(). È GPLv3 —
    // ridistribuibile, quindi qui la scelta di scaricarlo invece di versionarlo non
    // è giuridica ma di peso: sono 14,7 MB di cui 12 di sole texture, e chi non usa
    // gli shader non ne ha bisogno.
    //
    // Lo zip ha una cartella radice col numero di versione dentro; il mapper la
    // toglie, così l'albero finisce in shaders/shaders_slang/bezel/Mega_Bezel/ —
    // dove i suoi #include che escono dal pacchetto (../../../../include/,
    // ../../../../blurs/) trovano i file comuni libretro che AmiJa porta con sé.
    // -------------------------------------------------------------------------
    private static AssetPackage megaBezel(AmigaConfig config) {
        final String version = "Mega_Bezel_V1.17.1_2024-04-14";
        Path dest = AppHome.resolve("shaders", "shaders_slang", "bezel");
        return new AssetPackage(
                "megabezel",
                url(config, "megabezel",
                        "https://github.com/HyperspaceMadness/Mega_Bezel/releases/download/"
                        + version + "/" + version + ".zip"),
                null,
                14_730_259L,
                "HyperspaceMadness, GPL-3.0-or-later",
                false,
                AssetPackage.Format.ZIP,
                dest,
                dest.resolve("Mega_Bezel").resolve("Presets").resolve("MBZ__5__POTATO.slangp"),
                List.of(version + "/Mega_Bezel/Presets/MBZ__5__POTATO.slangp"),
                entry -> leafUnder(entry, version + "/", true));
    }

    // -------------------------------------------------------------------------
    // Il pacchetto Commodore di TheNamec: cornici fotorealistiche in 4K per Amiga
    // 500/500+/600/1200/CD32/CDTV e per i monitor Commodore, da montare sopra il
    // Mega Bezel che AmiJa installa in shaders/shaders_slang/bezel/.
    // NASCOSTO all'utente insieme al Mega Bezel — vedi hiddenPackages().
    //
    // È l'unica voce del catalogo che AmiJa non potrebbe ridistribuire nemmeno
    // volendo: è CC BY-NC-ND 4.0, e le clausole NonCommercial e NoDerivatives sono
    // incompatibili con la GPL-3.0-or-later del progetto. Da qui due conseguenze
    // che NON vanno "ottimizzate" via:
    //   - si scompatta VERBATIM (mapper identità). Gli altri pacchetti filtrano le
    //     voci inutili; qui filtrare sarebbe produrre un'opera derivata.
    //   - la destinazione è Mega_Bezel_Packs/thenamec-commodore-pack, e il nome
    //     della cartella è vincolato: i preset del pacchetto risalgono di sette
    //     livelli per ritrovare shaders_slang/bezel/Mega_Bezel, quindi la radice
    //     dev'essere esattamente due cartelle sotto shaders/.
    //
    // Lo sha256 non è fissato: l'archivio è di 347 MB e non è stato scaricato per
    // calcolarlo. L'URL però è ancorato al tag RC4.1, quindi il contenuto non
    // cambia sotto i piedi, e l'integrità la verifica requiredEntries.
    // -------------------------------------------------------------------------
    private static AssetPackage megaBezelCommodore(AmigaConfig config) {
        Path dest = AppHome.resolve("shaders", "Mega_Bezel_Packs", "thenamec-commodore-pack");
        return new AssetPackage(
                "megabezelcommodore",
                url(config, "megabezelcommodore",
                        "https://github.com/TheNamec/megabezel-commodore-pack/releases/download"
                        + "/RC4.1/thenamec-commodore-pack-RC4.1_4K.zip"),
                null,
                347_570_318L,
                "TheNamec, CC BY-NC-ND 4.0",
                false,
                AssetPackage.Format.ZIP,
                dest,
                dest.resolve("presets").resolve("Commodore_Amiga500")
                    .resolve("Bezel_Beige-POTATO").resolve("MBZ_SHARP_POTATO")
                    .resolve("OVERLAY_CURVED_DAY.slangp"),
                List.of("presets/Commodore_Amiga500/",
                        "res/thenamec/shaders/base/mbz_5_potato_gdv/preset.slangp"),
                entry -> entry);
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
