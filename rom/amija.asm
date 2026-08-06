;   AmiJa ROM - firmware di avvio per l'emulatore AmiJa
;   Copyright (C) 2026  zolfrik
;
;   This program is free software: you can redistribute it and/or modify
;   it under the terms of the GNU General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   This program is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;   GNU General Public License for more details.
;
;   You should have received a copy of the GNU General Public License
;   along with this program.  If not, see <https://www.gnu.org/licenses/>.
;
;   SPDX-License-Identifier: GPL-3.0-or-later
;
;   -------------------------------------------------------------------------
;   Questo file non contiene, ne' deriva da, alcun byte del Kickstart
;   Commodore. Le strutture dati che costruisce seguono i formati pubblicati
;   negli Amiga ROM Kernel Reference Manual: sono interfacce necessarie
;   all'interoperabilita' col software Amiga esistente, non espressione
;   creativa. Gli indirizzi a cui vengono collocate riproducono quelli del
;   Kickstart 1.3 perche' i giochi si aspettano di trovarcele.
;   -------------------------------------------------------------------------

            include "hardware.i"
            include "exec.i"

            org $F80000

; -----------------------------------------------------------------------------
; Vettori di reset: la CPU li legge da $0 e $4, perche' all'accensione
; l'overlay mappa la ROM in fondo alla memoria.
; -----------------------------------------------------------------------------
romStart:
            dc.l    $00080000               ; SSP iniziale, prima della sizing
            dc.l    reset                   ; PC di reset

; Tag di versione, nella forma "$VER:" con cui l'Amiga marca i binari. Sta
; subito dopo i vettori di reset, cioe' a $F80008, cosi' e' anche la prima cosa
; leggibile in un dump della ROM. Il file e' generato da romgen/RomVersion: la
; versione si cambia li', non qui.
            include "version.i"

; -----------------------------------------------------------------------------
; Reset: dallo spegnimento fino al salto nel bootblock del gioco.
; -----------------------------------------------------------------------------
reset:
            move.w  #$2700,sr
            lea     CUSTOM,a5

            ; Silenzia i chip prima di ogni altra cosa: dopo un reset a caldo
            ; DMA e interruzioni possono essere ancora accesi.
            move.w  #$7FFF,INTENA(a5)
            move.w  #$7FFF,INTREQ(a5)
            move.w  #$7FFF,DMACON(a5)
            move.w  #$7F00,ADKCON(a5)

            ; ⛔ Su una porta CIA si scrive PRIMA il dato e POI la direzione.
            ; All'accensione il registro dei dati vale $00 e i pin sono ingressi:
            ; nell'istante in cui la DDR li fa diventare uscite, il CIA mette sui
            ; pin quello $00. Su CIA-B porta B $00 vuol dire /SEL0-3 e /MTR tutti
            ; bassi, cioe' "tutti i drive selezionati col motore acceso": ogni
            ; drive aggancia il proprio latch del motore, e chi non viene piu'
            ; selezionato (DF1-DF3, che questa ROM non tocca) se lo tiene acceso
            ; per sempre — LED del drive fisso e piatto che gira a vuoto.
            ; Caricare prima il dato rende la transizione della DDR innocua.

            ; Togli l'overlay: da qui $000000 e' chip RAM, non ROM.
            ; CIA-A PRA bit 0 = /OVL, bit 1 = LED di alimentazione.
            move.b  #$FC,CIAAPRA
            move.b  #$03,CIAADDRA

            ; CIA-B porta B: segnali floppy a riposo, poi tutte uscite.
            move.b  #$FF,CIABPRB
            move.b  #$FF,CIABDDRB

            move.l  #$00080000,a7           ; stack provvisorio in chip RAM

            ; La ROM non e' scrivibile: le sue variabili vivono in chip RAM e
            ; vanno messe nello stato iniziale qui, non con una dc.l.
            clr.l   varChipSize
            clr.l   varSlowSize
            move.l  #-1,varCurCyl           ; posizione della testina ignota
            move.l  #-1,varCurTrack         ; nessuna traccia nel buffer
            move.b  #$FF,varPrbShadow       ; linee del floppy a riposo
            clr.l   varMotorOn              ; motore fermo
            move.l  #-1,varCurSide          ; faccia non ancora scelta

            bsr     sizeMemory              ; -> varChipSize, varSlowSize
            bsr     buildExecBase           ; tabella LVO, ExecBase, liste
            bsr     buildMemoryLists
            bsr     buildBootObjects        ; task, porta, IORequest
            bsr     buildGraphics           ; GfxBase, font di sistema
            bsr     buildVectors            ; vettori di eccezione

            bsr     bootFromFloppy
            tst.l   d0
            beq     noBootDisk

            bsr     handoff                 ; non ritorna

noBootDisk:
            ; Nessun drive, nessun disco, o bootblock non valido: mostra la
            ; schermata di cortesia con logo e istruzioni, e poi resta in
            ; ascolto del drive. Chi accende l'emulatore a mano vuota e infila
            ; un disco dopo si aspetta che parta da solo, senza passare da un
            ; reset: dallo splash in poi il firmware non e' fermo, sta
            ; aspettando un disco.
            bsr     showSplash
waitForDisk:
            bsr     waitDiskInserted        ; ritorna solo quando ce n'e' uno
            bsr     bootFromFloppy
            tst.l   d0
            beq     waitForDiskAgain
            bsr     handoff                 ; non ritorna
