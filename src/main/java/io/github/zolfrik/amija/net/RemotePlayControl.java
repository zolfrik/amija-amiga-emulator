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

package io.github.zolfrik.amija.net;

/**
 * Quel che il popup F12 puo' fare al gioco a distanza
 * ({@code docs/remote-play-plan.md} §9).
 *
 * <h2>Il dialog comanda, non possiede</h2>
 * Server e sessione vivono in {@code Amija} e <b>sopravvivono alla chiusura del popup</b>:
 * il dialog li accende, li spegne e ne legge lo stato. Se li possedesse, chiudere la
 * finestra chiuderebbe la partita — e il popup, essendo modale, e' anche la cosa che l'host
 * apre e chiude di continuo.
 *
 * <h2>Collegarsi si', smontare la macchina no</h2>
 * {@link #startHosting()} e {@link #stopHosting()} agiscono <b>subito</b>, col popup ancora
 * aperto: ospitare non tocca la macchina, che continua a esistere e a girare.
 *
 * <p>Anche {@link #requestJoin} agisce subito — apre davvero il collegamento — ma quel che
 * <b>non</b> fa e' smontare la macchina: un ospite non emula niente (§3), e smontare una
 * macchina da dentro un dialog modale, sull'EDT, mentre il thread di emulazione gira, e' il
 * modo di scoprire una serie di guasti intermittenti. Percio' la sessione si apre col popup
 * ancora aperto, il video comincia ad arrivare, e la macchina locale viene smontata solo
 * quando l'utente <b>conferma con OK</b>: quella transizione la fa chi possiede la macchina,
 * dove la fa gia' per il riavvio a freddo.
 *
 * <p>Il perche' di questa forma sta in un difetto vero del flusso precedente: il pulsante
 * chiudeva il popup e il collegamento avveniva dopo, cosicche' <b>un fallimento non aveva
 * piu' nessuna finestra in cui essere raccontato</b>. Ora il tentativo si vede in
 * {@link #status()} mentre e' in corso, e il suo esito resta li' anche dopo.
 */
public interface RemotePlayControl {

    /** Vero se questa macchina sta servendo (o aspettando) un ospite. */
    boolean isHosting();

    /** Vero se un ospite e' collegato adesso. */
    boolean hasGuest();

    /** Vero se c'e' una sessione aperta verso un host: questa macchina ne e' l'ospite. */
    boolean isGuest();

    /**
     * La game port coinvolta nel gioco a distanza (0 o 1), o {@code -1} se non c'e' nessun
     * ruolo attivo.
     *
     * <p>Chi la sta riempiendo lo dicono {@link #isHosting()} e {@link #isGuest()}, e il
     * senso cambia di conseguenza: <b>ospitando</b> e' la porta che si e' ceduta, e la sua
     * tendina nel popup va spenta perche' a riempirla e' l'altro; da <b>ospite</b> e' l'unica
     * porta che si puo' riempire, e sono le <i>altre</i> tendine a non voler dire niente.
     *
     * <p>Due domande, una risposta sola, ed e' voluto: l'assegnazione e' <b>una</b> — l'host
     * la decide, l'ospite la riceve nel WELCOME — e due metodi separati sarebbero due
     * verita' che possono divergere.
     */
    int remoteGamePort();

    /**
     * Vero mentre un tentativo di collegamento e' <b>in volo</b>.
     *
     * <p>L'handshake puo' metterci secondi (connessione TCP, PBKDF2 a 120k iterazioni, e
     * fino a {@code Handshake.HANDSHAKE_TIMEOUT_MS} di attesa su ogni lettura), quindi non
     * puo' avvenire sull'EDT: il popup si pianterebbe proprio nel momento in cui deve dire
     * cosa sta succedendo. Questo e' quel che il popup guarda per disabilitare il pulsante
     * e mostrare «connessione in corso».
     */
    boolean isJoining();

    /** Il codice da comunicare all'altro giocatore, o {@code null} se non si ospita. */
    String joinCode();

    /** Indirizzo da dare a chi sta sulla stessa rete, es. {@code 192.168.1.20:7788}. */
    String lanAddress();

    /**
     * Indirizzo pubblico, o {@code null} se non e' noto <b>o non e' utilizzabile</b>.
     *
     * <p>Lo dice il router via UPnP (§7.1). Finche' non ha risposto, o se ha risposto con un
     * indirizzo dietro il NAT dell'operatore, la riga non si mostra: meglio nessuna
     * informazione che un indirizzo a cui l'altro giocatore non arriverebbe, perche' un
     * indirizzo sbagliato manda a cercare il problema dove non c'e'.
     */
    String internetAddress();

    /**
     * Come e' andata l'apertura della porta sul router, in una riga leggibile, oppure
     * {@code null} se non c'e' niente da dire.
     *
     * <p>E' separata da {@link #status()} perche' risponde a un'altra domanda: quella dice
     * se c'e' qualcuno collegato, questa se qualcuno <b>potrebbe</b> collegarsi da fuori. E
     * soprattutto perche' la diagnosi piu' importante — il CGNAT del §7.3 — e' una cattiva
     * notizia che arriva quando ancora non e' successo niente, e va detta li', non tenuta
     * per il momento in cui un «Collega» dall'altra parte andra' in timeout.
     */
    String routerStatus();

    /** Una riga di stato leggibile: collegato, RTT, banda, perdite. */
    String status();

    /**
     * Comincia a ospitare, subito.
     *
     * @return {@code null} se e' partito, altrimenti il messaggio da mostrare all'utente
     */
    String startHosting();

    /** Smette di ospitare, subito. Un ospite collegato viene salutato e scollegato. */
    void stopHosting();

    /**
     * Comincia a collegarsi a un host, <b>in sottofondo</b>: ritorna subito, e il popup
     * resta aperto a raccontare come va.
     *
     * <p>L'esito arriva per due strade, e servono tutt'e due: {@link #status()} lo dice
     * mentre l'utente guarda, e un fallimento fa comparire anche un messaggio d'errore —
     * perche' l'utente potrebbe aver premuto e distolto lo sguardo, e una riga di stato che
     * nessuno guarda non ha avvisato nessuno.
     *
     * @return {@code null} se il tentativo e' partito, altrimenti cosa c'e' che non va nei
     *         parametri (indirizzo vuoto, codice malformato): quello si sa subito e non
     *         vale la pena aprire un socket per scoprirlo
     */
    String requestJoin(String address, String joinCode);

    /**
     * Lascia la sessione.
     *
     * @return {@code true} se per tornare a essere una macchina serve <b>chiudere il
     *         popup</b>: questa macchina e' un ospite a tutti gli effetti e non ha piu'
     *         niente da emulare, quindi va ricostruita, e ricostruirla da dentro un dialog
     *         modale e' proprio cio' che si evita. {@code false} se invece la sessione era
     *         stata aperta dal popup e la macchina locale non e' mai stata smontata: li'
     *         non c'e' niente da ricostruire e il popup puo' restare aperto.
     */
    boolean requestLeave();
}
