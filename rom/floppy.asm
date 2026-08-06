;   AmiJa ROM - trackdisk: motore, posizionamento, DMA MFM, decodifica
;   SPDX-License-Identifier: GPL-3.0-or-later
;
;   Legge il floppy come fa l'hardware Amiga: la DMA disco riversa in chip RAM
;   il flusso MFM grezzo di un giro intero di traccia, e la decodifica avviene
;   dopo, in software, ritrovando i settori dalle loro parole di sincronismo.

SECTORS     equ 11
SECTOR_SIZE equ 512
CYLINDERS   equ 80
TRACKS      equ CYLINDERS*2          ; cilindro*2 + faccia, come trackdisk
TRACK_BYTES equ SECTORS*SECTOR_SIZE
MFM_WORDS   equ $1A00                ; oltre un giro completo: garantisce 11 settori
POLL_MS     equ 3000                 ; intervallo fra due sondaggi del drive vuoto

; =============================================================================
; Avvio da floppy
; =============================================================================

; -----------------------------------------------------------------------------
; bootFromFloppy - carica i 1024 byte del bootblock da DF0 e ne verifica il
; checksum. Ritorna d0 <> 0 se BOOT_BUFFER contiene un bootblock valido.
; -----------------------------------------------------------------------------
bootFromFloppy:
            movem.l d1-d7/a0-a4,-(sp)

            bsr     motorOn
            tst.l   d0
            beq     bootFail                ; nessun drive pronto

            moveq   #0,d0                   ; traccia 0 = cilindro 0, faccia 0
            bsr     readTrackNum
            tst.l   d0
            beq     bootFail

            ; I settori 0 e 1 sono il bootblock: vanno dove il Kickstart 1.3
            ; li lascerebbe.
            lea     TRACK_BUFFER,a0
            lea     BOOT_BUFFER,a1
            move.w  #(2*SECTOR_SIZE)/4-1,d1
bootCopy:
            move.l  (a0)+,(a1)+
            dbra    d1,bootCopy

            bsr     bootChecksum
            tst.l   d0
            beq     bootFail

            moveq   #1,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts

bootFail:
            bsr     motorOff
            moveq   #0,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts

; -----------------------------------------------------------------------------
; bootChecksum - verifica il checksum del bootblock in BOOT_BUFFER.
;
; Somma a 32 bit dei 256 long con riporto rientrante: il totale deve essere
; $FFFFFFFF. Ritorna d0 <> 0 se il bootblock e' valido.
; -----------------------------------------------------------------------------
bootChecksum:
            movem.l d1-d3/a0,-(sp)
            lea     BOOT_BUFFER,a0
            moveq   #0,d1
            move.w  #(2*SECTOR_SIZE)/4-1,d2
csumLoop:
            move.l  (a0)+,d3
            add.l   d3,d1
            bcc     csumNoCarry
            addq.l  #1,d1                   ; riporto rientrante
csumNoCarry:
            dbra    d2,csumLoop

            moveq   #0,d0
            addq.l  #1,d1                   ; $FFFFFFFF + 1 = 0
            bne     csumDone
            moveq   #1,d0
csumDone:
            movem.l (sp)+,d1-d3/a0
            rts

; =============================================================================
; Motore e posizionamento
; =============================================================================

; -----------------------------------------------------------------------------
; motorOn - accende il motore di DF0 e attende che il drive sia pronto.
;
; Il motore si aggancia sul fronte di discesa di /SEL0 mentre /MTR e' basso:
; per questo prima si porta /MTR basso con tutti i drive deselezionati, e solo
; dopo si seleziona DF0.
;
; Ritorna d0 <> 0 se /RDY e' diventato basso entro il timeout.
; -----------------------------------------------------------------------------
motorOn:
            ; Se gira gia', non toccare nulla: rifare la sequenza spegnerebbe
            ; il motore e costringerebbe a riaspettare tutta la rampa, cosa che
            ; a ogni DoIO renderebbe il caricamento lentissimo.
            tst.l   varMotorOn
            beq     motorNeedsStart
            moveq   #1,d0
            rts

