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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.input.GamePort;
import java.util.Arrays;

/**
 * Denise (8362 OCS / 8373 ECS) — Display Encoder dell'Amiga 500.
 *
 * Architettura line-buffered allineata a vAmiga:
 *   fillShiftRegisters → translate → drawSprites → checkCollisions → colorize
 *
 * Correzioni rispetto alla versione precedente:
 *  - zPF: mappatura priorità corretta per HW Amiga
 *  - translateSPF: usa i bit PF2P (bplcon2 bit 3-5) come l'HW Amiga
 *  - VBlank: le scanline in area VBlank vengono riempite con palette[0]
 *  - checkS2SCollisions: rimosso parametro inutilizzato
 *  - Sprite DATA/DATB: range esteso fino a 0x17E
 *  - Supporto ECS: isECS, deniseId, setDeniseId(), isECS()
 *  - readRegister 0x07C: DENISEID corretto per OCS/ECS
 *  - Aggiunto triggerLightPen()
 *  - Ripristinati bplcon3 e bplcon4 (necessari per ECS)
 *  - clxdat read: mascherato con 0x7FFF prima dell'OR con 0x8000
 *  - Corretta identificazione sotto-registro sprite in drawSpritePair
 *
 * Correzioni hardware-accuracy di questa versione:
 *
 *  FIX 1 — isEHB: il bit 7 di BPLCON0 è GAUD (genlock audio enable), NON
 *    un flag EHB. Extra Half-Brite si attiva automaticamente quando BPU=6
 *    e HAM=0, senza ulteriori condizioni su OCS/ECS.
 *    (Rif: HW Ref Manual §7, tabella BPLCON0)
 *
 *  FIX 2 — fillShiftRegisters: BPLCON1 non veniva applicato. I campi
 *    PF1H (bit 3:0) e PF2H (bit 7:4) definiscono lo scroll orizzontale
 *    rispettivamente per i piani dispari (BPL1/3/5) e i piani pari
 *    (BPL2/4/6). In SPF i piani pari usano PF1H. L'offset va in pixel
 *    HPIXELS (pixScale=2 in lores, 1 in hires). Usato |= su dBuffer
 *    per permettere la sovrapposizione corretta tra fetch DMA adiacenti.
 *    (Rif: HW Ref Manual §7, BPLCON1)
 *
 *  FIX 3 — drawAttachedSpritePixel: la base della palette era sempre 16,
 *    indipendentemente dalla coppia sprite. Le coppie 1/2/3 usavano
 *    palette[16..19] anziché [20..23], [24..27], [28..31].
 *    Formula corretta: base = 16 + (x & 6) * 2, dove x è lo sprite dispari.
 *    (Rif: HW Ref Manual §7, tabella colori sprite attached)
 *
 *  FIX 4 — drawAttachedSpritePixel: la profondità Z usata era Z_SP[x]
 *    (sprite dispari, priorità inferiore). Per gli sprite attached la
 *    priorità è quella dello sprite pari (il "leader" della coppia).
 *    Formula corretta: Z_SP[x & ~1].
 *    (Rif: HW Ref Manual §7, "Sprite Priority")
 *
 *  FIX 5 — colorizeHAM: la maschera 0x5555 era errata. Intercettava Z_1
 *    (un livello Z del playfield!) e mancava Z_SP1, Z_SP2, Z_SP5, Z_SP6.
 *    Sostituita con Z_ALL_SPRITES. Aggiunto check priorità corretto: uno
 *    sprite interrompe HAM solo se la sua Z è maggiore di quella del
 *    playfield corrente (zpf calcolato da initialBplcon2).
 *    (Rif: HW Ref Manual §7, "HAM and Sprites")
 *
 *  FIX 6 — colorizeHAM, area bordo: il registro hold veniva aggiornato
 *    con il colore del bordo durante l'area fuori-DIW. Sul vero hardware
 *    il registro HAM NON viene resettato nel bordo; persiste l'ultimo
 *    valore accumulato nell'area attiva.
 *    (Rif: HW Ref Manual §7, "Hold-And-Modify Mode")
 *
 *  FIX 7 — checkS2PCollisions: le maschere CLXCON per PF2 erano errate.
 *    "enabled2 = (clxcon >> 7) & 0x15" non estrae correttamente i bit
 *    enable dei piani pari. Corretto in "(clxcon >> 6) & 0x2A" (bit 1,3,5
 *    dei 6 enable bit = piani pari BPL2/4/6).
 *    "match2 = (clxcon >> 1) & 0x15" idem per i match value.
 *    Corretto in "clxcon & 0x2A".
 *    (Rif: HW Ref Manual §7, CLXCON)
 *
 *  FIX 8 — checkP2PCollisions: stesse maschere errate di FIX 7.
 *
 *  FIX 9 — hsyncHandler VBlank: drawSprites() veniva chiamato durante il
 *    VBlank, lasciando i sprChanges[i] sporchi per la scanline successiva.
 *    In VBlank i recorder vengono solo puliti, non consumati.
 *
 *  FIX 10 — readRegister, BPLCON0 ($DFF100): su OCS è write-only.
 *    Restituire il valore ombra ($0102 dopo l'init grafico a $FCAFBA)
 *    causava il Kickstart 1.3 a calcolare BPLCON0=$1302 invece di $1200,
 *    perché il codice a $FCAF42 esegue: D0=BPLCON0; D0|=$1200; BPLCON0=D0.
 *    Con shadow=$0102 → $0102|$1200=$1302 (schermata corrotta, barra nera).
 *    Con 0 (write-only, corretto per OCS) → $0000|$1200=$1200 (corretto).
 *    Stesso fix applicato a BPLCON1/2 (write-only su OCS) e ai registri
 *    ECS-only BPLCON3/4 (inesistenti su OCS, restituiscono 0).
 *    (Rif: HW Ref Manual §7, BPLCON0; chip OCS Denise 8362)
 *
 *  FIX 11 — readRegister, DENASEN ($DFF07C): per OCS si restituiva
 *    agnus.getLastBusData(), che poteva valere $0102 (dall'ultima scrittura
 *    su BPLCON0), inquinando il rilevamento chipset del Kickstart.
 *    Su OCS Denise 8362 il registro non è implementato; il bus è tenuto
 *    alto dai resistori di pull-up → $FFFF. Restituito $FFFF per OCS,
 *    $FFFC per ECS Denise 8373 (valore identificativo standard).
 *    (Rif: Amiga Hardware Reference Manual, DENASEN; chip OCS Denise 8362)
 *
 * @author alessio.carotenuto
 */
public abstract class AbstractDenise {

    // =========================================================================
    // Costanti architetturali (allineamento vAmiga, PAL)
    // =========================================================================

    /**
     * Larghezza del line-buffer in pixel "massima risoluzione" (4 sub-pixel per CCK).
     * Copre tutta la linea incluso il blanking.
     */
    public static final int RAW_LINE_PIXELS = 912;   // 4 * 228
    private static final int HPIXELS    = RAW_LINE_PIXELS;
    private static final int HBLANK_MIN = 0x12;

    // =========================================================================
    // Inner class: RegChange e RegChangeRecorder
    // =========================================================================

    private static final class RegChange {
        int reg;
        int value;
    }

    /**
     * Registra i cambi di registro avvenuti durante la scanline corrente,
     * con il timestamp in pixel. Permette di applicarli nell'ordine corretto
     * durante la fase di colorize (fondamentale per gli effetti Copper mid-line).
     */
    private static final class RegChangeRecorder {
        final int[]       keys;
        final RegChange[] elements;
        int               size = 0;

        RegChangeRecorder(int capacity) {
            keys     = new int[capacity];
            elements = new RegChange[capacity];
            for (int i = 0; i < capacity; i++) elements[i] = new RegChange();
        }

        void insert(int key, int reg, int value) {
            if (size < keys.length) {
                keys[size]           = key;
                elements[size].reg   = reg;
                elements[size].value = value;
                size++;
            }
            // Drop silenzioso a overflow: aumentare la capacity se necessario.
        }

        void    clear()   { size = 0; }
        boolean isEmpty() { return size == 0; }
        int     size()    { return size; }
    }

    // =========================================================================
    // Dipendenze
    // =========================================================================

    private AbstractAgnus        agnus;
    private int          field;   // 0 o 1 per interlace

    // =========================================================================
    // Chip ID / ECS
    // =========================================================================

    /** 0xFFFF = OCS (default), 0xFFFC = ECS */
    //private int     deniseId = 0xFFFF;
    // STEP 14/P-13 (PERFORMANCE_REPORT_FINAL): isECS è "effectively final" — viene
    // impostato solo da setDeniseId() in fase di setup, prima che l'emulazione parta.
    // NON può essere dichiarato 'final' (setDeniseId è un setter pubblico runtime),
    // quindi il constant-folding JIT non è applicabile: nessuna modifica funzionale.
    private boolean isECS    = false;

    public void setDeniseId(int id) {
        //this.deniseId = id;
        this.isECS    = (id != 0xFFFF);
    }

    public boolean isECS() { return isECS; }

    // =========================================================================
    // Registri di controllo
    // =========================================================================

    private int bplcon0, bplcon1, bplcon2, bplcon3, bplcon4;

    // Campi derivati da bplcon0, ricalcolati ad ogni scrittura di bplcon0 (vedi
    // updateBplcon0Derived). Evitano di rifare Math.min(...) / shift ad ogni
    // pixel nei percorsi di decodifica bitplane (hot path). Valore identico a
    // ricalcolarli al volo: dipendono SOLO da bplcon0.
    private int     bpu   = 0;            // piani DISEGNATI da Denise: vedi deniseBpu()
    private boolean hires = false;        // (bplcon0 & 0x8000) != 0

    /**
     * Snapshot dei registri all'inizio della scanline corrente.
     * Usati da translate/colorize per garantire la coerenza nel caso in cui
     * il Copper modifichi i registri a metà riga.
     */
    // ⚠️ initialBplcon1 NON esiste piu': lo scroll fine si legge vivo, vedi
    // drawBitplanesAtPixel. BPLCON0 e BPLCON2 restano invece snapshot di inizio riga.
    private int initialBplcon0, initialBplcon2;
    private int lastRenderedBplcon0;

    private int     hstrt,   hstop;
    /**
     * ⛔ <b>Lo stato di HSTRT/HSTOP all'INIZIO della riga, e senza di esso una scrittura a
     * metà riga si applica all'indietro.</b> {@link #updateBorderBuffer()} gira a fine riga,
     * quando {@link #hstrt}/{@link #hstop} portano già il valore <b>nuovo</b>: partendo da
     * quelli, i pixel <i>prima</i> della scrittura venivano mascherati con la coordinata che
     * ancora non era stata scritta, e {@link #diwChanges} — che la reinserisce al suo pixel —
     * non poteva accorgersene, perché reimpostava un valore già in uso.
     *
     * <p>Il difetto è invisibile finché la finestra non si muove dentro la riga, ed è il
     * banco §17 {@code Denise/DIW} a renderlo osservabile: {@code diwtim.i} riscrive DIWSTOP
     * due volte per riga alternando due coordinate a 32 pixel lores di distanza, e su ogni
     * riga in cui la scrittura cadeva <b>dopo</b> che il contatore aveva già superato
     * entrambe usavamo la nuova dove l'oracolo tiene la vecchia. Su {@code diwtim1} la
     * finestra chiudeva a 571 invece che a 507: 64 pixel di crop, cioè esattamente
     * {@code $80 − $60}.
     *
     * <p>⚠️ È il gemello rovesciato di {@code initialBplcon1} (§16 classe F): là leggevamo
     * uno snapshot dove l'oracolo legge vivo, qui leggevamo vivo dove serve lo snapshot. La
     * discriminante è <b>chi consuma il valore</b>: lo scroll fine entra nel cursore
     * d'uscita mentre la riga scorre, la maschera DIW viene ricostruita tutta insieme a fine
     * riga e ha quindi bisogno del proprio stato iniziale.
     */
    private int     hstrtLineStart, hstopLineStart;
    private boolean hflop;
    private int     borderBufferIsDirty;
    private int     borderColor;
    /**
     * True quando la scanline corrente è una riga di bordo verticale, cioè non ha
     * prodotto output bitplane (vedi {@link #bpl1datWrittenThisLine}). In quest'area
     * gli sprite NON sono visibili su OCS: la riga è interamente bordo (palette[0]),
     * come WinUAE (pfield_draw_line: gli sprite si disegnano solo con BRDSPRT/ECS).
     */
    private boolean lineIsVBorder;
    /**
     * True se sulla scanline corrente è stato scritto BPL1DAT (cioè la riga ha
     * prodotto output bitplane). Su OCS gli sprite sono visibili solo nelle righe
     * con output bitplane; nelle righe di bordo verticale (nessun bitplane, anche
     * se dentro la DIW) non vengono mostrati — come WinUAE (pfield_draw_line:
     * border = plfleft<0 → sprite disegnati solo con BRDSPRT/ECS).
     */
    private boolean bpl1datWrittenThisLine;

    private int clxcon,  clxdat;
    // PERF: maschera degli sprite che hanno disegnato pixel sulla scanline corrente
    // (azzerata in hsyncHandler). Usata per saltare i loop di collisione inutili.
    private int spritesOnLineMask;

    // Game port 1 (mouse) e port 2 (joystick) — opzionali
    private GamePort gamePort1 = null;
    private GamePort gamePort2 = null;

    // =========================================================================
    // Palette (12-bit Amiga: 0x0RGB)
    // =========================================================================

    private final int[] palette = new int[32];
    // Stato dei registri colore all'INIZIO della scanline corrente (= fine della
    // precedente). I write COLOR00..31 aggiornano palette[] immediatamente (per le
    // letture live su ECS), ma colorize() deve ricostruire ogni segmento a partire
    // dal colore EREDITATO dalla riga precedente e applicare le change ai loro
    // trigger. Senza questo snapshot il segmento PRIMA della prima change usava già
    // il valore POST-write (palette[] live), quindi una MOVE COLOR00 a metà riga
    // visibile (es. synccpu di vAmigaTS) colorava erroneamente tutta la parte
    // sinistra col colore nuovo. È l'analogo di initialBplcon0 per i colori, e
    // corrisponde al colreg[] persistente di vAmiga (PixelEngine).
    private final int[] paletteLineStart = new int[32];

