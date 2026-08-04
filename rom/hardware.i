;   AmiJa ROM - registri hardware dell'Amiga
;   SPDX-License-Identifier: GPL-3.0-or-later
;
;   Indirizzi e bit dei chip custom e dei CIA, come documentati nell'Amiga
;   Hardware Reference Manual.

CUSTOM      equ $DFF000

; CIA-A risponde agli indirizzi dispari, CIA-B ai pari; i registri distano 256.
CIAAPRA     equ $BFE001
CIAAPRB     equ $BFE101
CIAADDRA    equ $BFE201
CIAADDRB    equ $BFE301
CIAAICR     equ $BFED01
CIAACRA     equ $BFEE01
CIAACRB     equ $BFEF01

CIABPRA     equ $BFD000
CIABPRB     equ $BFD100
CIABDDRA    equ $BFD200
CIABDDRB    equ $BFD300
CIABICR     equ $BFDD00
CIABCRA     equ $BFDE00
CIABCRB     equ $BFDF00

; --- registri custom, come offset da CUSTOM ---------------------------------
DMACONR     equ $002
DSKPT       equ $020
DSKLEN      equ $024
INTENAR     equ $01C
INTREQR     equ $01E
DSKSYNC     equ $07E
COP1LC      equ $080
COPJMP1     equ $088
DIWSTRT     equ $08E
DIWSTOP     equ $090
DDFSTRT     equ $092
DDFSTOP     equ $094
DMACON      equ $096
INTENA      equ $09A
INTREQ      equ $09C
ADKCON      equ $09E
BPLCON0     equ $100
BPLCON1     equ $102
BPLCON2     equ $104
BPL1MOD     equ $108
BPL2MOD     equ $10A
BPL1PT      equ $0E0
COLOR00     equ $180

; --- bit di DMACON ----------------------------------------------------------
DMAF_DISK    equ $0010
DMAF_SPRITE  equ $0020
DMAF_BLITTER equ $0040
DMAF_COPPER  equ $0080
DMAF_RASTER  equ $0100
DMAF_MASTER  equ $0200

; --- bit di INTENA / INTREQ -------------------------------------------------
INTF_TBE     equ $0001
INTF_DSKBLK  equ $0002
INTF_SOFTINT equ $0004
INTF_PORTS   equ $0008
INTF_COPER   equ $0010
INTF_VERTB   equ $0020
INTF_BLIT    equ $0040
INTF_RBF     equ $0800
INTF_DSKSYNC equ $1000
INTF_EXTER   equ $2000
INTF_INTEN   equ $4000

; --- bit di ADKCON ----------------------------------------------------------
ADK_FAST     equ $0100
ADK_MSBSYNC  equ $0200
ADK_WORDSYNC equ $0400
ADK_MFMPREC  equ $1000

; --- CIA-B porta B: linee del floppy ----------------------------------------
; Tutte attive basse tranne DIR. Bit 1 alto = verso l'esterno, cioe' verso la
; traccia 0.
CIAB_STEP    equ $01
CIAB_DIR     equ $02
CIAB_SIDE    equ $04
CIAB_SEL0    equ $08
CIAB_MTR     equ $80

; --- CIA-A porta A: stato del floppy (attivi bassi) -------------------------
CIAA_CHNG    equ 2
CIAA_WPRO    equ 3
CIAA_TK0     equ 4
CIAA_RDY     equ 5
