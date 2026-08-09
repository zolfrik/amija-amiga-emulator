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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Descrizione di un pacchetto di terze parti che l'assistente di download può
 * prelevare <b>per conto dell'utente</b>.
 *
 * <p>AmiJa non ridistribuisce nulla: il file viaggia dal server del titolare dei
 * diritti al disco dell'utente, esattamente come se questi cliccasse il link in un
 * browser. Ogni pacchetto resta soggetto alla propria licenza; provenienza e
 * titolare sono registrati in {@code THIRD-PARTY-NOTICES.md} §5, che fissa anche
 * le sei regole a cui ogni nuova voce del manifest deve attenersi (fonte ufficiale,
 * nessun aggiramento di registrazioni o paywall, mai ROM o immagini disco).
 *
 * @param id             identificatore stabile; da esso derivano le chiavi i18n
 *                       {@code assets.<id>.name} e {@code assets.<id>.desc}
 * @param url            URL di download presso la fonte ufficiale
 * @param sha256         impronta del file verificata a mano, in esadecimale minuscolo.
 *                       È <b>indicativa</b>: gli URL non sono versionati, quindi un
 *                       rilascio a monte la cambierà legittimamente. Una difformità
 *                       va segnalata, non trattata come errore fatale.
 * @param approxBytes    dimensione attesa, usata per la barra di avanzamento quando
 *                       il server non manda {@code Content-Length}
 * @param rightsHolder   titolare dei diritti, mostrato all'utente prima del download
 * @param preselected    se la casella parte spuntata quando il pacchetto non è ancora
 *                       installato. Vale per ciò che serve a far girare i giochi; i
 *                       pacchetti grossi e facoltativi — gli shader — partono spenti,
 *                       perché "Scarica" non deve mai avviare centinaia di MB che
 *                       l'utente non ha chiesto
 * @param format         formato dell'archivio
 * @param destDir        cartella di destinazione, relativa alla working dir
 * @param probe          file la cui presenza indica che il pacchetto è già installato
 * @param requiredEntries voci che l'archivio deve contenere; se mancano, il file
 *                       scaricato non è quello atteso e l'installazione si ferma
 * @param mapper         mappa una voce dell'archivio sul percorso di destinazione
 *                       (relativo a {@code destDir}), oppure {@code null} per ignorarla
 */
public record AssetPackage(
        String id,
        String url,
        String sha256,
        long approxBytes,
        String rightsHolder,
        boolean preselected,
        Format format,
        Path destDir,
        Path probe,
        List<String> requiredEntries,
        UnaryOperator<String> mapper) {

    /** Formato dell'archivio scaricato. */
    public enum Format { ZIP, LHA }

    public AssetPackage {
        requiredEntries = List.copyOf(requiredEntries);
    }

    /** Nome leggibile e localizzato del pacchetto. */
    public String displayName() {
        return Messages.t("assets." + id + ".name");
    }

    /** Descrizione localizzata: a cosa serve il pacchetto. */
    public String description() {
        return Messages.t("assets." + id + ".desc");
    }

    /** {@code true} se il pacchetto risulta già installato nella working dir. */
    public boolean installed() {
        return Files.isRegularFile(probe);
    }

    /**
     * Percorso di destinazione (relativo a {@link #destDir()}) per una voce
     * dell'archivio, oppure {@code null} se la voce va ignorata.
     */
    public String map(String entry) {
        return mapper.apply(entry);
    }
}