    /**
     * Snapshot d'inizio riga dei registri sprite — l'esatto gemello di
     * {@link #paletteLineStart}, e per la stessa ragione.
     *
     * <p>⛔ {@link #drawSpritePair} rigioca i cambi di registro ai loro trigger in pixel,
     * ma partiva dai valori <b>vivi</b>, che a fine riga contengono già TUTTE le
     * scritture della riga: il segmento precedente alla prima change usava quindi lo
     * stato posteriore a tutte le change. Una MOVE a SPRxDATA a metà riga armava lo
     * sprite anche a sinistra del punto in cui è stato armato; una MOVE a SPRxCTL lo
     * disarmava anche a sinistra, dove doveva ancora disegnare; e la posizione
     * orizzontale di partenza (sprhppos, derivata da POS/CTL) e il bit di attach
     * (SPRxCTL bit 7 dello sprite dispari) erano quelli di fine riga per l'intero
     * chunk iniziale. È lo stesso difetto che paletteLineStart ha chiuso per i colori.
     *
     * <p>sprhppos non è nello snapshot perché è derivato: {@link #updateSprHCoords} lo
     * ricalcola da POS/CTL al ripristino.
     */
    private int       armedLineStart;
    private final int[] sprposLineStart  = new int[8];
    private final int[] sprctlLineStart  = new int[8];
    private final int[] sprdataLineStart = new int[8];
    private final int[] sprdatbLineStart = new int[8];
    public int dbgPalette(int i) { return palette[i & 31]; }

    // =========================================================================
    // Shift register bitplane
    // =========================================================================

    private final int[] bpldat     = new int[6];
    private final int[] bpldatPipe = new int[6];  // pipeline stage
    private final int[] shiftReg   = new int[6];
    private boolean     armedOdd, armedEven;

    /**
     * Stato dell'<b>emissione incrementale</b> degli shift register, un gruppo per
     * playfield (indice 0 = piani dispari, 1 = piani pari).
     *
     * <p>⛔ <b>Denise non disegna una word in blocco: la fa uscire un pixel alla volta, al
     * passo della risoluzione VIVA.</b> L'oracolo lo scrive come un contatore di shift
     * ({@code drawing.cpp}, {@code lts_unaligned_ecs}):
     * <pre>
     * bplshiftcnt[0] += denise_res_size;                    // 1 in lores, 2 in hires
     * if (bplshiftcnt[0] &gt;= 4) { bplshiftcnt[0] = 0; shiftbpl6(); }
     * </pre>
     * — e ha perfino un ramo dedicato alla transizione lores→hires
     * ({@code expand_bplcon0_early()}: {@code reswitch_unalign = 3}), che manda tre
     * mezze-CCK sul percorso lento pixel-esatto.
     *
     * <p>⚠️ <b>Che cosa cambia davvero</b>, e perché è un rifacimento e non una costante:
     * il vecchio modello scriveva i 16 pixel della word tutti insieme, al CCK di draw, con
     * un solo {@code pixScale = hires ? 1 : 2}. Un modello a blocchi <b>non può</b>
     * esprimere un cambio di passo a metà word: se BPLCON0 commuta mentre la word sta
     * uscendo, i bit rimasti devono uscire al passo nuovo — l'oracolo finisce la word in
     * metà tempo e poi resta nero, noi la facevamo durare tutta. È il banco
     * {@code Denise/Registers/BPLCON0/hilo}, e sono i 21 px che il §12 aveva rimandato a
     * questo banco per nome.
     *
     * <p>✅ <b>A risoluzione costante l'emissione incrementale è bit-identica al blocco</b>:
     * 16 bit × 2 sub-pixel = 32 entry = 8 CCK in lores, 16 entry = 4 CCK in hires, gli
     * stessi indici e gli stessi valori. È la proprietà che rende il rifacimento
     * verificabile — la rete deve restare ferma, e a muoversi dev'essere solo {@code hilo}.
     *
     * <p>⚠️ <b>Vivo è SOLO {@code hires}.</b> {@code bpu}, il dual-playfield e lo scroll
     * fine sono <b>latchati al caricamento</b>, esattamente come li congelava il blocco:
     * cambiarli insieme sarebbe cambiare due modelli in una volta, e a quel punto un rosso
     * non si sa più attribuire. Sono materia di altre classi dello stesso banco.
     */
    private final int[]  srCount  = new int[2];   // bit ancora da far uscire (0..16)
    private final int[]  srCursor = new int[2];   // prossimo indice di dBuffer da scrivere
    private final int[]  srPhase  = new int[2];   // sub-pixel già emessi del bit corrente
    private final int[]  srBpu    = new int[2];   // BPU latchato al caricamento
    private final byte[][] srSlice = new byte[2][16]; // slice precalcolati, uno per bit

    // =========================================================================
    // Sprite state
    // =========================================================================

    private final int[] sprdata  = new int[8];
    private final int[] sprdatb  = new int[8];
    private final int[] sprpos   = new int[8];
    private final int[] sprctl   = new int[8];
    private final int[] sprhppos = new int[8];  // posizione H precalcolata in pixel
    private final int[] ssra     = new int[8];  // shift register A per sprite
    private final int[] ssrb     = new int[8];  // shift register B per sprite
    private int armed;                 // bitmask degli sprite armati
    private int spriteClipBegin, spriteClipEnd;

    /**
     * ⛔ <b>BPL1DAT non arma solo i bitplane: SBLOCCA anche gli sprite.</b> Pixel in cui
     * BPL1DAT e' stato scritto la prima volta in questa riga, o {@link Integer#MAX_VALUE}
     * se non e' mai stato scritto (riga tutta bordo).
     *
     * <p>WinUAE lo modella come una maschera a <b>due bit</b>, {@code sprites_hidden}
     * (drawing.cpp): il bit 0 e' la finestra orizzontale (HDIW) e il bit <b>1</b> e'
     * proprio questo — {@code hstart_new()} lo alza a inizio riga e <b>solo</b>
     * {@code bpl1dat_enable_sprites()} lo abbassa, con il commento che lo dice per esteso:
     * <i>«BPL1DAT allows sprites 1 lores pixel before bitplanes»</i>. Lo sprite esce solo
     * se {@code !sprites_hidden}, cioe' se <b>entrambi</b> i bit sono a zero.
     *
     * <p>⚠️ Il bit 0 lo applicavamo gia' (la maschera DIW di {@code bBuffer} in
     * {@code colorize}); il bit 1 mancava, e sono i 14 rossi su 14 del gruppo
     * {@code clip} del banco Denise/Sprites.
     */
    private int bpl1datPixelThisLine = Integer.MAX_VALUE;

    /**
     * Lo scarto fra la scrittura di BPL1DAT e il pixel da cui lo sprite torna visibile,
     * in pixel raw (2 per CCK-mezza). ⚠️ <b>MISURATO</b>, non deduttо dal commento
     * dell'oracolo: vedi il piano, §20 classe A.
     */
    private static final int SPRITE_ENABLE_OFFSET = 0;

    /** Pixel totali resi per sprite per frame corrente (reset a ogni vsync). */
    private final long[] spritePixelsThisFrame = new long[8];
    /** Pixel totali resi per sprite nel frame completato più recente. */
    private final long[] spritePixelsLastFrame = new long[8];

    // =========================================================================
    // Line buffer
    // =========================================================================