waitForDiskAgain:
            ; Disco illeggibile o senza bootblock valido: motore fermo e si
            ; torna ad aspettare. Il prossimo tentativo lo fara' il prossimo
            ; cambio di disco, non un ritentativo a vuoto su questo.
            bsr     motorOff
            bra     waitForDisk

; -----------------------------------------------------------------------------
; handoff - cede il controllo al bootblock.
;
; Il bootblock parte in modo utente con A1 sull'IORequest di trackdisk gia'
; aperto, A6 su SysBase e A4/A0 che delimitano il buffer. Il passaggio a modo
; utente si fa con una RTE su un frame costruito a mano: e' l'unico modo di
; cambiare il bit S e il PC nella stessa istruzione.
; -----------------------------------------------------------------------------
handoff:
            move.w  #$2700,sr

            ; Stato hardware finale, quello che il gioco si aspetta di trovare.
            lea     CUSTOM,a5
            move.w  #$7FFF,INTENA(a5)
            move.w  #$7FFF,INTREQ(a5)
            move.w  #$7FFF,DMACON(a5)
            move.w  #$7F00,ADKCON(a5)

            ; Il Copper viene riacceso qui sotto, e parte da COP1LC: se quello
            ; puntasse ancora alla lista della schermata di cortesia — o, sul
            ; percorso diretto, a $000000, dove c'e' la tabella dei vettori —
            ; rileggerebbe come istruzioni roba che non lo e', e riscriverebbe i
            ; registri video appena impostati. Una lista che finisce subito e'
            ; lo stato minimo sensato da consegnare al gioco, che comunque
            ; installera' la sua.
            lea     CHIP_COPPER,a0
            move.w  #$FFFF,(a0)+
            move.w  #$FFFE,(a0)+
            move.l  #CHIP_COPPER,COP1LC(a5)
            move.w  d0,COPJMP1(a5)          ; strobe: il valore non conta

            move.w  #$8000|ADK_MFMPREC|ADK_FAST,ADKCON(a5)
            move.w  #$8000|DMAF_MASTER|DMAF_COPPER|DMAF_BLITTER|DMAF_DISK,DMACON(a5)
            move.w  #$8000|INTF_INTEN|INTF_EXTER|INTF_VERTB|INTF_PORTS|INTF_SOFTINT,INTENA(a5)
            move.w  #$8000|INTF_DSKSYNC,INTREQ(a5)   ; lasciata pendente

            move.w  #$2C81,DIWSTRT(a5)
            move.w  #$F4C1,DIWSTOP(a5)
            move.w  #$0038,DDFSTRT(a5)
            move.w  #$00D0,DDFSTOP(a5)
            move.w  #$1200,BPLCON0(a5)
            move.w  #$0000,BPLCON1(a5)
            move.w  #$0024,BPLCON2(a5)
            move.w  #$0000,BPL1MOD(a5)
            move.w  #$0000,BPL2MOD(a5)

            ; Motore spento e linee del floppy a riposo: il gioco riaccendera'
            ; il drive per conto suo quando gli servira'.
            bsr     motorOff

            ; Stato finale dei CIA. Dato prima della direzione, come al reset.
            move.b  #$FC,CIAAPRA
            move.b  #$03,CIAADDRA
            move.b  #$00,CIAACRA
            move.b  #$08,CIAACRB            ; timer B in colpo singolo
            move.b  #$82,CIAAICR            ; abilita l'interruzione di timer B

            move.b  #$FF,CIABPRA
            move.b  #$C0,CIABDDRA
            move.b  #$FF,CIABPRB
            move.b  #$FF,CIABDDRB
            move.b  #$00,CIABCRA
            move.b  #$80,CIABCRB            ; scrittura sull'allarme del TOD
            move.b  #$84,CIABICR            ; abilita l'interruzione di FLAG

            ; Stack supervisore in cima alla slow RAM: dopo la RTE tornera'
            ; esattamente a SYS_STK_UPPER.
            move.l  #SYS_STK_UPPER,a7
            move.l  #BOOT_BUFFER+12,-(sp)   ; PC del frame
            move.w  #$0004,-(sp)            ; SR del frame: modo utente, Z alto

            move.l  #USER_STACK,a0
            move.l  a0,usp

            movem.l handoffRegs,d0-d7/a0-a6
            rte

; Valori dei registri all'ingresso del bootblock, nell'ordine di MOVEM:
; D0-D7 poi A0-A6.
handoffRegs:
            dc.l    $00000000               ; D0
            dc.l    $8000FFFF               ; D1
            dc.l    $00000001               ; D2
            dc.l    $00000000               ; D3
            dc.l    $00000000               ; D4
            dc.l    $00000000               ; D5
            dc.l    $FFFFFFFF               ; D6
            dc.l    $00000000               ; D7
            dc.l    BOOT_BUFFER+1024        ; A0 = fine del bootblock
            dc.l    BOOT_IOREQ              ; A1 = IORequest di trackdisk
            dc.l    $FFFFFFFF               ; A2
            dc.l    romStart                ; A3 = un puntatore in ROM
            dc.l    BOOT_BUFFER             ; A4 = inizio del bootblock
            dc.l    USER_STACK              ; A5
            dc.l    EXECBASE_ADDR           ; A6 = SysBase

            include "exec.asm"
            include "graphics.asm"
            include "floppy.asm"
            include "splash.asm"

            end
