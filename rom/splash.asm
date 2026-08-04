;   AmiJa ROM - schermata di cortesia quando non c'e' niente da avviare
;   SPDX-License-Identifier: GPL-3.0-or-later

SPLASH_W    equ 320
SPLASH_H    equ 256
PLANES      equ 3
BPL_BYTES   equ (SPLASH_W/8)*SPLASH_H

; -----------------------------------------------------------------------------
; showSplash - logo, "AmiJa" e "press F12 for setup".
;
; L'immagine e' in ROM, ma Agnus sa leggere i bitplane solo dalla chip RAM:
; quindi va copiata. Formato del blob, generato da romgen/SplashImage: 8 word
; di palette, poi i tre bitplane non interlacciati uno dopo l'altro.
; -----------------------------------------------------------------------------
showSplash:
            movem.l d0/a0-a1,-(sp)
            lea     CUSTOM,a5
            lea     splashData,a0

            ; La palette e' fissa per tutto il quadro, quindi sta nei registri
            ; e non nella copper list.
            lea     COLOR00(a5),a1
            moveq   #7,d0
splPalette:
            move.w  (a0)+,(a1)+
            dbra    d0,splPalette

            lea     CHIP_BPL,a1
            move.w  #(PLANES*BPL_BYTES)/4-1,d0
splPlanes:
            move.l  (a0)+,(a1)+
            dbra    d0,splPlanes

            lea     copperList,a0
            lea     CHIP_COPPER,a1
            move.w  #(copperEnd-copperList)/2-1,d0
splCopper:
            move.w  (a0)+,(a1)+
            dbra    d0,splCopper

            move.l  #CHIP_COPPER,COP1LC(a5)
            move.w  d0,COPJMP1(a5)          ; strobe: fai ripartire il Copper
            move.w  #$8000|DMAF_MASTER|DMAF_RASTER|DMAF_COPPER,DMACON(a5)
            movem.l (sp)+,d0/a0-a1
            rts

; I puntatori ai bitplane sono costanti note a tempo di assemblaggio, quindi
; la lista non ha bisogno di patch a runtime.
copperList:
            dc.w    BPLCON0,$3200           ; 3 bitplane, uscita a colori
            dc.w    BPLCON1,$0000
            dc.w    BPL1MOD,$0000
            dc.w    BPL2MOD,$0000
            dc.w    DIWSTRT,$2C81
            dc.w    DIWSTOP,$2CC1
            dc.w    DDFSTRT,$0038
            dc.w    DDFSTOP,$00D0
            dc.w    BPL1PT+0,(CHIP_BPL)>>16
            dc.w    BPL1PT+2,(CHIP_BPL)&$FFFF
            dc.w    BPL1PT+4,(CHIP_BPL+BPL_BYTES)>>16
            dc.w    BPL1PT+6,(CHIP_BPL+BPL_BYTES)&$FFFF
            dc.w    BPL1PT+8,(CHIP_BPL+2*BPL_BYTES)>>16
            dc.w    BPL1PT+10,(CHIP_BPL+2*BPL_BYTES)&$FFFF
            dc.w    $FFFF,$FFFE
copperEnd:

            even
splashData:
            incbin  "splash.bin"