    /**
     * dBuffer: indice di colore bitplane grezzo (prima della priorità).
     * bBuffer: maschera bordo/DIW. 0xFF = dentro DIW, altro = colore bordo.
     * iBuffer: uso futuro (pixel interleaved / ECS).
     * mBuffer: indice di colore finale dopo risoluzione priorità.
     * zBuffer: depth buffer (valore maggiore = più vicino all'osservatore).
     */
    private final byte[] dBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] bBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] iBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] mBuffer = new byte[HPIXELS + 64 + 8];
    private final int[]  zBuffer = new int [HPIXELS + 64 + 8];
    private final int[]  rgbLine = new int[HPIXELS];

    // =========================================================================
    // Costanti Z-buffer (priorità/profondità)
    // Valore più alto = layer più in primo piano.
    //
    // Ordine (dal più vicino al più lontano):
    //   Z_0 > Z_SP0 > Z_SP1 > Z_1 > Z_SP2 > Z_SP3 > Z_2 >
    //   Z_SP4 > Z_SP5 > Z_3 > Z_SP6 > Z_SP7 > Z_4
    //
    // zPF mappa il registro BPLCON2 PFxP:
    //   PFP = 0 → Z_4 (dietro a tutti gli sprite)
    //   PFP = 4 → Z_0 (davanti a tutti gli sprite)
    //
    // NOTA: i valori sono potenze di 2 distinte, per cui l'OR di un insieme
    // di costanti Z mantiene come MSB il livello con priorità massima.
    // Questo garantisce che il confronto "z > zBuffer[p]" sia un test di
    // priorità corretto anche quando zBuffer accumula più layer tramite OR.
    //
    // Le maschere ausiliarie usate da translateDPF (0x01–0x07) cadono tutte
    // sotto Z_4 (0x0008), quindi non interferiscono mai con i confronti Z_SP.
    // =========================================================================

    private static final int Z_0   = 0x8000;
    private static final int Z_SP0 = 0x4000;
    private static final int Z_SP1 = 0x2000;
    private static final int Z_1   = 0x1000;
    private static final int Z_SP2 = 0x0800;
    private static final int Z_SP3 = 0x0400;
    private static final int Z_2   = 0x0200;
    private static final int Z_SP4 = 0x0100;
    private static final int Z_SP5 = 0x0080;
    private static final int Z_3   = 0x0040;
    private static final int Z_SP6 = 0x0020;
    private static final int Z_SP7 = 0x0010;
    private static final int Z_4   = 0x0008;

    private static final int[] Z_SP = {
        Z_SP0, Z_SP1, Z_SP2, Z_SP3, Z_SP4, Z_SP5, Z_SP6, Z_SP7
    };

    /**
     * Maschera che copre tutti i bit Z degli 8 sprite.
     * FIX 5: sostituisce la maschera errata 0x5555 usata in colorizeHAM,
     * che intercettava Z_1 (un livello Z del playfield) e mancava
     * Z_SP1, Z_SP2, Z_SP5, Z_SP6.
     */
    private static final int Z_ALL_SPRITES =
        Z_SP0 | Z_SP1 | Z_SP2 | Z_SP3 | Z_SP4 | Z_SP5 | Z_SP6 | Z_SP7;
    // = 0x4000|0x2000|0x0800|0x0400|0x0100|0x0080|0x0020|0x0010 = 0x6BB0

    // =========================================================================
    // Change recorder (per supporto Copper mid-scanline)
    // =========================================================================

    /**
     * Ritardo, in pixel, con cui un registro <b>BPLCON</b> agisce dentro Denise: <b>un CCK</b>.
     *
     * <p>⛔ <b>Non è un ritardo del bus: è lo STADIO della pipe RGA di Denise.</b> Nell'oracolo
     * (`drawing.cpp`, `do_denise_cck()`) gli slot si leggono a due indici diversi nello stesso
     * CCK:
     * <pre>
     *   idx0 = i + startpos - 0   → expand_drga_early(rd)   // COLORxx (update_color)
     *   idx1 = i + startpos - 1   → expand_drga(rd)         // BPLCON0/1/2/3, BPLxDAT, DIWSTRT…
     * </pre>
     * I colori agiscono nel CCK in cui sono scritti, i BPLCON <b>un CCK dopo</b>. È una
     * differenza <b>relativa</b>: per questo si vede solo dove la stessa riga ha entrambi.
     *
     * <p>Misurato su `Denise/Sprites` (`general/sprites1÷4`, `reenable1`,
     * `spritedma/sprdma1÷3`): il Copper commuta il dual-playfield con BPLCON0 a hpos `$51`,
     * `$61`, `$71`… e la transizione di colore cadeva <b>4 px = 1 CCK</b> troppo a sinistra,
     * sempre, otto test e 48÷56 px ciascuno.
     *
     * <p>⚠️ Nell'oracolo lo stesso stadio porta anche BPLCON1, BPLxDAT e DIWSTRT/DIWSTOP. Da
     * noi quei tre hanno modelli tarati per conto loro (lo split dello scroll fra Agnus e
     * Denise, BPL1DAT come trigger del display, `DIW_AXIS_SHIFT`) e <b>non</b> sono stati
     * spostati: servirebbe una misura propria per ciascuno.
     */
    private static final int CON_DELAY_PX = 4;

    private final RegChangeRecorder  conChanges = new RegChangeRecorder(128);
    private final RegChangeRecorder  diwChanges = new RegChangeRecorder(128);
    private final RegChangeRecorder  colChanges = new RegChangeRecorder(1024);
    private final RegChangeRecorder[] sprChanges = new RegChangeRecorder[4];

    // =========================================================================
    // Costruttore e reset
    // =========================================================================

    protected AbstractDenise() {
        for (int i = 0; i < 4; i++) sprChanges[i] = new RegChangeRecorder(128);
        reset();
    }

    public void setAgnus(AbstractAgnus agnus)            { this.agnus   = agnus;   }
    public void setField(int field)               { this.field   = field;   }

    private void updateBplcon0Derived() {
        bpu   = deniseBpu(bplcon0);
        hires = (bplcon0 & 0x8000) != 0;
    }

    /**
     * Numero di piani che <b>Denise</b> compone, ricavato da BPLCON0.
     *
     * <p>⛔ <b>Più di quattro piani in HIRES non è una configurazione legale su OCS/ECS:
     * Denise non ne disegna NESSUNO.</b> Non è un troncamento a quattro — l'immagine
     * sparisce del tutto e resta lo sfondo. L'oracolo lo dichiara in
     * {@code drawing.cpp update_fmode()}:
     * <pre>
     *   denise_planes = GET_PLANES(bplcon0_denise);
     *   if (denise_bplfmode == 0 &amp;&amp; denise_res &gt; 0 &amp;&amp; denise_planes &gt; 4) denise_planes = 0;
     *   if (denise_bplfmode == 0 &amp;&amp; denise_res &gt; 1 &amp;&amp; denise_planes &gt; 2) denise_planes = 0;
     * </pre>
     * (il secondo ramo è SuperHires, che non modelliamo). Il gemello lato Agnus è
     * {@code real_bitplane_number} — vedi {@code AbstractAgnus.bitplaneDmaPlaneLimit}:
     * i due chip applicano la stessa soglia, ciascuno per conto proprio.
     *
     * <p>La clamp a 6 è invece nostra e riguarda solo BPU=7 in lores: il registro a
     * scorrimento ha sei piani, e sull'oracolo sette piani in lores sono comunque
     * Extra-Half-Brite ({@code bplehb = denise_planes == 6 || (!aga_mode &amp;&amp;
     * denise_planes == 7)}), cioè lo stesso disegno che si ottiene con sei.
     */
    private static int deniseBpu(int bplcon0) {
        int planes = (bplcon0 >> 12) & 7;
        if ((bplcon0 & 0x8000) != 0 && planes > 4) return 0;
        return Math.min(planes, 6);
    }

    public void reset() {
        bplcon0 = bplcon1 = bplcon2 = 0;
        updateBplcon0Derived();
        bplcon3 = 0x0C00;
        bplcon4 = 0x0011;
        initialBplcon0 = initialBplcon2 = 0;
        hstrt = 0; hstop = 0;
        hstrtLineStart = 0; hstopLineStart = 0;
        hflop = true;
        borderBufferIsDirty = 0;
        borderColor = 0;
        clxdat = 0; clxcon = 0;
        Arrays.fill(palette,  0);
        Arrays.fill(paletteLineStart, 0);
        Arrays.fill(bpldat,   0);
        Arrays.fill(bpldatPipe, 0);
        Arrays.fill(shiftReg, 0);
        armedOdd = armedEven = false;
        srCount[0] = srCount[1] = 0;
        srPhase[0] = srPhase[1] = 0;
        srCursor[0] = srCursor[1] = 0;
        srBpu[0] = srBpu[1] = 0;
        Arrays.fill(sprdata,  0);
        Arrays.fill(sprdatb,  0);
        Arrays.fill(sprpos,   0);
        Arrays.fill(sprctl,   0);
        Arrays.fill(sprhppos, 0);
        Arrays.fill(ssra, 0);
        Arrays.fill(ssrb, 0);
        armed = 0;
        armedLineStart = 0;
        Arrays.fill(sprposLineStart,  0);
        Arrays.fill(sprctlLineStart,  0);
        Arrays.fill(sprdataLineStart, 0);
        Arrays.fill(sprdatbLineStart, 0);
        spriteClipBegin = 0;
        spriteClipEnd   = HPIXELS + 32;
        Arrays.fill(dBuffer, (byte) 0);
        Arrays.fill(bBuffer, (byte) 0xFF);
        Arrays.fill(iBuffer, (byte) 0);
        Arrays.fill(mBuffer, (byte) 0);
        Arrays.fill(zBuffer, 0);
        Arrays.fill(rgbLine, 0);
        conChanges.clear();
        diwChanges.clear();
        colChanges.clear();
        for (int i = 0; i < 4; i++) sprChanges[i].clear();
    }

    // =========================================================================
    // Accesso registri
    // =========================================================================

    public void writeRegister(int address, int value) {
        // vAmiga mappa i register-change al pixel con Beam::pixel = 4*(h - HBLANK_MIN),
        // dove h è il DMA-cycle INTERO (CCK, 0..227): NON ha risoluzione sub-CCK. Il
        // nostro hpos ha invece granularità di mezzo-CCK (0..454, 2 per CCK) e i write
        // della CPU a un registro custom completano sul sotto-ciclo DISPARI del CCK,
        // quindi hpos*2 dava +2px rispetto a vAmiga (che quantizza al confine CCK). I
        // write del Copper sono già su hpos pari, perciò la quantizzazione è un no-op
        // per loro: cambia solo i write CPU, allineandoli a vAmiga. (cck = hpos/2)
        int cck   = (agnus != null) ? agnus.getHpos() >> 1 : 0;
        int pixel = Math.max(0, cck * 4 - 4 * HBLANK_MIN);
        writeRegisterAtPixel(address, value, pixel);
    }

    public void writeRegisterAtPixel(int address, int value, int pixel) {
        address &= 0x1FE;

        switch (address) {

            // --- DIW ---
            case 0x08E: // DIWSTRT
                setHSTRT(value & 0xFF, pixel);
                break;
            case 0x090: // DIWSTOP
                // I valori hstop 0x00–0x7F sono interpretati come 0x100–0x17F
                // (il bit 8 è implicito, estende il range oltre i 256 pixel).
                setHSTOP((value & 0xFF) | 0x100, pixel);
                break;
            case 0x1E4: // DIWHIGH (D5, ECS): H8 di HSTART (bit 5) e HSTOP (bit 13).
                // Su ECS estende l'intervallo orizzontale della finestra display oltre
                // i limiti OCS; la parte V è gestita lato Agnus. No-op su OCS. Il valore
                // arriva già mascherato (0x2727) da AbstractAgnus.writeDIWHIGH.
                if (isECS) {
                    setHSTRT((hstrt & 0xFF) | ((value & 0x0020) != 0 ? 0x100 : 0), pixel);
                    setHSTOP((hstop & 0xFF) | ((value & 0x2000) != 0 ? 0x100 : 0), pixel);
                }
                break;

            // --- Collision ---
            case 0x098: clxcon = value; break;

            // --- JOYTEST ($DFF036) ---
            // Preset dei contatori quadratura di entrambe le porte.
            // I 6 bit alti di ciascun byte sovrascrivono il contatore;
            // i 2 bit bassi (quadratura istantanea) vengono preservati.
            case 0x036:
                if (gamePort1 != null) gamePort1.pokeJoytest(value);
                if (gamePort2 != null) gamePort2.pokeJoytest(value);
                break;

            // --- BPLCON ---
            case 0x100: // BPLCON0
                conChanges.insert(pixel + CON_DELAY_PX, address, value);
                // Cambio di modalità di COLORE: tracciato in colChanges per colorize.
                // ⛔ Sono DUE, non solo HAM: anche Extra-Half-Brite si accende e si spegne
                // dentro la riga (BPU che passa a 6, o che lascia una configurazione hires
                // illegale), e senza questa insert la riga in cui il Copper alza BPU a 6
                // resta colorata a piena luminosità per intero — il residuo di riga 22 di
                // `BPLPTR/drop6x` e `dropcpu6x`.
                if (isHamActive(bplcon0) != isHamActive(value) || isEHB(bplcon0) != isEHB(value)) {
                    // Stesso stadio del conChanges qui sopra: HAM ed EHB escono da
                    // bplcon0_denise, cioe' da expand_bplcon0(), non dal ramo _early.
                    colChanges.insert(pixel + CON_DELAY_PX, address, value);
                }
                bplcon0 = value;
                updateBplcon0Derived();
                break;
            case 0x102: // BPLCON1 (scroll — effettivo subito)
                bplcon1 = value & 0xFF;
                break;
            case 0x104: // BPLCON2 (priorità)
                conChanges.insert(pixel, address, value);
                bplcon2 = value;
                break;
            case 0x106: bplcon3 = value; break;  // ECS
            case 0x10C: bplcon4 = value; break;  // ECS

            // --- BPLxDAT ---
            case 0x110: case 0x112: case 0x114:
            case 0x116: case 0x118: case 0x11A:
                setBPLxDAT((address - 0x110) / 2, value, pixel);
                break;

            // --- SPRxPOS ---
            case 0x140: case 0x148: case 0x150: case 0x158:
            case 0x160: case 0x168: case 0x170: case 0x178:
                pokeSPRxPOS((address - 0x140) / 8, value, pixel);
                break;

            // --- SPRxCTL ---
            case 0x142: case 0x14A: case 0x152: case 0x15A:
            case 0x162: case 0x16A: case 0x172: case 0x17A:
                pokeSPRxCTL((address - 0x142) / 8, value, pixel);
                break;

            // --- SPRxDATA ---
            case 0x144: case 0x14C: case 0x154: case 0x15C:
            case 0x164: case 0x16C: case 0x174: case 0x17C:
                pokeSPRxDATA((address - 0x144) / 8, value, pixel);
                break;

            // --- SPRxDATB ---
            case 0x146: case 0x14E: case 0x156: case 0x15E:
            case 0x166: case 0x16E: case 0x176: case 0x17E:
                pokeSPRxDATB((address - 0x146) / 8, value, pixel);
                break;

            case 0x038: case 0x03A: case 0x03C: case 0x03E:
                // Strobe registers: no effect in this model unless light pen or similar is used
                break;

            default:
                // COLOR00..COLOR31
                if (address >= 0x180 && address <= 0x1BE) {
                    int ci = (address - 0x180) / 2;
                    palette[ci] = value & 0xFFF;
                    colChanges.insert(pixel, address, value);
                }
                break;
        }
    }

    public int readRegister(int address) {
        address &= 0x1FE;
        switch (address) {
            // JOY0DATR / JOY1DATR: i contatori di quadratura della porta. Non dipendono da
            // che cosa vi sia collegato — una porta vuota legge i propri contatori, non 0
            // (vedi GamePort). Lo 0 qui sotto vale solo per una macchina montata SENZA
            // quella porta, che l'A500 non è.
            case 0x00A: return (gamePort1 != null ? gamePort1.joydat() : 0) & 0xFFFF;
            case 0x00C: return (gamePort2 != null ? gamePort2.joydat() : 0) & 0xFFFF;
            // CLXDAT: bit 15 sempre 1; lettura azzera il registro
            case 0x00E: { int v = (clxdat & 0x7FFF) | 0x8000; clxdat = 0; return v; }

            // DENISEID ($DFF07C):
            //   ECS Denise 8373 → restituisce $FFFC (identificativo chip ECS).
            //   OCS Denise 8362 → registro non implementato; sul bus reale i
            //   dati galleggiano a $FFFF (pull-up). NON si usa getLastBusData():
            //   l'ultimo dato scritto sul bus (es. BPLCON0=$0102) inquinerebbe
            //   il rilevamento OCS/ECS da parte del Kickstart.
            case 0x07C: return isECS ? 0xFFFC : 0xFFFF;

            // BPLCON0 ($DFF100) — write-only su OCS.
            //
            // ROOT CAUSE del bug BPLCON0=$1302 invece di $1200:
            //   Il Kickstart a $FCAF42 esegue:
            //     MOVE.W  BPLCON0, D0   ; legge BPLCON0
            //     ORI.W   #$1200, D0    ; aggiunge i bit display
            //     MOVE.W  D0, BPLCON0   ; scrive il risultato
            //
            //   Con la precedente implementazione (return bplcon0 = $0102,
            //   valore scritto al passo di init a $FCAFBA), il Kickstart calcolava:
            //     D0 = $0102 | $1200 = $1302  →  BPLCON0 errato  ✗
            //
            //   Su hardware OCS reale BPLCON0 è write-only: la lettura restituisce
            //   il bus aperto (~$FFFF o 0), NON il valore ombra. Il Kickstart
            //   calcola quindi:
            //     D0 = $0000 | $1200 = $1200  →  BPLCON0 corretto  ✓
            //
            // Per ECS Denise (che rende leggibili tutti i registri di controllo)
            // si restituisce il valore ombra corrente.
            case 0x100: return isECS ? bplcon0 : 0;

            // BPLCON1 ($DFF102) — write-only su OCS, leggibile su ECS.
            case 0x102: return isECS ? bplcon1 : 0;

            // BPLCON2 ($DFF104) — write-only su OCS, leggibile su ECS.
            case 0x104: return isECS ? bplcon2 : 0;

            // BPLCON3 ($DFF106) — registro ECS, inesistente su OCS.
            case 0x106: return isECS ? bplcon3 : 0;

            // BPLCON4 ($DFF10C) — registro ECS/AGA, inesistente su OCS.
            case 0x10C: return isECS ? bplcon4 : 0;

            default:
                // Registri colore ($DFF180–$DFF1BE):
                //   Su ECS Denise sono leggibili; su OCS sono write-only
                //   (il bus restituisce 0 per i registri non implementati in lettura).
                if (address >= 0x180 && address <= 0x1BE) {
                    return isECS ? palette[(address - 0x180) / 2] : 0;
                }
                return 0xFFFF;
        }
    }

    // =========================================================================
    // Helpers write register
    // =========================================================================

    /**
     * ⛔ <b>Le due scritture DIW si registrano sull'asse del CONTATORE, non su quello dei
     * pixel</b>, e i due assi hanno una fase diversa: {@code DIW_AXIS_SHIFT} è quella fase.
     *
     * <p>Il comparatore della finestra orizzontale è l'unico consumatore di Denise che non
     * lavora sull'indice di pixel ma su {@code counter} (vedi {@link #updateBorderBuffer()}),
     * e la conversione {@code counter = 2·HBLANK_MIN + i/2} mancava di una costante. Misurata
     * sui due lati con {@code WINUAE_HDIWLOG} (metà produttore, in {@code custom.cpp}) e
     * {@code DiwWriteProbe}, sulla stessa {@code vpos} e sulle stesse sei righe di sweep di
     * {@code diwtim1}: l'oracolo scrive a {@code agnus_hpos} 178, 180, 182, 184, 186, 188 e
     * noi ai cck 174, 176, 178, 180, 182, 184 — Δ costante <b>+4</b>, che è la convenzione di
     * etichetta fra le due griglie già misurata dal §12 sui marcatori di {@code synccpu}.
     * Appaiando con l'evento {@code CALC} ({@code hcnt = 2·agnus_hpos − 6}), all'istante della
     * <b>stessa</b> scrittura l'oracolo ha {@code hcnt} = il nostro {@code counter} <b>+ 2</b>,
     * e due unità di contatore sono <b>un CCK</b>, cioè quattro sub-pixel.
     *
     * <p>⚠️ <b>Non è un ritardo della scrittura</b>, ed è la distinzione che conta: applicare
     * lo stesso spostamento a <b>tutte</b> le scritture di Denise porta il §16
     * {@code Denise/Registers} da 52/16 a <b>27/34</b> — sull'asse dei pixel siamo già giusti,
     * ed è il §16 a dimostrarlo. Lo spostamento vale per il solo consumatore che cambia asse.
     *
     * <p>⚠️ Equivalente e sbagliato: spostare l'origine di {@code counter}. Sposterebbe anche
     * <b>dove cadono i bordi</b> della finestra, che oggi sono giusti su tutte le righe che non
     * sono di transizione.
     */
    private static final int DIW_AXIS_SHIFT = 4;

    private void setHSTRT(int val, int pixel) {
        hstrt = val;
        diwChanges.insert(pixel + DIW_AXIS_SHIFT, 0x08E, val);
        borderBufferIsDirty = Math.max(borderBufferIsDirty, 2);
    }

    private void setHSTOP(int val, int pixel) {
        hstop = val;
        diwChanges.insert(pixel + DIW_AXIS_SHIFT, 0x090, val);
        borderBufferIsDirty = Math.max(borderBufferIsDirty, 2);
    }

    private void setBPLxDAT(int x, int value, int pixel) {
        bpldat[x] = value & 0xFFFF;
        if (x == 0) {
            // BPL1DAT scritto dal DMA: carica la pipeline e arma i shift register.
            // BPL1DAT è il trigger perché nel ciclo DMA è l'ultimo piano dispari ad
            // essere fetchato (Agnus ha già scritto BPL3/5 nei cicli precedenti).
            System.arraycopy(bpldat, 0, bpldatPipe, 0, 6);
            armedOdd  = true;
            armedEven = true;
            bpl1datWrittenThisLine = true;
            if (pixel < bpl1datPixelThisLine) bpl1datPixelThisLine = pixel;
        }
    }

    /**
     * Istante in cui una scrittura a SPRxDATA/SPRxDATB agisce, in pixel dal confine di CCK.
     * Nell'oracolo è lo stadio {@code expand_drga_early} (idx0) e l'applicazione è
     * <b>immediata</b>: {@code case 0x144…: sprwrite(sreg, rd->v)}.
     */
    private static final int SPR_DAT_PX = 8;

    /**
     * Istante in cui una scrittura a SPRxPOS/SPRxCTL agisce: <b>mezzo CCK più tardi</b>
     * di SPRxDATA.
     *
     * <p>⛔ Nell'oracolo POS e CTL <b>non</b> si applicano allo stadio in cui arrivano:
     * {@code expand_drga_early} li mette da parte ({@code spr_unalign_reg[0]}) e il
     * consumo avviene nel disegno per-pixel, sulla <b>seconda metà</b> del color clock e
     * <b>dopo</b> il confronto orizzontale (`drawing.cpp`, percorso OCS/ECS):
     * <pre>
     *   if (h &amp;&amp; spr_unalign_reg[0]) {
     *       matchsprites2(cnt &lt;&lt; 2);                              // il match usa il valore VECCHIO
     *       sprwrite(spr_unalign_reg[0] - 0x140, spr_unalign_val[0]);
     *       spr_unalign_reg[0] = 0;
     *   }
     * </pre>
     * SPRxDATA/SPRxDATB quella deviazione non ce l'hanno.
     *
     * <p>Misurato su `Denise/Sprites/timing/sprtimpos1` e `sprtimctl1`, che scrivono
     * SPRxPOS/SPRxCTL <b>a ridosso</b> dell'inizio orizzontale dello sprite: la nostra
     * scadenza era <b>un CCK intero</b> dopo quella di SPRxDATA, e sulle righe di confine
     * rifiutavamo una scrittura che l'oracolo accetta.
     */
    private static final int SPR_POSCTL_PX = SPR_DAT_PX + 2;

    private void pokeSPRxPOS(int x, int value, int pixel) {
        sprpos[x] = value;
        updateSprHCoords(x);
        sprChanges[x / 2].insert(pixel + SPR_POSCTL_PX, 0x140 + x * 8, value);
    }

    private void pokeSPRxCTL(int x, int value, int pixel) {
        sprctl[x] = value;
        updateSprHCoords(x);
        // CTL write disarms the sprite immediately (matches vAmiga: CLR_BIT(armed, x)).
        // This prevents stale data from the previous frame rendering on scanlines
        // before VSTART (which would create a vertical line artifact).
        armed &= ~(1 << x);
        sprChanges[x / 2].insert(pixel + SPR_POSCTL_PX, 0x142 + x * 8, value);
    }

    private void pokeSPRxDATA(int x, int value, int pixel) {
        armed    |= (1 << x);
        sprdata[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x144 + x * 8, value);
    }

    private void pokeSPRxDATB(int x, int value, int pixel) {
        sprdatb[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x146 + x * 8, value);
    }

    private void updateSprHCoords(int x) {
        // sprhpos = SPRPOS[7:0] << 2 | SPRCTL[0] << 1  (HSTART * 2, matching vAmiga)
        int sprhpos = ((sprpos[x] & 0xFF) << 2) | ((sprctl[x] & 0x01) << 1);
        sprhppos[x] = sprhpos + 2 - 4 * HBLANK_MIN;
    }

    // =========================================================================
    // Pipeline principale di rendering (chiamata da Agnus a ogni HSYNC)
    // =========================================================================

    /**
     * Punto di ingresso principale: processa la scanline appena completata
     * e scrive i pixel sul display.
     *
     * @param vpos  numero di scanline verticale (0-based)
     */
    public void hsyncHandler(int vpos) {
        lastRenderedBplcon0 = initialBplcon0;
        if (vpos == 0) {
            System.arraycopy(spritePixelsThisFrame, 0, spritePixelsLastFrame, 0, 8);
            Arrays.fill(spritePixelsThisFrame, 0L);
        }
        // PERF (Fase 3): NON si pre-riempie più rgbLine qui. Entrambi i rami sottostanti
        // coprono già l'INTERA rgbLine[0..HPIXELS): il ramo visibile via colorize()
        // (colorizeNormal/colorizeHAM scrivono ogni pixel, bordi inclusi, perché i
        // segmenti di colChanges tassellano [0,HPIXELS)), il ramo VBlank via
        // fillVBlankLine(). La vecchia Arrays.fill(rgbLine,...) incondizionata era quindi
        // interamente sovrascritta su ~92% delle righe (le visibili) = lavoro sprecato
        // (~3,5% del tempo core in Arrays.fill). Rimuoverla è bit-exact (golden CRC
        // 832316F5 invariata).
        // Una word armata a fine riga non fa in tempo a uscire tutta: i CCK per farlo non
        // ci sono più. Il vecchio modello a blocchi quei pixel li aveva già scritti, quindi
        // qui si finisce di scriverli — è la coda della riga, oltre la finestra di fetch, e
        // serve solo a NON introdurre una seconda differenza. Vedi srCount.
        flushShiftOut();

        updateBorderBuffer();

        if (agnus != null && !agnus.inVBlankArea(vpos)) {
            // Scanline visibile: pipeline completa.
            // OCS: gli sprite sono visibili solo nelle righe che hanno prodotto
            // output bitplane. Una riga senza bitplane (bordo verticale, anche se
            // dentro la DIW — es. North & South spegne i bitplane sotto le bandiere
            // ma lascia la DIW aperta fino a riga 299) è interamente bordo: il
            // cursore/sprite NON vi compare. Cfr. WinUAE pfield_draw_line
            // (border = plfleft<0 → sprite solo con BRDSPRT/ECS).
            lineIsVBorder = !bpl1datWrittenThisLine;
            translate();
            // Bit 1 di `sprites_hidden`: prima della prima BPL1DAT della riga lo sprite
            // non esce. Vedi bpl1datPixelThisLine.
            spriteClipBegin = bpl1datPixelThisLine == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : bpl1datPixelThisLine + SPRITE_ENABLE_OFFSET;
            drawSprites();
            checkCollisions();
            colorize(vpos);
        } else {
            lineIsVBorder = false;
            // VBlank: riempie con il colore di sfondo per evitare la banda nera.
            fillVBlankLine(vpos);

            // FIX 9: In VBlank drawSprites() NON viene chiamato. Chiamarla
            // lasciava i sprChanges[i] sporchi perché renderSpriteChunk li
            // consumava (recorder.clear()) ma le scritture su mBuffer/zBuffer
            // avrebbero potuto sporcare i buffer per la prima scanline visibile.
            // I recorder vanno comunque svuotati esplicitamente per evitare
            // l'accumulo di eventi sprite registrati durante il VBlank.
            for (int i = 0; i < 4; i++) sprChanges[i].clear();

            colChanges.clear();
            conChanges.clear();
        }

        // Pulizia buffer per la prossima scanline.
        //
        // STEP 6 (PERFORMANCE_REPORT_FINAL): azzeramento mirato dell'hot path.
        // hsyncHandler è chiamato ~15.600 volte/s; il fill dello zBuffer (int[984])
        // era il più costoso. Analisi del data-flow (vedi pipeline translate→...→colorize):
        //
        //  • dBuffer: scritto con |= da fillShiftRegisters e letto INTERAMENTE da
        //    translate (arraycopy dBuffer→mBuffer). Richiede clear completo.
        //  • zBuffer: translate() ASSEGNA (non OR) zBuffer[0..HPIXELS) ad ogni riga
        //    visibile prima di qualsiasi lettura; la coda [HPIXELS, length) NON è
        //    toccata da translate ma è usata da drawSprites (|=) e dalle letture,
        //    quindi va azzerata. → si azzera solo la coda (risparmio: HPIXELS int).
        //  • mBuffer: translate() lo sovrascrive INTERAMENTE via arraycopy all'inizio
        //    della pipeline, prima di ogni lettura. → clear ridondante, rimosso.
        //  • iBuffer: "uso futuro", mai letto né scritto altrove. → clear rimosso.
        //
        // In VBlank i buffer non vengono letti (vedi ramo else sopra), quindi non
        // azzerare [0,HPIXELS) dello zBuffer è sicuro anche al passaggio VBlank→riga
        // visibile (translate riassegna prima di leggere).
        Arrays.fill(dBuffer, (byte) 0);
        Arrays.fill(zBuffer, HPIXELS, zBuffer.length, 0);
        for (int i = 0; i < 8; i++) { ssra[i] = 0; ssrb[i] = 0; }
        spritesOnLineMask = 0;
        bpl1datWrittenThisLine = false;   // reset per la prossima scanline
        bpl1datPixelThisLine = Integer.MAX_VALUE;

        // Gli sprite possono essere visibili ovunque sulla riga attiva;
        // la posizione orizzontale è già filtrata da sprhppos (negativo se in blanking).
        spriteClipBegin = 0;
        spriteClipEnd   = HPIXELS + 32;

        // Snapshot dei registri: saranno usati come stato "iniziale" per
        // la scanline che sta per cominciare, prima di qualsiasi aggiornamento.
        initialBplcon0 = bplcon0;
        initialBplcon2 = bplcon2;
        // Stesso ragionamento della palette, per la finestra orizzontale: updateBorderBuffer()
        // ha appena consumato la riga, quindi i valori vivi sono quelli che la PROSSIMA riga
        // eredita. Vedi hstrtLineStart.
        hstrtLineStart = hstrt;
        hstopLineStart = hstop;
        // Snapshot dei registri colore di FINE riga (= inizio della prossima):
        // nel ramo visibile colorize() ha appena riportato palette[] allo stato di
        // fine riga; nel ramo VBlank palette[] contiene già i valori live. In
        // entrambi i casi questo è lo stato ereditato dalla scanline successiva.
        System.arraycopy(palette, 0, paletteLineStart, 0, 32);
        // Stesso ragionamento per i registri sprite: nel ramo visibile drawSprites() ha
        // appena riportato i registri vivi allo stato di fine riga rigiocando le change,
        // nel ramo VBlank sono già vivi. In entrambi i casi è lo stato ereditato dalla
        // scanline successiva. Vedi armedLineStart.
        armedLineStart = armed;
        System.arraycopy(sprpos,  0, sprposLineStart,  0, 8);
        System.arraycopy(sprctl,  0, sprctlLineStart,  0, 8);
        System.arraycopy(sprdata, 0, sprdataLineStart, 0, 8);
        System.arraycopy(sprdatb, 0, sprdatbLineStart, 0, 8);

        diwChanges.clear();
        conChanges.clear();
        colChanges.clear();
    }

    // =========================================================================
    // VBlank fill
    // =========================================================================

    /**
     * Riempie una scanline in area VBlank con il colore di sfondo corrente.
     * Senza questo, i pixel lasciati non inizializzati appaiono neri.
     */
    private void fillVBlankLine(int vpos) {
        int bgRGB    = amigaToRGB(palette[0]);
        Arrays.fill(rgbLine, bgRGB);
    }

    // =========================================================================
    // Fase 1: caricamento degli shift register ed emissione dei pixel
    // =========================================================================

    /**
     * <b>Carica</b> gli shift register: non disegna più niente qui. I pixel escono da
     * {@link #emitBitplanePixels(int)}, quattro per CCK, al passo della risoluzione viva.
     * Vedi {@link #srCount} per il perché.
     */
    /**
     * @param fineOdd  resto sotto il CCK dello scroll dei piani dispari, in <b>sub-pixel</b>
     *                 (0÷3); la parte grossa l'ha già applicata Agnus ritardando il CCK di
     *                 draw. La divisione e la regola stanno in
     *                 {@code AbstractAgnus.scrollSubPixels}.
     * @param fineEven lo stesso per i piani pari. ⚠️ In SPF Agnus passa qui il valore di
     *                 PF1H, perché i piani pari scrollano con i dispari.
     */
    public void drawBitplanesAtPixel(int pixel, boolean drawOdd, boolean drawEven,
                                     int fineOdd, int fineEven) {
        // ⛔ Lo scroll si legge VIVO, non dallo snapshot di inizio riga: BPLCON1 scritto
        // dentro la riga vale da subito (l'oracolo aggiorna `bplcon1_shift[]` alla
        // scrittura, `update_bplcon1()`). Con `initialBplcon1` arrivava una RIGA tardi, e lo
        // smaschera `BPL0DAT/bpl0dat*`, dove il Copper scrive BPLCON1 e BPL1DAT sulla stessa
        // riga: la riga a scroll 0 era esatta e tutte le altre indietro del proprio scroll.
        if (drawOdd && armedOdd) {
            loadOddShiftRegisters();
            armShiftOut(0, pixel, fineOdd);
            armedOdd = false;
        }
        if (drawEven && armedEven) {
            loadEvenShiftRegisters();
            armShiftOut(1, pixel, fineEven);
            armedEven = false;
        }
    }

    /**
     * Arma l'uscita di una word: 16 bit da far scorrere a partire da {@code pixel}, più lo
     * scroll <b>fine</b> — il resto sotto il CCK, gia' in SUB-PIXEL (0÷3). La parte grossa
     * l'ha applicata Agnus ritardando il CCK di draw; la regola che le divide sta in
     * {@code AbstractAgnus.scrollSubPixels}.
     *
     * <p>⚠️ {@code bpu} e il dual-playfield si <b>latchano qui</b>: il modello a blocchi li
     * congelava all'istante del draw, e tenerli congelati è ciò che rende questo
     * rifacimento bit-identico a risoluzione costante.
     */
    private void armShiftOut(int grp, int pixel, int fineScroll) {
        srCount[grp]  = 16;
        srCursor[grp] = pixel + fineScroll;   // fineScroll e' gia' in sub-pixel
        srPhase[grp]  = 0;
        srBpu[grp]    = bpu;
        precomputeSlices(grp);
    }

    /**
     * Fa uscire i <b>due sub-pixel di questa MEZZA CCK</b> da entrambi i gruppi di shift
     * register. Chiamata <b>due volte per CCK</b> da Agnus: nel fronte di salita (dopo
     * l'eventuale caricamento) e nel fronte di discesa, subito dopo il Copper.
     *
     * <p>Un bit dura <b>due</b> sub-pixel in lores e <b>uno</b> in hires, e la risoluzione
     * si rilegge a ogni sub-pixel: è qui, e solo qui, che una commutazione a metà word
     * cambia il passo di uscita.
     *
     * <p>⛔ <b>Perché due mezze-CCK e non un CCK intero.</b> Nell'oracolo la parte
     * <b>risoluzione</b> di BPLCON0 entra <b>uno stadio prima</b> del resto del registro:
     * {@code expand_drga_early()} — che chiama {@code expand_bplcon0_early()} — legge lo
     * slot RGA {@code idx0}, mentre {@code expand_drga()} legge {@code idx1}, cioè uno
     * indietro. Quello stadio vale <b>mezzo color clock</b>, ed è misurato: emettendo
     * quattro sub-pixel in blocco in cima al CCK, la commutazione di {@code hilo} cadeva
     * <b>due sub-pixel tardi</b> su tutte e dodici le righe. Spezzando l'emissione a metà,
     * la scrittura del Copper del CCK corrente arriva in tempo per la seconda metà.
     *
     * <p>⚠️ Spezzarla non cambia <b>quali</b> sub-pixel si scrivono né con che valore: il
     * cursore avanza allo stesso modo. Cambia solo l'istante in cui {@code hires} viene
     * riletto, che è esattamente l'unica cosa che questo rifacimento vuole spostare.
     */
    public void emitBitplaneHalfCck() {
        if (srCount[0] == 0 && srCount[1] == 0) return;
        if (srCount[0] != 0) emitGroup(0, 2);
        if (srCount[1] != 0) emitGroup(1, 2);
    }

    /** Fa uscire quel che resta delle due word, a fine riga. Vedi il chiamante. */
    private void flushShiftOut() {
        while (srCount[0] > 0 || srCount[1] > 0) {
            if (srCount[0] > 0) emitGroup(0, 2);
            if (srCount[1] > 0) emitGroup(1, 2);
        }
    }

    /**
     * Precalcola i <b>sedici slice</b> della word: il contributo a {@code dBuffer} di ogni
     * bit, già combinato sui tre piani del gruppo.
     *
     * <p>⚠️ <b>È un'ottimizzazione, non un modello</b>, e la si può fare solo perché nulla
     * tocca {@code shiftReg} fra un caricamento e il successivo: i BPLxDAT scrivono
     * {@code bpldat}/{@code bpldatPipe}, e un nuovo caricamento riarma comunque tutto. Senza
     * di essa l'emissione costa tre test e tre shift <b>per sub-pixel</b> invece che per
     * word, e si misura: +12,45 % sul banco A/B (`tools/ab-bench`, 5/5 concordi).
     */
    private void precomputeSlices(int grp) {
        final byte[] dst = srSlice[grp];
        final int bpuL = srBpu[grp];
        final int r0, r1, r2, m0, m1, m2;
        if (grp == 0) {
            r0 = bpuL >= 1 ? shiftReg[0] : 0; m0 = 1;
            r1 = bpuL >= 3 ? shiftReg[2] : 0; m1 = 4;
            r2 = bpuL >= 5 ? shiftReg[4] : 0; m2 = 16;
        } else {
            r0 = bpuL >= 2 ? shiftReg[1] : 0; m0 = 2;
            r1 = bpuL >= 4 ? shiftReg[3] : 0; m1 = 8;
            r2 = bpuL >= 6 ? shiftReg[5] : 0; m2 = 32;
        }
        for (int i = 0; i < 16; i++) {
            final int bit = 0x8000 >> i;
            int slice = 0;
            if ((r0 & bit) != 0) slice |= m0;
            if ((r1 & bit) != 0) slice |= m1;
            if ((r2 & bit) != 0) slice |= m2;
            dst[i] = (byte) slice;
        }
    }

    private void emitGroup(int grp, int subPixels) {
        final byte[] slices = srSlice[grp];
        final int keepMask = (grp == 0) ? 0x2A : 0x15;
        final int bitStep  = hires ? 1 : 2;   // sub-pixel per bit — letto VIVO
        int count  = srCount[grp];
        int cursor = srCursor[grp];
        int phase  = srPhase[grp];
        // Il cursore resta in fase col CCK: due sub-pixel per mezza-CCK, sempre.
        for (int i = 0; i < subPixels && count > 0; i++) {
            if (cursor >= 0 && cursor < dBuffer.length) {
                dBuffer[cursor] = (byte) ((dBuffer[cursor] & keepMask) | slices[16 - count]);
            }
            cursor++;
            if (++phase >= bitStep) {
                phase = 0;
                count--;
            }
        }
        srCount[grp]  = count;
        srCursor[grp] = cursor;
        srPhase[grp]  = phase;
    }

    private void loadOddShiftRegisters() {
        if (bpu >= 1) shiftReg[0] = bpldatPipe[0];
        if (bpu >= 3) shiftReg[2] = bpldatPipe[2];
        if (bpu >= 5) shiftReg[4] = bpldatPipe[4];
    }

    private void loadEvenShiftRegisters() {
        if (bpu >= 2) shiftReg[1] = bpldatPipe[1];
        if (bpu >= 4) shiftReg[3] = bpldatPipe[3];
        if (bpu >= 6) shiftReg[5] = bpldatPipe[5];
    }


    // =========================================================================
    // Fase 2: updateBorderBuffer — calcola bBuffer (maschera DIW/bordo)
    // =========================================================================

    private void updateBorderBuffer() {
        if (borderBufferIsDirty == 0) return;
        borderBufferIsDirty--;

        // ⛔ La finestra orizzontale è un FLIP-FLOP che ATTRAVERSA la riga: si parte dallo
        // stato in cui la riga precedente l'ha lasciata, non da un confronto di livello.
        // È `denise_hdiw` dell'oracolo — una variabile persistente che cambia SOLO nelle due
        // transizioni (`do_hstrt_ecs`/`do_hstop_ecs`) e non viene mai ricalcolata a inizio
        // riga.
        //
        // Il counter itera [2..455] (wrap $1C8→2) e non raggiunge mai valori come 0 o $1FF:
        // con hstrt/hstop fuori da quel range i fronti non scattano MAI, e il flip-flop
        // resta dov'è per sempre. È esattamente il comportamento che serve nei due casi che
        // il banco §17 e i giochi mettono alla prova:
        //   - Barbarian (DIWSTRT H=$00, DIWSTOP H=$FF→$1FF): nessuna transizione, la
        //     finestra resta APERTA perché lo era (hflop parte a true al reset) → schermo
        //     visibile. Un confronto di livello dava lo stesso esito, ma per un'altra strada;
        //   - DIWH/minmax (DIWSTRT H=$FF, DIWSTOP oltre $1C7): la finestra si apre a 255 e
        //     NON si richiude, quindi la riga dopo comincia già aperta. ⛔ Qui il confronto
        //     di livello sbagliava — ricalcolava "chiusa" a inizio riga — ed è il rosso che
        //     ha fatto scoprire la forma giusta: 4 righe su minmax, l'oracolo che disegna
        //     dalla colonna 30 dove noi partivamo dalla 314 (= hstart 255).
        //
        // ⚠️ hflop è aggiornato in coda a questo metodo con lo stato di fine riga, quindi
        // qui porta esattamente ciò che la riga precedente ha lasciato.
        // ⛔ E le COORDINATE si prendono dallo stato di inizio riga, non da quello vivo:
        // vedi hstrtLineStart.
        int     hs      = hstrtLineStart;
        int     he      = hstopLineStart;
        int     counter = HBLANK_MIN * 2;
        boolean hf      = hflop;
        int     diwIdx  = 0;

        for (int i = 0; i < HPIXELS; i++) {
            // Applica eventuali modifiche DIW in questo pixel
            while (diwIdx < diwChanges.size() && i >= diwChanges.keys[diwIdx]) {
                RegChange c = diwChanges.elements[diwIdx++];
                if      (c.reg == 0x08E) hs = c.value;
                else if (c.reg == 0x090) he = c.value;
            }
            // L'ordine è quello dell'oracolo (drawing.cpp, lts_*_ecs): prima lo start, poi
            // lo stop, ed entrambi `if` separati ⇒ a coordinate uguali lo STOP vince.
            if (counter == hs) hf = true;
            if (counter == he) hf = false;

            // Incrementa counter ogni 2 pixel (1 CCK)
            if ((i & 1) == 1) {
                counter = (counter + 1) & 0x1FF;
                if (counter == 0x1C8) counter = 2;  // wrap PAL
            }
            bBuffer[i] = hf ? (byte) 0xFF : (byte) borderColor;
        }

        if (hflop != hf) {
            hflop = hf;
            borderBufferIsDirty = Math.max(borderBufferIsDirty, 1);
        }
    }

    // =========================================================================
    // Fase 3: translate — risolve priorità playfield → mBuffer + zBuffer
    // =========================================================================

    private void translate() {
        System.arraycopy(dBuffer, 0, mBuffer, 0, mBuffer.length);

        int     pixel          = 0;
        boolean dual           = isDualPlayfield(initialBplcon0);
        int     currentBplcon2 = initialBplcon2;
        int     conIdx         = 0;

        while (conIdx < conChanges.size()) {
            int trigger = conChanges.keys[conIdx];
            if (dual) translateDPF(pixel, trigger, currentBplcon2);
            else      translateSPF(pixel, trigger, currentBplcon2);
            pixel = trigger;
            RegChange c = conChanges.elements[conIdx++];
            if      (c.reg == 0x100) dual           = isDualPlayfield(c.value);
            else if (c.reg == 0x104) currentBplcon2 = c.value;
        }
        if (dual) translateDPF(pixel, HPIXELS, currentBplcon2);
        else      translateSPF(pixel, HPIXELS, currentBplcon2);

        conChanges.clear();
    }

    /**
     * Single PlayField: la priorità sprite/playfield usa i bit PF2P
     * (bplcon2 bit 3-5), NON PF1P. In modalità single-playfield il playfield
     * è valutato come "playfield 2" ai fini della priorità verso gli sprite.
     *
     * Confermato da vAmiga (Denise::translateSPF usa state.zpf2 = zPF2(bplcon2))
     * e WinUAE. In precedenza usavamo erroneamente PF1P (bplcon2 & 7): con
     * BPLCON2 in cui PF1P!=PF2P (es. Ultima VI menu, BPLCON2=0x0008 → PF1P=0,
     * PF2P=1) il cursore sprite finiva DIETRO al playfield invece che davanti.
     */
    private void translateSPF(int from, int to, int bplcon2) {
        int plf2pri = (bplcon2 >> 3) & 7;
        int zpf2    = zPF(plf2pri);
        // La regola «SWIV» vive qui: playfield singolo, almeno 5 piani e PF2P >= 5. Il
        // gemello per il dual-playfield sta in translateDPF. Vedi deniseSpecials().
        boolean specials = bpu >= 5 && plf2pri >= 5;
        for (int i = from; i < to && i < mBuffer.length; i++) {
            int s = mBuffer[i] & 0x3F;
            if (specials) {
                s = deniseSpecials(s, bpu, false, bplcon2 & 7, plf2pri);
                mBuffer[i] = (byte) s;
            }
            zBuffer[i] = (s != 0) ? zpf2 : 0;
        }
    }

    /**
     * Dual PlayField: separa i bit pari (PF1) e dispari (PF2),
     * risolve priorità in base a bplcon2.
     */
    private void translateDPF(int from, int to, int bplcon2) {
        boolean pf2pri  = (bplcon2 & 0x0040) != 0;
        int     plf1pri = bplcon2 & 7;
        int     plf2pri = (bplcon2 >> 3) & 7;
        int     zpf1    = zPF(plf1pri);
        int     zpf2    = zPF(plf2pri);

        for (int i = from; i < to && i < mBuffer.length; i++) {
            int s    = dBuffer[i] & 0x3F;
            // Bit 0,2,4 → PF1 (piani dispari); bit 1,3,5 → PF2 (piani pari)
            int idx1 = (s & 1) | ((s & 4) >> 1) | ((s & 16) >> 2);
            int idx2 = ((s & 2) >> 1) | ((s & 8) >> 2) | ((s & 32) >> 3);

            // ⛔ Il COLORE esce dalla tabella dell'oracolo, che è la stessa che serve a
            // HAM+DPF e che sa già leggere i bit 6/7 del playfield trasparente; la
            // PRIORITÀ (e quindi lo z per gli sprite) resta decisa dai piani veri, perché
            // un playfield trasparente continua a coprire quello dietro.
            int pv  = deniseSpecials(s, bpu, true, plf1pri, plf2pri);
            byte colour = (byte) dualPlayfieldIndex(pv, pf2pri);

            if (idx1 != 0) {
                if (idx2 != 0) {
                    // Entrambi i playfield visibili: vince la priorità configurata
                    if (pf2pri) { mBuffer[i] = colour; zBuffer[i] = zpf2 | 0x05; }
                    else        { mBuffer[i] = colour; zBuffer[i] = zpf1 | 0x04; }
                } else {
                    mBuffer[i] = colour;              zBuffer[i] = zpf1 | 0x02;
                }
            } else {
                if (idx2 != 0) { mBuffer[i] = colour; zBuffer[i] = zpf2 | 0x03; }
                else           { mBuffer[i] = 0;      zBuffer[i] = 0x01; }
            }
        }
    }

    /**
     * Mappa il valore dei bit di priorità BPLCON2 PFxP al livello Z corrispondente.
     *
     * La scala usa "0 = priorità massima" (come i livelli interrupt HW):
     *   PFP = 0 → Z_0 (playfield davanti a TUTTI gli sprite)
     *   PFP = 1 → Z_1 (playfield davanti a SP2..SP7, dietro a SP0/SP1)
     *   PFP = 2 → Z_2 (playfield davanti a SP4..SP7, dietro a SP0..SP3)
     *   PFP = 3 → Z_3 (playfield davanti a SP6/SP7, dietro agli altri)
     *   PFP = 4 → Z_4 (playfield dietro a TUTTI gli sprite)
     *
     * Mapping confermato da vAmiga (DeniseRegs.cpp zPF) e comportamento
     * osservato su WinUAE: BPLCON2=0x0024 (PFP=4) → sprite 0 (cursore)
     * davanti al testo.
     */
    private static int zPF(int prio) {
        switch (prio) {
            case 0: return Z_0;
            case 1: return Z_1;
            case 2: return Z_2;
            case 3: return Z_3;
            case 4: return Z_4;
            default: return 0;
        }
    }

    // =========================================================================
    // Fase 4: drawSprites — scrive sprite su mBuffer/zBuffer
    // =========================================================================

    private void drawSprites() {
        // Riparti dallo stato EREDITATO dalla riga precedente: i write di questa riga
        // hanno già aggiornato i registri vivi, ma il replay dei sprChanges deve
        // applicarli ai loro trigger. Ripristinando lo snapshot di inizio riga il
        // segmento prima della prima change usa i valori vecchi (corretti) e il replay
        // riporta i registri allo stato di fine riga. Identico a colorize()/palette.
        armed = armedLineStart;
        System.arraycopy(sprposLineStart,  0, sprpos,  0, 8);
        System.arraycopy(sprctlLineStart,  0, sprctl,  0, 8);
        System.arraycopy(sprdataLineStart, 0, sprdata, 0, 8);
        System.arraycopy(sprdatbLineStart, 0, sprdatb, 0, 8);
        for (int i = 0; i < 8; i++) updateSprHCoords(i);

        for (int pair = 0; pair < 4; pair++) drawSpritePair(pair);
    }

    private void drawSpritePair(int pair) {
        int                  s1       = pair * 2;
        int                  s2       = pair * 2 + 1;
        RegChangeRecorder    recorder = sprChanges[pair];

        int     strt    = 0;
        int     strt1   = sprhppos[s1];
        int     strt2   = sprhppos[s2];
        // `armed` è già stato riportato allo stato d'inizio riga da drawSprites(); il
        // replay qui sotto lo rialza/riabbassa ai trigger e lo lascia, a fine ciclo,
        // allo stato di fine riga — che è quello che lo snapshot fotograferà per la
        // riga successiva. (Prima si lavorava su una copia locale tmpArmed, mai
        // riscritta: il valore di partenza era già quello di fine riga.)
        boolean a1      = (armed & (1 << s1)) != 0;
        boolean a2      = (armed & (1 << s2)) != 0;


        for (int recIdx = 0; recIdx < recorder.size(); ) {
            int trigger = recorder.keys[recIdx];
            renderSpriteChunk(strt, trigger, pair, strt1, strt2, a1, a2);
            strt = trigger;

            RegChange c    = recorder.elements[recIdx++];
            int       sIdx = (c.reg - 0x140) / 8;
            /*
             * Identifica il sotto-registro tramite l'offset nel blocco da 8 byte:
             *   offset & 6 == 0 → POS
             *   offset & 6 == 2 → CTL
             *   offset & 6 == 4 → DATA (A)
             *   offset & 6 == 6 → DATB (B)
             */
            int sub = c.reg & 6;
            if (sub == 4) {                          // DATA
                sprdata[sIdx] = c.value;
                armed |= (1 << sIdx);
            } else if (sub == 6) {                   // DATB
                sprdatb[sIdx] = c.value;
            } else if (sub == 0) {                   // POS
                sprpos[sIdx] = c.value;
                updateSprHCoords(sIdx);
                if (sIdx == s1) strt1 = sprhppos[s1]; else strt2 = sprhppos[s2];
            } else {                                 // CTL (sub == 2)
                sprctl[sIdx] = c.value;
                updateSprHCoords(sIdx);
                if (sIdx == s1) strt1 = sprhppos[s1]; else strt2 = sprhppos[s2];
                // CTL write disarms the sprite for the remainder of this scanline.
                // Matches vAmiga behavior: CLR_BIT(armed, sprite).
                armed &= ~(1 << sIdx);
            }
            a1 = (armed & (1 << s1)) != 0;
            a2 = (armed & (1 << s2)) != 0;
        }
        renderSpriteChunk(strt, HPIXELS, pair, strt1, strt2, a1, a2);
        recorder.clear();
    }

    private void renderSpriteChunk(int from, int to, int pair,
                                   int strt1, int strt2,
                                   boolean armed1, boolean armed2) {
        int     s1  = pair * 2;
        int     s2  = pair * 2 + 1;

        // PERF: quando entrambi gli shift register del pair sono vuoti, l'UNICO
        // effetto possibile del loop in [from,to) è l'arming di uno sprite (il
        // caricamento del registro alla sua posizione orizzontale strt). Saltiamo
        // direttamente al primo arming raggiungibile; se non ce n'è, il chunk non
        // produce nulla e si ritorna. Il profilo (caricamento di Agony) mostrava
        // renderSpriteChunk a ~8.5% perché scorreva l'INTERA scanline per ogni pair
        // ogni riga anche a sprite inattivi. La condizione di raggiungibilità
        // (parità di strt-from, dato lo step +2) replica ESATTAMENTE il loop
        // originale: nessun cambiamento di comportamento, solo iterazioni saltate.
        if ((ssra[s1] | ssrb[s1] | ssra[s2] | ssrb[s2]) == 0) {
            int next = Integer.MAX_VALUE;
            if (armed1 && strt1 >= from && strt1 < to && (((strt1 - from) & 1) == 0)) next = strt1;
            if (armed2 && strt2 >= from && strt2 < to && (((strt2 - from) & 1) == 0) && strt2 < next) next = strt2;
            if (next == Integer.MAX_VALUE) return;
            from = next;
        }

        boolean att = (sprctl[s2] & 0x80) != 0;

        for (int hpos = from; hpos < to; hpos += 2) {
            if (hpos == strt1 && armed1) { ssra[s1] = sprdata[s1]; ssrb[s1] = sprdatb[s1]; }
            if (hpos == strt2 && armed2) { ssra[s2] = sprdata[s2]; ssrb[s2] = sprdatb[s2]; }

            if ((ssra[s1] | ssrb[s1] | ssra[s2] | ssrb[s2]) != 0) {
                if (hpos >= spriteClipBegin && hpos < spriteClipEnd) {
                    if (att) drawAttachedSpritePixel(hpos, s2);
                    else   { drawSpritePixel(hpos, s1); drawSpritePixel(hpos, s2); }
                }
                // Shift dei registri sprite (1 bit per CCK = 2 sub-pixel in HPIXELS)
                ssra[s1] = (ssra[s1] << 1) & 0xFFFF; ssrb[s1] = (ssrb[s1] << 1) & 0xFFFF;
                ssra[s2] = (ssra[s2] << 1) & 0xFFFF; ssrb[s2] = (ssrb[s2] << 1) & 0xFFFF;
            }
        }
    }

    /** Sprite normale: 2 bit di colore (da ssrA MSB e ssrB MSB). */
    private void drawSpritePixel(int hpos, int x) {
        int col = ((ssra[x] >> 15) & 1) | ((ssrb[x] >> 14) & 2);
        if (col != 0) {
            int z    = Z_SP[x];
            int base = 16 + (x & 6) * 2;  // palette[16..19/20..23/24..27/28..31]
            for (int p = hpos; p <= hpos + 1 && p < zBuffer.length; p++) {
                if (z > zBuffer[p]) mBuffer[p] = (byte)(base | col);
                zBuffer[p] |= z;
            }
            spritePixelsThisFrame[x] += 2;
            spritesOnLineMask |= (1 << x);
        }
    }

    /**
     * Sprite attached: 4 bit di colore (due sprite abbinati, 15 colori + trasparente).
     *
     * FIX 3: la base della palette dipende dalla coppia sprite.
     *   coppia 0 (s0,s1) → palette[16..31]
     *   coppia 1 (s2,s3) → palette[20..35] (ma 32 è il max: palette[20..31] + wrap)
     *   ... correttamente: 16 + (x & 6) * 2 per x = sprite dispari (1,3,5,7)
     *   x=1 → 16+(0)*2=16, x=3 → 16+(2)*2=20, x=5 → 16+(4)*2=24, x=7 → 16+(6)*2=28
     *
     * FIX 4: la profondità Z per gli sprite attached è quella dello sprite pari
     *   (il "leader" della coppia, con Z maggiore = più in primo piano).
     *   Z_SP[x & ~1] restituisce Z_SP0, Z_SP2, Z_SP4, Z_SP6 per x=1,3,5,7.
     *
     * @param x  indice dello sprite DISPARI della coppia (1, 3, 5, 7)
     */
    private void drawAttachedSpritePixel(int hpos, int x) {
        // x è lo sprite dispari della coppia; x-1 è quello pari
        int col = ((ssra[x-1] >> 15) & 1) | ((ssrb[x-1] >> 14) & 2)
                | ((ssra[x]   >> 13) & 4) | ((ssrb[x]   >> 12) & 8);
        if (col != 0) {
            // FIX 4: usa la Z dello sprite pari (priorità più alta nella coppia)
            int z    = Z_SP[x & ~1];
            // Sprite ATTACHED: il codice colore a 4 bit indicizza DIRETTAMENTE i
            // registri 16-31 (un'unica tavolozza condivisa da tutte le coppie), quindi
            // la base è SEMPRE 16. NON va usato l'offset per-coppia 16+(x&6)*2 (quello
            // vale solo per gli sprite non-attached, a 2 bit, in drawSpritePixel): con
            // l'offset, su coppie diverse da 0+1 il codice 8-11 finiva nei colori 28-31
            // invece che 24-27 (es. la carne del protagonista di Last Battle, colore 27
            // EC8, diventava 31 842 → sprite arancione invece che incarnato). Cfr.
            // vAmiga Denise::drawAttachedSpritePixelPair (mBuffer = 0b10000 | col).
            int base = 16;
            for (int p = hpos; p <= hpos + 1 && p < zBuffer.length; p++) {
                if (z > zBuffer[p]) mBuffer[p] = (byte)(base | col);
                zBuffer[p] |= z;
            }
            spritePixelsThisFrame[x - 1] += 2;
            spritePixelsThisFrame[x]     += 2;
            spritesOnLineMask |= (1 << (x - 1)) | (1 << x);
        }
    }

    // =========================================================================
    // Fase 5: checkCollisions — aggiorna CLXDAT su zBuffer/dBuffer
    // =========================================================================

    private void checkCollisions() {
        // PERF (bit-identico): salta i loop di collisione che non possono produrre
        // alcun bit in clxdat. checkS2PCollisions(x) itererebbe tutta la linea solo
        // per fare 'continue' se lo sprite x non ha pixel; checkS2SCollisions non può
        // generare collisioni se meno di due coppie sprite sono presenti sulla linea.
        // I loop saltati non modificano clxdat → risultato identico.
        int m = spritesOnLineMask;
        if (m != 0) {
            int pairMask = ((m & 0x03) != 0 ? 1 : 0) | ((m & 0x0C) != 0 ? 2 : 0)
                         | ((m & 0x30) != 0 ? 4 : 0) | ((m & 0xC0) != 0 ? 8 : 0);
            if (Integer.bitCount(pairMask) >= 2) checkS2SCollisions();
            for (int i = 0; i < 8; i++) {
                if ((m & (1 << i)) != 0) checkS2PCollisions(i);
            }
        }
        checkP2PCollisions();
    }

    /**
     * Sprite-Sprite: controlla collisioni tra le 4 coppie sprite.
     *
     * FIX collisioni (HRM §7 / vAmiga Denise::checkS2SCollisions):
     *  - i bit CLXDAT sprite-sprite sono 9..14, NON 0..5 (il layout precedente
     *    era l'inverso di quello hardware);
     *  - gli sprite DISPARI partecipano solo se il rispettivo bit ENSPx di
     *    CLXCON (bit 12..15) è settato; i pari partecipano sempre.
     */
    private void checkS2SCollisions() {
        int z_s01 = Z_SP0 | ((clxcon & 0x1000) != 0 ? Z_SP1 : 0);
        int z_s23 = Z_SP2 | ((clxcon & 0x2000) != 0 ? Z_SP3 : 0);
        int z_s45 = Z_SP4 | ((clxcon & 0x4000) != 0 ? Z_SP5 : 0);
        int z_s67 = Z_SP6 | ((clxcon & 0x8000) != 0 ? Z_SP7 : 0);
        for (int pos = 0; pos < HPIXELS; pos++) {
            int z = zBuffer[pos];
            if ((z & z_s01) != 0 && (z & z_s23) != 0) clxdat |= 0x0200;  // bit 9
            if ((z & z_s01) != 0 && (z & z_s45) != 0) clxdat |= 0x0400;  // bit 10
            if ((z & z_s01) != 0 && (z & z_s67) != 0) clxdat |= 0x0800;  // bit 11
            if ((z & z_s23) != 0 && (z & z_s45) != 0) clxdat |= 0x1000;  // bit 12
            if ((z & z_s23) != 0 && (z & z_s67) != 0) clxdat |= 0x2000;  // bit 13
            if ((z & z_s45) != 0 && (z & z_s67) != 0) clxdat |= 0x4000;  // bit 14
        }
    }

    /**
     * Sprite-Playfield per lo sprite x.
     *
     * FIX 7: le maschere CLXCON per PF2 erano calcolate in modo errato.
     *
     * CLXCON (HW Ref Manual §7):
     *   bit 11:6 = ENBP6..ENBP1 — enable comparison per ogni piano (1=abilitato)
     *   bit  5:0 = MVBP6..MVBP1 — valore atteso per il confronto
     *
     * PF1 usa i piani dispari (bit 0,2,4 di dBuffer = BPL1,3,5) → maschera 0x15
     * PF2 usa i piani pari   (bit 1,3,5 di dBuffer = BPL2,4,6) → maschera 0x2A
     *
     * Un pixel di PF1 è "acceso" se:   (dBuffer & enabled1) == (match1 & enabled1)
     * Un pixel di PF2 è "acceso" se:   (dBuffer & enabled2) == (match2 & enabled2)
     *
     * Versione errata originale:
     *   enabled2 = (clxcon >> 7) & 0x15  → shift errato, maschera errata
     *   match2   = (clxcon >> 1) & 0x15  → shift errato, maschera errata
     */
    private void checkS2PCollisions(int x) {
        // Sprite dispari: partecipa solo se il bit ENSPx di CLXCON è settato
        // (vAmiga: if (IS_ODD(x)) if (!ensp<x>()) return).
        if ((x & 1) != 0 && (clxcon & (1 << (12 + (x >> 1)))) == 0) return;

        // FIX 7: maschere corrette per PF1 (piani dispari) e PF2 (piani pari).
        // FIX collisioni: il valore di match va mascherato con l'enable
        // (vAmiga: compare = mvbp() & enabled) — un bit MVBP settato su un
        // piano NON abilitato altrimenti rende il confronto impossibile.
        int enabled1 = (clxcon >> 6) & 0x15;   // ENBP per bit 0,2,4
        int enabled2 = (clxcon >> 6) & 0x2A;   // ENBP per bit 1,3,5
        int match1   =  clxcon       & 0x15 & enabled1;   // MVBP per bit 0,2,4
        int match2   =  clxcon       & 0x2A & enabled2;   // MVBP per bit 1,3,5

        for (int pos = 0; pos < HPIXELS; pos++) {
            if ((zBuffer[pos] & Z_SP[x]) == 0) continue;
            int d = dBuffer[pos] & 0xFF;
            // FIX collisioni: layout CLXDAT hardware (HRM §7):
            //   bit 1-4 = sprite coppia (x>>1) vs piani DISPARI (PF1)
            //   bit 5-8 = sprite coppia (x>>1) vs piani PARI    (PF2)
            // (il layout precedente, 6-9/10-13, era l'inverso di quello reale)
            if ((d & enabled2) == match2) {
                clxdat |= (1 << (5 + (x >> 1)));
            } else if ((zBuffer[pos] & 0x07) == 0) {
                // Stranezza hardware in single-playfield: se PF2 non matcha, nemmeno PF1
                // matcha. L'oracolo la discrimina su `bpldualpf` (drawing.cpp,
                // denise_collide_sprites: in DPF i due confronti sono PARALLELI, in SPF
                // quello di PF1 è ANNIDATO dentro quello di PF2); noi ci serve per-pixel,
                // perché il Copper commuta SPF/DPF DENTRO la riga.
                // ⛔ E il marcatore non è il bit 0: translateDPF scrive 0x01..0x05, e i
                // codici 0x02 (solo PF1 visibile) e 0x04 (entrambi, vince PF1) hanno il
                // bit 0 a ZERO. Leggendo solo `& 0x01` quei pixel passavano per
                // single-playfield e si prendevano l'annidamento che non gli spetta.
                // translateSPF non scrive mai i tre bit bassi e le costanti Z stanno
                // tutte da 0x0008 in su, quindi `& 0x07` è esattamente «reso in DPF».
                continue;
            }
            if ((d & enabled1) == match1) clxdat |= (1 << (1 + (x >> 1)));
        }
    }

    /**
     * Playfield1 vs Playfield2.
     *
     * FIX 8: stesse maschere CLXCON errate di checkS2PCollisions. Corretto.
     *
     * Un pixel appartiene a PF1 se i piani dispari abilitati corrispondono
     * al valore di match; analogamente per PF2 con i piani pari.
     * Una collisione PF1-PF2 avviene quando lo stesso pixel appartiene
     * contemporaneamente a entrambi i playfield.
     */
    private void checkP2PCollisions() {
        // FIX collisioni: la collisione playfield-playfield è il bit 0 di
        // CLXDAT (HRM §7), non il bit 14 (che è sprite 4/5 vs 6/7).
        if ((clxdat & 0x0001) != 0) return;  // già rilevata

        // FIX 8: maschere corrette per PF1 e PF2
        // FIX collisioni: match mascherato con l'enable (vAmiga: mvbp() & enabled)
        int enabled1 = (clxcon >> 6) & 0x15;
        int enabled2 = (clxcon >> 6) & 0x2A;
        int match1   =  clxcon       & 0x15 & enabled1;
        int match2   =  clxcon       & 0x2A & enabled2;

        for (int pos = 0; pos < HPIXELS; pos++) {
            int d = dBuffer[pos] & 0xFF;
            if ((d & enabled1) == match1 && (d & enabled2) == match2) {
                clxdat |= 0x0001;
                return;
            }
        }
    }

    // =========================================================================
    // Fase 6: colorize — traduce indici palette in RGB e scrive sul display
    // =========================================================================

    private void colorize(int vpos) {
        // Riparti dallo stato colore EREDITATO dalla riga precedente: i write di
        // questa riga hanno già aggiornato palette[] live, ma il replay dei
        // colChanges deve applicarli ai loro trigger. Ripristinando lo snapshot di
        // inizio riga, il segmento prima della prima change usa il colore vecchio
        // (corretto) e il replay riporta palette[] allo stato di fine riga.
        System.arraycopy(paletteLineStart, 0, palette, 0, 32);
        int     pixel   = 0;
        int     hold    = palette[0];
        boolean hamMode = isHamActive(initialBplcon0);
        // ⛔ Anche EHB segue BPLCON0 dentro la riga, esattamente come HAM. Prima veniva
        // deciso una volta sola da initialBplcon0 (lo snapshot di inizio riga): una riga
        // in cui il Copper porta BPU a 6 dopo l'inizio veniva colorata TUTTA a piena
        // luminosità, e quella è una riga sola per schermata — la prima del blocco a sei
        // piani di `BPLPTR/drop6x` e `dropcpu6x` (riga 22), $88F invece di $447.
        // Nell'oracolo `bplehb` è ricalcolato da `update_mode()` a ogni scrittura di
        // BPLCON0 e il decoder lo legge vivo (drawing.cpp:2791).
        boolean ehbMode = isEHB(initialBplcon0);
        // ⛔ Il bit DPF serve al decoder HAM ed è quello GREZZO, non quello della catena di
        // priorità: `decode_ham_pixel()` guarda `bpldualpf` (bit 10 di BPLCON0) anche quando
        // il modo è HAM. Vedi dualPlayfieldIndex().
        boolean hamDpf  = (initialBplcon0 & 0x0400) != 0;
        // ⚠️ PF2PRI dallo snapshot di inizio riga, come lo `zpf` di colorizeHAM: BPLCON2
        // cambia dentro la riga solo via conChanges, che qui non arrivano. Nessun test del
        // banco lo muove sotto HAM+DPF; il giorno che servisse, va portato in colChanges.
        boolean hamDpfPri = (initialBplcon2 & 0x0040) != 0;
        int     colIdx  = 0;

        while (colIdx < colChanges.size()) {
            int trigger = colChanges.keys[colIdx];
            if (hamMode) hold = colorizeHAM(vpos, pixel, trigger, hold, hamDpf, hamDpfPri);
            else              colorizeNormal(vpos, pixel, trigger, ehbMode);
            pixel = trigger;
            RegChange c = colChanges.elements[colIdx++];
            if      (c.reg == 0x100) {
                hamMode = isHamActive(c.value);
                ehbMode = isEHB(c.value);
                hamDpf  = (c.value & 0x0400) != 0;
            }
            else if (c.reg >= 0x180)  palette[(c.reg - 0x180) / 2] = c.value & 0xFFF;
        }
        if (hamMode) colorizeHAM(vpos, pixel, HPIXELS, hold, hamDpf, hamDpfPri);
        else         colorizeNormal(vpos, pixel, HPIXELS, ehbMode);

        colChanges.clear();
    }

    private void colorizeNormal(int vpos, int from, int to, boolean ehb) {
        for (int i = from; i < to && i < HPIXELS; i++) {
            // lineIsVBorder → riga senza output bitplane: tutta bordo (niente sprite)
            // bBuffer[i] == 0xFF → pixel interno al DIW → usa mBuffer (indice finale)
            // bBuffer[i] != 0xFF → pixel bordo → usa borderColor (indice 0)
            int cIdx  = lineIsVBorder ? (borderColor & 0x3F)
                      : (bBuffer[i] == (byte) 0xFF) ? (mBuffer[i] & 0x3F) : (bBuffer[i] & 0x3F);
            int color = palette[cIdx & 0x1F];
            if (ehb && (cIdx & 0x20) != 0) {
                // Extra-Half-Brite: luminosità dimezzata su tutti e tre i canali
                int r = ((color >> 8) & 0xF) >> 1;
                int g = ((color >> 4) & 0xF) >> 1;
                int b =  (color       & 0xF) >> 1;
                color = (r << 8) | (g << 4) | b;
            }
            rgbLine[i] = amigaToRGB(color);
        }
    }

    /**
     * Colorize in modalità HAM (Hold-And-Modify).
     *
     * In HAM i 6 bit del dBuffer vanno interpretati così:
     *   op  = dBuffer[5:4]  (00=palette, 01=modify-B, 10=modify-R, 11=modify-G)
     *   val = dBuffer[3:0]
     *
     * FIX 5: la maschera 0x5555 per rilevare sprite era errata.
     *   Sostituita con Z_ALL_SPRITES (0x6BB0).
     *   Aggiunto check corretto di priorità: uno sprite interrompe HAM
     *   solo se la sua Z è maggiore di quella del playfield corrente.
     *
     * FIX 6: nell'area bordo (bBuffer != 0xFF) il registro hold NON deve
     *   essere aggiornato. Sul vero hardware il bitplane non emette dati
     *   nel bordo, quindi l'operazione HAM non avviene e hold persiste.
     *   (Il colore mostrato è sempre palette[0], ma hold rimane inalterato.)
     *
     * @param hold  ultimo colore accumulato (12-bit 0x0RGB)
     * @return      nuovo valore di hold al termine del segmento
     */
    private int colorizeHAM(int vpos, int from, int to, int hold, boolean hamDpf,
                            boolean hamDpfPri) {
        // FIX 5: Z del playfield corrente. HAM è single-playfield: la priorità
        // verso gli sprite usa PF2P (bit 3-5), come translateSPF e vAmiga.
        int zpf = zPF((initialBplcon2 >> 3) & 7);

        for (int i = from; i < to && i < HPIXELS; i++) {
            if (lineIsVBorder || bBuffer[i] != (byte) 0xFF) {
                // FIX 6: area bordo (orizzontale o verticale) — mostra palette[0]
                // ma NON aggiornare hold. Sul vero hardware il bitplane non produce
                // output nel bordo, quindi l'operazione HAM non viene eseguita e
                // hold persiste.
                rgbLine[i] = amigaToRGB(palette[0]);
                continue;
            }

            // FIX 5: verifica se c'è uno sprite in primo piano rispetto al playfield.
            // Non basta rilevare la presenza di uno sprite (Z_ALL_SPRITES): lo sprite
            // deve avere Z maggiore della Z del playfield corrente.
            // Integer.highestOneBit(sprBits) è la Z del pixel sprite più prioritario.
            int sprBits = zBuffer[i] & Z_ALL_SPRITES;
            if (sprBits != 0 && Integer.highestOneBit(sprBits) > zpf) {
                // Lo sprite è visibile sopra il playfield: mostra il colore sprite
                // ma NON aggiornare hold (il registro HAM non viene toccato).
                rgbLine[i] = amigaToRGB(palette[mBuffer[i] & 0x1F]);
                continue;
            }

            // Processo HAM: decodifica op e val dai 6 bit grezzi del bitplane
            int s   = dBuffer[i] & 0x3F;
            int op  = (s >> 4) & 3;
            // ⛔ HAM + dual-playfield: l'operazione resta quella di HAM (bit 5:4), ma il
            // VALORE non è più `s & 0xF` — è l'indice risolto dalla PRIORITÀ dei due
            // playfield. Vedi dualPlayfieldIndex().
            int val = hamDpf ? (dualPlayfieldIndex(s, hamDpfPri) & 0xF) : (s & 0xF);
            int r   = (hold >> 8) & 0xF;
            int g   = (hold >> 4) & 0xF;
            int b   =  hold       & 0xF;
            switch (op) {
                // op 0 sceglie dalla palette: in HAM+DPF l'indice e' quello risolto, non `val`.
                case 0: hold = palette[(hamDpf ? dualPlayfieldIndex(s, hamDpfPri) : val) & 0x1F];
                        break;
                case 1: b = val; hold = (r<<8)|(g<<4)|b;    break; // modifica B
                case 2: r = val; hold = (r<<8)|(g<<4)|b;    break; // modifica R
                case 3: g = val; hold = (r<<8)|(g<<4)|b;    break; // modifica G
            }
            rgbLine[i] = amigaToRGB(hold);
        }
        return hold;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * STEP 9 (PERFORMANCE_REPORT_FINAL): LUT precalcolata per la conversione
     * colore Amiga 12-bit (0x0RGB) → RGB 24-bit.
     *
     * amigaToRGB() è chiamata per ogni pixel colorizzato; la versione originale
     * eseguiva 3 shift + 3 moltiplicazioni. La LUT (4096 × int = 16 KB, sempre in
     * cache L1/L2) sostituisce il calcolo con un singolo load. I valori sono
     * identici bit-per-bit alla formula originale (replica della nibble: 0xF→0xFF).
     */
    private static final int[] AMIGA_RGB_LUT = new int[4096];
    static {
        for (int c = 0; c < 4096; c++) {
            int r = (c >> 8) & 0xF;
            int g = (c >> 4) & 0xF;
            int b =  c       & 0xF;
            AMIGA_RGB_LUT[c] = ((r * 17) << 16) | ((g * 17) << 8) | (b * 17);
        }
    }

    /**
     * LUT dell'ORACOLO usata dai test di regressione vAmigaTS. I riferimenti
     * {@code .raw} sono rigenerati con WinUAE cycle-exact (vedi
     * {@code WinUAE-master/tools/winuae-regdump.sh}), la cui LUT e' stata forzata
     * alla stessa espansione banale del nibble usata da {@code Palette::RGB}:
     * {@code nibble<<4} (max 0xF0, NON 0xFF=nibble*0x11), senza gamma ne'
     * round-trip di colore.
     *
     * <p>Fino ai riferimenti generati da vAmiga qui c'era in piu' un round-trip
     * YUV→RGB la cui troncatura spostava di 1 LSB alcuni colori (es. $F0F →
     * F0,00,EF invece di F0,00,F0). Quella deformazione era un artefatto di
     * {@code PixelEngine::adjustRGB} e non esiste in WinUAE: rimossa con la
     * rigenerazione dei .raw. Attivata solo dai test via
     * {@link #useVAmigaColorLut}; i giochi usano la LUT standard. */
    private static final int[] VAMIGA_RGB_LUT = new int[4096];
    static {
        for (int c = 0; c < 4096; c++) {
            int r = (c >> 4) & 0xF0;   // nibble<<4
            int g = (c)      & 0xF0;
            int b = (c << 4) & 0xF0;
            VAMIGA_RGB_LUT[c] = (r << 16) | (g << 8) | b;
        }
    }

    /** Se true, {@link #amigaToRGB} usa la LUT esatta di vAmiga (test di
     *  regressione vAmigaTS). Default false: i giochi mantengono la LUT standard
     *  nibble*0x11 (nessuna regressione sul golden CRC). */
    public static volatile boolean useVAmigaColorLut = false;

    /** Converte il colore 12-bit Amiga (0x0RGB) in RGB a 24 bit (via LUT).
     *  Il mask & 0xFFF replica il comportamento originale (solo i 12 bit bassi
     *  contribuiscono, gli altri venivano ignorati dagli shift). */
    private static int amigaToRGB(int c) {
        return (useVAmigaColorLut ? VAMIGA_RGB_LUT : AMIGA_RGB_LUT)[c & 0xFFF];
    }

    /**
     * Calcola la coordinata Y effettiva sul display tenendo conto dell'interlace.
     * Se LACE (bit 2 di bplcon0) è attivo, le scanline vengono raddoppiate
     * e il campo corrente (field 0 o 1) determina se si disegna sulle righe
     * pari o dispari.
     */

    /** HAM attivo se bit 11 (HOMOD) di BPLCON0 è impostato. */
    private static boolean isHAM(int bplcon0) {
        return (bplcon0 & 0x0800) != 0;
    }

    /**
     * HAM <b>davvero attivo</b>: il bit c'è <i>e</i> i piani sono più di quattro.
     *
     * <p>⛔ <b>Con quattro piani o meno HAM non è HAM: è una normale lettura di palette.</b>
     * L'oracolo lo decide in {@code select_lts()} ({@code drawing.cpp}), dopo aver già
     * scelto il modo:
     * <pre>
     * if (denise_max_planes &lt;= 4 &amp;&amp; bm == CMODE_HAM) {
     *     bm = CMODE_NORMAL;
     * }
     * </pre>
     * (e appena sopra, il gemello per l'Extra-Half-Brite con {@code <= 5}).
     *
     * <p>⚠️ Con HAM da solo la deroga è <b>invisibile</b>, ed è per questo che non era mai
     * emersa: con ≤ 4 piani i bit 5:4 del pixel sono zero, l'operazione HAM è sempre «scegli
     * dalla palette» e il risultato coincide con la lettura normale. Si vede solo quando
     * <b>c'è anche il DPF</b>, perché lì l'operazione 0 userebbe l'indice risolto dalla
     * priorità invece del pixel grezzo — e allora i due modi divergono.
     *
     * <p>⚠️ L'oracolo usa {@code denise_max_planes}, che è il <b>massimo raggiunto nella
     * riga</b> e non il valore corrente («if plane count decreases mid-scanline, old data in
     * higher plane count shifters must be still shifted out»). Qui si usa il conteggio
     * corrente: nessun test del banco cambia BPU dentro la riga sotto HAM. ⏸ Se un giorno
     * servisse, il massimo di riga va tenuto in un campo e azzerato all'hsync.
     */
    private static boolean isHamActive(int bplcon0) {
        return isHAM(bplcon0) && deniseBpu(bplcon0) > 4;
    }

    /**
     * Extra Half-Brite attivo quando BPU=6 e HAM=0.
     *
     * FIX 1: la versione precedente controllava il bit 7 di BPLCON0 (GAUD =
     * genlock audio enable), che NON ha relazione con EHB. Su OCS/ECS la
     * modalità EHB non ha un bit di abilitazione esplicito: si attiva
     * automaticamente quando si usano 6 piani senza HAM.
     * (Rif: HW Ref Manual §7, "Extra Half-Brite Mode")
     */
    private static boolean isEHB(int bplcon0) {
        final int planes = deniseBpu(bplcon0);
        return !isHAM(bplcon0)
            && !isDualPlayfield(bplcon0)
            && (planes == 6 || planes == 7);
    }

    /**
     * Dual-playfield — <b>e HAM non deve essere acceso</b>.
     *
     * <p>⛔ <b>I modi di Denise sono una CATENA DI PRIORITÀ, non tre bit indipendenti.</b>
     * L'oracolo la scrive in una riga sola ({@code setbplmode()}, {@code drawing.cpp}):
     * <pre>
     * if      (bplham)               bplmode_next = CMODE_HAM;
     * else if (bpldualpf)            bplmode_next = CMODE_DUALPF;
     * else if (bplehb &amp;&amp; bplehb_eke) bplmode_next = CMODE_EXTRAHB_ECS_KILLEHB;
     * else if (bplehb)               bplmode_next = CMODE_EXTRAHB;
     * else                           bplmode_next = CMODE_NORMAL;
     * </pre>
     * ⇒ <b>HAM vince su tutto</b>, il dual-playfield viene dopo, e l'Extra-Half-Brite si
     * accende solo se non c'è né l'uno né l'altro:
     * <pre>
     * bplehb = (denise_planes == 6 || (!aga_mode &amp;&amp; denise_planes == 7))
     *          &amp;&amp; !bplham &amp;&amp; !bpldualpf &amp;&amp; (!ecs_denise || !(bplcon2_denise &amp; 0x200));
     * </pre>
     *
     * <p>⚠️ Noi trattavamo i tre come indipendenti: {@code translate()} sceglieva il
     * dual-playfield dal solo bit 10 e {@code colorize()} decideva HAM per conto suo, così
     * con <b>HAM e DPF accesi insieme</b> — che i manuali non definiscono — risolvevamo le
     * priorità come dual-playfield e poi decodificavamo HAM sopra il risultato. È la classe
     * B del §16: {@code block3}, {@code block3m}, {@code modes3/3b/3c}, {@code modes4},
     * {@code invprio3}, cioè tutti e soli i test in cui BPLCON0 ha bit 11 e bit 10 alti.
     *
     * <p>⏸ Su ECS manca ancora il ramo {@code bplehb_eke} (KILLEHB, bit 9 di BPLCON2), che
     * qui non serve: l'harness monta {@code OcsDenise}.
     */
    private static boolean isDualPlayfield(int bplcon0) {
        return (bplcon0 & 0x0400) != 0 && !isHAM(bplcon0);
    }

    /**
     * L'indice di colore che il <b>dual-playfield</b> risolve per il pixel grezzo
     * {@code pv}: si separano i due playfield e vince quello che ha priorità, con l'altro
     * usato solo dove il primo è trasparente.
     *
     * <p>⛔ <b>Serve anche in HAM</b>, ed è il difetto che chiudeva la classe B del §16.
     * Con HAM e DPF accesi insieme l'operazione resta quella di HAM — la scelgono i bit 5:4
     * del pixel — ma il <b>valore</b> non è più {@code pv & 0xF}: è questo indice.
     * L'oracolo ha un ramo apposta in {@code decode_ham_pixel()} ({@code drawing.cpp}):
     * <pre>
     * } else {   /&#42; OCS/ECS mode HAM6 + DPF &#42;/
     *     int *lookup = bpldualpfpri ? dblpf_ind2 : dblpf_ind1;
     *     int idx = lookup[pv];
     *     switch (pv &amp; 0x30) {
     *         case 0x00: ham_lastcolor = color_regs_ecs[idx]; break;
     *         case 0x10: ham_lastcolor &amp;= 0xFF0; ham_lastcolor |= (idx &amp; 0xF); break;
     *         ...
     * </pre>
     * ⚠️ E il {@code bpldualpf} che lo guida è il bit <b>grezzo</b> di BPLCON0, non il modo
     * risolto da {@code setbplmode()}: lì HAM vince e il modo è {@code CMODE_HAM}, ma il
     * decoder guarda comunque il bit 10.
     *
     * <p>La tabella è {@code gen_pfield_tables()}, riprodotta qui alla lettera:
     * <pre>
     * plane1 = bit 0,2,4,6 del pixel   →  playfield 1 (piani dispari)
     * plane2 = bit 1,3,5,7 del pixel   →  playfield 2 (piani pari), +8 se non zero
     * plane1x = (pv &amp; 0x40) ? 0 : plane1     // "invalid value emulation" di BPLCON2
     * plane2x = (pv &amp; 0x80) ? 0 : plane2
     * dblpf_ind1[pv] = plane1 == 0 ? plane2x : plane1x;   // PF1 davanti
     * dblpf_ind2[pv] = plane2 == 0 ? plane1x : plane2x;   // PF2 davanti (PF2PRI)
     * </pre>
     *
     * <p>⚠️ <b>Verificato in aritmetica, non a occhio</b>: sul pixel {@code pv = $1F} di
     * {@code block3} la tabella dà {@code plane1 = 7}, {@code plane2 = 3+8 = 11},
     * {@code dblpf_ind1 = 7}; con l'operazione {@code $10} (modifica blu) il colore diventa
     * {@code 000070}, che è esattamente il pixel dell'oracolo dove noi davamo {@code 0000F0}.
     *
     * <p>⏸ I bit 6 e 7 — che l'oracolo usa per emulare i valori <b>non validi</b> di
     * priorità in BPLCON2 — qui sono sempre zero: il nostro {@code dBuffer} porta sei piani.
     * È materia della classe A ({@code invprio}), non di questa.
     */
    /**
     * Le due funzioni <b>non documentate</b> che Denise ha quando una priorità di playfield
     * in BPLCON2 vale <b>5 o più</b> — cioè punta a un playfield che non esiste.
     * L'oracolo le sceglie in {@code update_specials()} e le applica in
     * {@code decode_denise_specials()}, sul pixel <b>grezzo</b> e <b>dopo</b> il calcolo
     * delle collisioni:
     * <pre>
     * decode_specials = 0;
     * if (!aga_mode) {
     *   if ((bplmode == CMODE_NORMAL || bplmode == CMODE_EXTRAHB || ...)
     *       &amp;&amp; denise_planes &gt;= 5 &amp;&amp; plf2pri &gt;= 5)      decode_specials = 1;
     *   if (bplmode == CMODE_DUALPF &amp;&amp; (plf1pri &gt;= 5 || plf2pri &gt;= 5))
     *                                                    decode_specials = 2;
     * }
     * </pre>
     *
     * <p><b>1 — «SWIV»</b>, playfield singolo: con almeno 5 piani e PF2P ≥ 5, se il bit del
     * <b>piano 5</b> è acceso <i>tutti gli altri piani sono ignorati</i> e l'indice diventa
     * esattamente 16. L'oracolo la chiama come il gioco che la usa (il pannello punteggi di
     * SWIV).
     *
     * <p><b>2 — playfield TRASPARENTE</b>, dual-playfield: il playfield la cui priorità è
     * ≥ 5 mantiene la sua precedenza — continua a coprire l'altro — ma il suo colore diventa
     * COLOR00. Si segnala accendendo i bit 6 e 7 del pixel, che sono i bit «inutilizzati» dei
     * piani su OCS/ECS ed è esattamente per questo che
     * {@link #dualPlayfieldIndex(int, boolean)} li legge: {@code plane1x} e {@code plane2x}
     * diventano 0 mentre {@code plane1}/{@code plane2} restano non nulli, quindi la priorità
     * non cambia e il colore sì. (Il logo di Running Man / Scoopex.)
     *
     * @param pv       pixel grezzo a 6 bit
     * @param planes   piani che Denise sta componendo
     * @param dpf      true se il modo è dual-playfield
     * @param plf1pri  PF1P = {@code bplcon2 & 7}
     * @param plf2pri  PF2P = {@code (bplcon2 >> 3) & 7}
     */
    private static int deniseSpecials(int pv, int planes, boolean dpf,
                                      int plf1pri, int plf2pri) {
        if (dpf) {
            if (plf1pri >= 5 || plf2pri >= 5) {
                if (plf1pri >= 5 && (pv & 0x15) != 0) pv |= 0x40;
                if (plf2pri >= 5 && (pv & 0x2A) != 0) pv |= 0x80;
            }
            return pv;
        }
        // Playfield singolo: la regola «SWIV».
        if (planes >= 5 && plf2pri >= 5 && (pv & 0x10) != 0) return 0x10;
        return pv;
    }

    private static int dualPlayfieldIndex(int pv, boolean pf2pri) {
        int plane1 = ((pv >> 0) & 1) | ((pv >> 1) & 2) | ((pv >> 2) & 4) | ((pv >> 3) & 8);
        int plane2 = ((pv >> 1) & 1) | ((pv >> 2) & 2) | ((pv >> 3) & 4) | ((pv >> 4) & 8);
        if (plane2 > 0) plane2 += 8;
        int plane1x = (pv & 0x40) != 0 ? 0 : plane1;
        int plane2x = (pv & 0x80) != 0 ? 0 : plane2;
        return pf2pri ? (plane2 == 0 ? plane1x : plane2x)
                      : (plane1 == 0 ? plane2x : plane1x);
    }

    // =========================================================================
    // API pubblica aggiuntiva
    // =========================================================================

    /** Segnala al chip Agnus un evento light pen (BPLCON0 bit 3 = LPEN). */
    public void triggerLightPen() {
        if ((bplcon0 & 0x0008) != 0 && agnus != null) agnus.triggerLightPen();
    }

    // Disarmo esplicito (debug/harness). Tocca ANCHE lo snapshot d'inizio riga,
    // altrimenti drawSprites() lo riporterebbe indietro al prossimo hsync.
    public void disarmAllSprites()          { armed = 0; armedLineStart = 0; }
    public void disarmSprite(int idx)       {
        if (idx >= 0 && idx < 8) { armed &= ~(1 << idx); armedLineStart &= ~(1 << idx); }
    }
    public void setGamePort1(GamePort p)    { gamePort1 = p; }
    public void setGamePort2(GamePort p)    { gamePort2 = p; }
    public int  getBplcon0()                { return bplcon0; }
    public int  getBpu()                    { return bpu; }     // piani DISEGNATI, vedi deniseBpu()
    /** BPU grezzo di BPLCON0, senza nessuna clamp: è quello che Agnus filtra da sé. */
    public int  getRawBpu()                 { return (bplcon0 >> 12) & 7; }
    /** {@code true} se una word e' ancora da caricare negli shift register: vedi
     *  AbstractAgnus.handleBitplaneDrawEvent, dove e' il gate del disegno al posto di DMACON. */
    public boolean isBitplaneArmed()        { return armedOdd || armedEven; }

    public boolean isHires()                { return hires; }   // (bplcon0 & 0x8000)!=0, cached
    /** LACE (BPLCON0 bit 2): il display alterna due semiquadri sfalsati di mezza riga. */
    public boolean isInterlaced()           { return (bplcon0 & 0x0004) != 0; }
    public int[] getRgbLine()               { return rgbLine; }
    /**
     * RGB del COLOR00 (bordo) LIVE, usando la stessa LUT di {@link #amigaToRGB}. Serve
     * al bordo-destro deep-HBLANK (VirtualCRT): l'ultimo CCK visibile di una riga mostra
     * il COLOR00 al beam cck 0 della riga SEGUENTE (vAmiga Beam::pixel wrap, "belongs to
     * the previous line"). Campionato a inizio riga successiva (dopo l'eventuale write a cck 0).
     */
    public int  getLiveColor0Rgb()          { return amigaToRGB(palette[0]); }

    /**
     * True se alla colonna grezza {@code rawX} la riga <b>appena renderizzata</b> aveva
     * <b>playfield</b>: né bordo verticale né fuori dalla finestra DIW orizzontale.
     *
     * <p>Serve alla colonna di wrap del bordo destro — vedi
     * {@code VirtualCRT.saveRegressionScreenshot}. {@code bBuffer} porta la maschera di
     * bordo della riga che {@link #hsyncHandler(int)} ha appena chiuso, quindi va
     * interrogato <b>prima</b> che la riga successiva la ricalcoli.
     */
    public boolean isPlayfieldAt(int rawX) {
        return !lineIsVBorder && rawX >= 0 && rawX < bBuffer.length
                && bBuffer[rawX] == (byte) 0xFF;
    }
    public int  getPaletteColor(int idx)    { return palette[idx & 31]; }
    public int  getRawPixelColor(int rawX)  { return rawX >= 0 && rawX < HPIXELS ? rgbLine[rawX] : 0; }
    public int  getPixelColor(int rawX, int vpos) { return getRawPixelColor(rawX); }
    public int  getLastRenderedBplcon0()    { return lastRenderedBplcon0; }

    /**
     * True se la scanline appena renderizzata da {@link #hsyncHandler(int)} è
     * interamente bordo verticale (nessun output bitplane: {@code bpl1datWrittenThisLine}
     * era false), false se i bitplane hanno prodotto pixel. È l'analogo di
     * WinUAE {@code thisframe_*_drawn_line} (cfr. drawing.cpp finish_playfield_line):
     * VirtualCRT lo usa per inquadrare il display sull'estensione effettivamente
     * disegnata invece che sulla finestra DIW (che può essere aperta su tutto
     * l'overscan, es. It Came From The Desert: DIW [5,320) ma box [72,255]).
     */
    public boolean isLastLineVBorder()      { return lineIsVBorder; }

    /** Ritorna la coordinata x (in pixel raw) del bordo sinistro della Display Window. */
    public int getDiwHStartPixel()  { return (hstrt - HBLANK_MIN * 2) * 2; }

    /** Ritorna la coordinata x (in pixel raw) del bordo destro della Display Window. */
    public int getDiwHStopPixel()   { return (hstop - HBLANK_MIN * 2) * 2; }

    /**
     * Ritorna il numero di pixel resi dallo sprite {@code idx} nell'ultimo frame
     * completato (frame corrente escluso, conteggio resettato a ogni vsync).
     */
    public long getSpritePixelCountLastFrame(int idx) {
        return (idx >= 0 && idx < 8) ? spritePixelsLastFrame[idx] : 0;
    }

    /**
     * Ritorna il numero di pixel resi dallo sprite {@code idx} nel frame corrente
     * (ancora in corso, resettato all'inizio del frame).
     */
    public long getSpritePixelCountThisFrame(int idx) {
        return (idx >= 0 && idx < 8) ? spritePixelsThisFrame[idx] : 0;
    }
}