motorNeedsStart:
            move.b  #$FF,CIABPRB            ; tutto a riposo
            move.b  #$7F,CIABPRB            ; /MTR basso, nessun drive scelto
            move.b  #$77,CIABPRB            ; /SEL0 basso: aggancia il motore
            move.b  #$77,varPrbShadow
            move.l  #-1,varCurSide          ; faccia da riselezionare

            move.w  #400,d1                 ; fino a ~400 ms di rampa
motorWait:
            btst    #CIAA_RDY,CIAAPRA       ; /RDY basso = drive pronto
            beq     motorReady
            moveq   #1,d0
            bsr     waitMs
            dbra    d1,motorWait
            clr.l   varMotorOn
            moveq   #0,d0                   ; timeout: nessun drive collegato
            rts
motorReady:
            move.l  #1,varMotorOn
            moveq   #1,d0
            rts

; -----------------------------------------------------------------------------
; motorOff - ferma il motore e deseleziona DF0.
; -----------------------------------------------------------------------------
motorOff:
            move.b  #$FF,CIABPRB
            move.b  #$FF,varPrbShadow
            clr.l   varMotorOn
            move.l  #-1,varCurSide
            rts

; =============================================================================
; Attesa di un disco
; =============================================================================

; -----------------------------------------------------------------------------
; waitDiskInserted - non ritorna finche' in DF0 non compare un disco.
;
; Il drive non ha una linea "c'e' un disco": ha /CHNG, un latch che va basso a
; ogni inserimento o estrazione e che un impulso di STEP rialza soltanto se un
; disco c'e' davvero (HRM: "it is reset by a STEP pulse when there is a disk in
; the drive"). Interrogarlo vuol dire quindi far battere la testina, ed e'
; esattamente il tic-tac che un Amiga vero fa quando lo si lascia a vuoto.
;
; Il motore resta fermo per tutta l'attesa: si accende solo quando c'e'
; qualcosa da leggere.
; -----------------------------------------------------------------------------
waitDiskInserted:
            movem.l d0-d1,-(sp)

            ; Seleziona DF0 lasciando /MTR alto. Il motore si aggancia solo sul
            ; fronte di discesa di /SEL mentre /MTR e' gia' basso, quindi cosi'
            ; il piatto non parte.
            move.b  #$F7,CIABPRB            ; /SEL0 basso, /MTR alto
            move.b  #$F7,varPrbShadow
            move.l  #-1,varCurSide          ; faccia da riselezionare
            moveq   #1,d0
            bsr     waitMs                  ; i segnali del drive si assestano

            ; La testina va parcheggiata sulla traccia 0: li' i passi verso
            ; l'esterno non muovono nulla, e il sondaggio puo' ripetersi per ore
            ; senza spostare il braccio ne' consumare il fondo corsa.
            bsr     seekTrack0

            ; La prima misura si butta via: serve solo ad azzerare il latch, che
            ; dopo l'accensione o un boot fallito e' quasi sempre basso. Senza,
            ; un disco gia' dentro e non avviabile verrebbe riprovato all'infinito.
            bsr     probeDisk
wdiLoop:
            ; Il sondaggio costa un colpo di testina, che si sente e si vede.
            ; Un Amiga vero lo fa due volte al secondo ed e' il suo tic-tac piu'
            ; noto; qui da' fastidio e basta, perche' nessuno resta sullo splash
            ; per lavorarci. Un colpo ogni tre secondi resta abbastanza reattivo
            ; da non far dubitare che il disco sia stato visto.
            move.l  #POLL_MS,d0
            bsr     waitMs
            bsr     probeDisk
            tst.l   d0
            beq     wdiLoop

            movem.l (sp)+,d0-d1
            rts

; -----------------------------------------------------------------------------
; probeDisk - c'e' un disco NUOVO in DF0? Ritorna d0 <> 0 se si'.
;
; Due letture di /CHNG con in mezzo un impulso di STEP:
;   alto prima                  -> nessuno ha toccato niente dall'ultima volta
;   basso prima, basso dopo     -> qualcosa e' cambiato, ma il drive e' vuoto
;   basso prima, alto dopo      -> c'e' un disco, ed e' arrivato adesso
; Guardare anche il livello PRIMA dello step e' quel che fa vedere il cambio di
; disco: dopo un'estrazione seguita da un inserimento il latch e' basso una
; volta sola, e la sola lettura dopo lo step non distinguerebbe il disco nuovo
; da quello di prima.
;
; Vuole DF0 gia' selezionato, il motore fermo e la testina sulla traccia 0.
; -----------------------------------------------------------------------------
probeDisk:
            move.l  d1,-(sp)
            moveq   #0,d1
            btst    #CIAA_CHNG,CIAAPRA
            bne     probeDone               ; /CHNG alto: nessuna novita'

            ; Qualcosa e' cambiato: quello che resta nel buffer non vale piu'.
            move.l  #-1,varCurTrack

            move.b  varPrbShadow,d0
            or.b    #CIAB_DIR,d0            ; verso l'esterno: a fondo corsa
            bsr     stepPulse

            btst    #CIAA_CHNG,CIAAPRA
            beq     probeDone               ; ancora basso: il drive e' vuoto
            moveq   #1,d1
probeDone:
            move.l  d1,d0
            move.l  (sp)+,d1
            rts

; -----------------------------------------------------------------------------
; seekTrack0 - riporta la testina sulla traccia 0.
; Ritorna d0 <> 0 se /TK0 e' basso entro un numero di passi plausibile.
; -----------------------------------------------------------------------------
seekTrack0:
            movem.l d1-d2,-(sp)
            move.w  #90,d1                  ; piu' delle 80 tracce di un DD
seekHomeLoop:
            btst    #CIAA_TK0,CIAAPRA       ; /TK0 basso = ci siamo
            beq     seekHomeDone
            move.b  varPrbShadow,d0
            or.b    #CIAB_DIR,d0            ; DIR alto = verso l'esterno
            bsr     stepPulse
            dbra    d1,seekHomeLoop
            moveq   #0,d0
            movem.l (sp)+,d1-d2
            rts
seekHomeDone:
            moveq   #15,d0                  ; assestamento della testina
            bsr     waitMs
            clr.l   varCurCyl
            moveq   #1,d0
            movem.l (sp)+,d1-d2
            rts

; -----------------------------------------------------------------------------
; seekCylinder - porta la testina sul cilindro d1.
; -----------------------------------------------------------------------------
seekCylinder:
            movem.l d0-d3,-(sp)
            ; Il braccio non va oltre l'ultimo cilindro: un bersaglio assurdo
            ; diventerebbe un seek infinito.
            cmp.l   #CYLINDERS,d1
            bcs     seekTargetOk
            move.l  #CYLINDERS-1,d1
seekTargetOk:
            moveq   #0,d3                   ; nessun passo fatto finora
            move.l  varCurCyl,d2
            bmi     seekNeedsHome           ; posizione sconosciuta
seekCompare:
            ; Fra il confronto e il salto non ci va NIENTE che tocchi i flag:
            ; un moveq qui dentro li riscriveva, blt testava lui invece del
            ; cmp, e la testina andava sempre verso l'esterno all'infinito.
            cmp.l   d1,d2
            beq     seekArrived
            blt     seekInward
            moveq   #1,d3                   ; si e' mosso qualcosa
            move.b  varPrbShadow,d0
            or.b    #CIAB_DIR,d0            ; verso l'esterno
            bsr     stepPulse
            subq.l  #1,d2
            bra     seekCompare
seekInward:
            moveq   #1,d3
            move.b  varPrbShadow,d0
            and.b   #$FD,d0                 ; DIR basso: verso l'interno
            bsr     stepPulse
            addq.l  #1,d2
            bra     seekCompare
seekArrived:
            move.l  d2,varCurCyl
            ; L'assestamento serve solo se la testina si e' davvero mossa:
            ; pagarlo a ogni lettura raddoppierebbe i tempi di caricamento.
            tst.l   d3
            beq     seekNoSettle
            moveq   #15,d0
            bsr     waitMs
seekNoSettle:
            movem.l (sp)+,d0-d3
            rts
seekNeedsHome:
            bsr     seekTrack0
            moveq   #0,d2
            moveq   #1,d3
            bra     seekCompare

; -----------------------------------------------------------------------------
; stepPulse - un passo nella direzione gia' impostata in d0.
; d0 = valore da mettere su CIA-B PRB, con /STEP alto.
; -----------------------------------------------------------------------------
stepPulse:
            movem.l d0-d1,-(sp)
            or.b    #CIAB_STEP,d0           ; /STEP a riposo
            move.b  d0,varPrbShadow
            move.b  d0,CIABPRB
            and.b   #$FE,d0                 ; /STEP basso: impulso
            move.b  d0,CIABPRB
            or.b    #CIAB_STEP,d0
            move.b  d0,CIABPRB
            moveq   #3,d0                   ; il drive vuole ~3 ms per passo
            bsr     waitMs
            movem.l (sp)+,d0-d1
            rts

; =============================================================================
; Lettura
; =============================================================================

; -----------------------------------------------------------------------------
; readTrackNum - legge la traccia d0 (0..159) in TRACK_BUFFER.
;
; La traccia e' cilindro*2 + faccia, la numerazione usata da trackdisk.
; Ritorna d0 <> 0 se tutti gli 11 settori sono stati decodificati.
; -----------------------------------------------------------------------------
readTrackNum:
            movem.l d1-d7/a0-a4,-(sp)
            move.l  d0,d2                   ; d2 = traccia richiesta

            ; Se e' gia' quella nel buffer, non serve rileggere.
            cmp.l   varCurTrack,d2
            bne     readNeeded
            moveq   #1,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts

readNeeded:
            move.l  #-1,varCurTrack            ; il buffer non e' piu' valido

            move.l  d2,d1
            lsr.l   #1,d1                   ; cilindro
            bsr     seekCylinder

            ; Faccia: si tocca solo se e' cambiata davvero.
            move.l  d2,d1
            and.l   #1,d1
            cmp.l   varCurSide,d1
            beq     readSideReady
            move.b  varPrbShadow,d0
            tst.l   d1
            beq     readSideZero
            and.b   #$FB,d0                 ; /SIDE basso = faccia 1
            bra     readSideSet
readSideZero:
            or.b    #CIAB_SIDE,d0           ; /SIDE alto = faccia 0
readSideSet:
            move.b  d0,varPrbShadow
            move.b  d0,CIABPRB
            move.l  d1,varCurSide
            moveq   #3,d0                   ; il cambio faccia vuole un attimo
            bsr     waitMs
readSideReady:

            ; Fino a tre tentativi: la testina puo' arrivare a meta' settore.
            moveq   #2,d3
readRetry:
            move.l  d2,d0
            bsr     readTrackRaw
            tst.l   d0
            bne     readOk
            dbra    d3,readRetry
            moveq   #0,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts
readOk:
            move.l  d2,varCurTrack
            moveq   #1,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts

; -----------------------------------------------------------------------------
; readTrackRaw - un giro di DMA disco, poi decodifica.
; Ingresso: d0 = traccia attesa. Ritorna d0 <> 0 se gli 11 settori ci sono.
; -----------------------------------------------------------------------------
readTrackRaw:
            ; a5 va salvato: la convenzione Amiga vuole a2-a6 preservati, e il
            ; chiamante quasi sempre ci tiene $DFF000.
            movem.l d1-d7/a0-a3/a5,-(sp)
            move.l  d0,d7
            lea     CUSTOM,a5

            ; Sincronizzazione sulla parola $4489, celle da 2 us.
            move.w  #$4489,DSKSYNC(a5)
            move.w  #$7F00,ADKCON(a5)
            move.w  #$8000|ADK_WORDSYNC|ADK_FAST,ADKCON(a5)

            move.w  #$8000|DMAF_MASTER|DMAF_DISK,DMACON(a5)
            move.w  #INTF_DSKBLK,INTREQ(a5) ; pulisci un DSKBLK residuo

            move.l  #MFM_BUFFER,DSKPT(a5)
            move.w  #$4000,DSKLEN(a5)       ; ferma qualunque DMA in corso
            move.w  #$8000+MFM_WORDS,DSKLEN(a5)
            move.w  #$8000+MFM_WORDS,DSKLEN(a5)   ; va scritta due volte

            ; Attendi DSKBLK, con timeout: senza disco non arriva mai.
            move.w  #2000,d1
readWait:
            move.w  INTREQR(a5),d0
            btst    #1,d0
            bne     readGotBlock
            moveq   #1,d0
            bsr     waitMs
            dbra    d1,readWait

            move.w  #$4000,DSKLEN(a5)
            moveq   #0,d0
            movem.l (sp)+,d1-d7/a0-a3/a5
            rts

readGotBlock:
            move.w  #$4000,DSKLEN(a5)       ; ferma la DMA disco
            move.w  #INTF_DSKBLK,INTREQ(a5)

            move.l  d7,d0
            bsr     decodeTrack
            movem.l (sp)+,d1-d7/a0-a3/a5
            rts

; -----------------------------------------------------------------------------
; decodeTrack - estrae gli 11 settori dal buffer MFM grezzo.
;
; Ingresso: d0 = traccia attesa. Ritorna d0 <> 0 se ci sono tutti.
;
; Formato di un settore Amiga, dopo le due parole di sync $4489:
;   +0   long info (formato, traccia, settore, settori-a-fine-scrittura)
;   +8   16 byte di label
;   +40  checksum di header
;   +48  checksum dei dati
;   +56  512 byte di dati
; Ogni campo e' in MFM: prima tutti i bit dispari, poi tutti i pari.
; -----------------------------------------------------------------------------
decodeTrack:
            movem.l d1-d7/a0-a4,-(sp)
            move.l  d0,d7                   ; traccia attesa
            moveq   #0,d6                   ; maschera dei settori trovati

            lea     MFM_BUFFER,a0
            lea     MFM_BUFFER+2*MFM_WORDS-1200,a4   ; ultimo inizio utile

scanLoop:
            cmpa.l  a4,a0
            bcc     scanDone

            cmp.w   #$4489,(a0)
            bne     scanNext
            ; Salta le parole di sync consecutive: il settore comincia dopo.
scanSkipSync:
            addq.l  #2,a0
            cmpa.l  a4,a0
            bcc     scanDone
            cmp.w   #$4489,(a0)
            beq     scanSkipSync

            ; a0 punta alla long "info" codificata: dispari a +0, pari a +4.
            move.l  (a0),d0
            move.l  4(a0),d1
            bsr     mfmDecodeLong

            move.l  d0,d2
            lsr.l   #8,d2
            and.l   #$FF,d2                 ; d2 = settore
            move.l  d0,d3
            swap    d3                      ; i bit 16-23 diventano 0-7
            and.l   #$FF,d3                 ; d3 = traccia

            cmp.l   d7,d3
            bne     scanNext                ; traccia sbagliata: ignora
            cmp.w   #SECTORS,d2
            bcc     scanNext                ; numero di settore assurdo

            ; Gia' preso? Il buffer copre piu' di un giro: i settori tornano.
            move.l  d6,d4
            lsr.l   d2,d4
            btst    #0,d4
            bne     scanNext
            bset    d2,d6

            ; Destinazione = TRACK_BUFFER + settore*512
            move.l  d2,d4
            lsl.l   #8,d4                   ; lo shift immediato arriva a 8,
            add.l   d4,d4                   ; quindi 256 e poi il doppio
            lea     TRACK_BUFFER,a1
            adda.l  d4,a1

            ; I dati stanno a +56: 512 byte dispari, poi 512 byte pari.
            lea     56(a0),a2
            lea     56+SECTOR_SIZE(a0),a3
            move.w  #SECTOR_SIZE/4-1,d5
sectorCopy:
            move.l  (a2)+,d0
            move.l  (a3)+,d1
            bsr     mfmDecodeLong
            move.l  d0,(a1)+
            dbra    d5,sectorCopy

scanNext:
            addq.l  #2,a0
            bra     scanLoop

scanDone:
            and.l   #$7FF,d6                ; servono tutti e 11 i settori
            cmp.l   #$7FF,d6
            bne     decodeFail
            moveq   #1,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts
decodeFail:
            moveq   #0,d0
            movem.l (sp)+,d1-d7/a0-a4
            rts

; -----------------------------------------------------------------------------
; mfmDecodeLong - ricompone una long dai suoi bit dispari e pari.
; d0 = bit dispari, d1 = bit pari. Uscita in d0; d1 viene distrutta.
; -----------------------------------------------------------------------------
mfmDecodeLong:
            and.l   #$55555555,d0
            and.l   #$55555555,d1
            add.l   d0,d0                   ; i dispari valgono il bit alto
            or.l    d1,d0
            rts

; =============================================================================
; Il device: cosa fa DoIO
; =============================================================================

; -----------------------------------------------------------------------------
; tdPerformIO - esegue la richiesta puntata da A1.
;
; E' quello che un bootblock usa per caricare il resto del gioco: CMD_READ con
; un offset in byte dall'inizio del disco. I comandi che non riguardano il
; trasferimento dati vengono accettati senza fare nulla, che e' il
; comportamento utile per un loader.
; -----------------------------------------------------------------------------
tdPerformIO:
            movem.l d1-d7/a0-a3,-(sp)
            move.w  IO_COMMAND(a1),d0
            cmp.w   #CMD_READ,d0
            beq     tdRead

            clr.b   IO_ERROR(a1)            ; TD_MOTOR e simili: nulla da fare
            moveq   #0,d0
            bra     tdDone

tdRead:
            ; Il motore puo' essere fermo: all'handoff lo si lascia spento, e
            ; un loader che chiama DoIO non se ne occupa.
            bsr     motorOn
            tst.l   d0
            beq     tdReadErr

            move.l  IO_OFFSET(a1),d2
            move.l  IO_LENGTH(a1),d3
            move.l  IO_DATA(a1),a3
            clr.l   IO_ACTUAL(a1)

tdReadLoop:
            tst.l   d3
            beq     tdReadOk

            move.l  d2,d0
            divu    #TRACK_BYTES,d0
            move.l  d0,d4
            and.l   #$FFFF,d4               ; d4 = traccia
            clr.w   d0
            swap    d0
            move.l  d0,d5                   ; d5 = offset dentro la traccia

            ; Una traccia oltre la fine del disco e' un errore di I/O, non un
            ; invito a cercare all'infinito: senza questo controllo il seek
            ; continuerebbe a passi da 3 ms verso cilindri che non esistono, e
            ; la macchina sembrerebbe piantata dentro la ROM.
            cmp.l   #TRACKS,d4
            bcc     tdReadErr

            move.l  d4,d0
            bsr     readTrackNum
            tst.l   d0
            beq     tdReadErr

            move.l  #TRACK_BYTES,d6
            sub.l   d5,d6                   ; quanto resta di questa traccia
            cmp.l   d3,d6
            bcs     tdChunkOk
            move.l  d3,d6                   ; ne serve meno
tdChunkOk:
            lea     TRACK_BUFFER,a0
            adda.l  d5,a0
            move.l  d6,d7
            subq.l  #1,d7
tdCopy:
            move.b  (a0)+,(a3)+
            dbra    d7,tdCopy

            add.l   d6,d2
            sub.l   d6,d3
            add.l   d6,IO_ACTUAL(a1)
            bra     tdReadLoop

tdReadOk:
            clr.b   IO_ERROR(a1)
            moveq   #0,d0
            bra     tdDone
tdReadErr:
            move.b  #-1,IO_ERROR(a1)
            moveq   #-1,d0
tdDone:
            move.b  #NT_REPLYMSG,LN_TYPE(a1)
            movem.l (sp)+,d1-d7/a0-a3
            rts

; =============================================================================
; Utilita'
; =============================================================================

; -----------------------------------------------------------------------------
; waitMs - attesa approssimativa. Ingresso: d0 = millisecondi.
; -----------------------------------------------------------------------------
waitMs:
            movem.l d0-d1,-(sp)
wmOuter:
            move.w  #700,d1                 ; ~7000 cicli, circa 1 ms a 7,09 MHz
wmInner:
            dbra    d1,wmInner
            dbra    d0,wmOuter
            movem.l (sp)+,d0-d1
            rts

            even
            even
