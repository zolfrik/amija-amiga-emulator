;   AmiJa ROM - graphics.library minima
;   SPDX-License-Identifier: GPL-3.0-or-later
;
;   Non e' una graphics.library: e' quel tanto che serve ai giochi che, invece
;   di aprirla, se la prendono di soppiatto.
;
;   L'idioma e' questo:
;       movea.l $4.l,a6
;       movea.l $9C(a6),a6      ; ExecBase+156 = IntVects[6].iv_Data
;       jsr     -72(a6)         ; OpenFont
;       move.l  $22(a0),(a1)    ; tf_CharData
;   IntVects[6] e' il vettore dell'interrupt del blitter, e sotto Kickstart la
;   sua iv_Data e' proprio GfxBase: cosi' si ottiene la libreria senza
;   OpenLibrary. Poi si chiede il font di sistema solo per rubargli i glifi e
;   disegnare testo per conto proprio.
;
;   Percio' qui contano tre cose: che ExecBase+156 punti a una GfxBase
;   plausibile, che -72 ritorni una TextFont valida, e che tf_CharData stia in
;   CHIP RAM — i giochi ci blittano sopra, e il blitter non sa leggere la ROM.

GFXBASE_ADDR    equ $00C01E1E           ; dove lo lascia il Kickstart 1.3
GFX_LVO_COUNT   equ 109                 ; -6 .. -654
GFX_POSSIZE     equ 328

; --- struct TextFont --------------------------------------------------------
TF_YSIZE        equ 20
TF_STYLE        equ 22
TF_FLAGS        equ 23
TF_XSIZE        equ 24
TF_BASELINE     equ 26
TF_BOLDSMEAR    equ 28
TF_ACCESSORS    equ 30
TF_LOCHAR       equ 32
TF_HICHAR       equ 33
TF_CHARDATA     equ 34
TF_MODULO       equ 38
TF_CHARLOC      equ 40
TF_CHARSPACE    equ 44
TF_CHARKERN     equ 48
TF_SIZE         equ 52

FONT_LOCHAR     equ 32
FONT_HICHAR     equ 127
FONT_COUNT      equ FONT_HICHAR-FONT_LOCHAR+1
FONT_XSIZE      equ 8
FONT_YSIZE      equ 8
FONT_MODULO     equ FONT_COUNT*FONT_XSIZE/8
FONT_DATABYTES  equ FONT_MODULO*FONT_YSIZE

; -----------------------------------------------------------------------------
; buildGraphics - costruisce GfxBase, la sua tabella dei salti e il font.
; -----------------------------------------------------------------------------
buildGraphics:
            movem.l d0-d2/a0-a2,-(sp)

            ; --- azzera l'area di GfxBase ----------------------------------
            lea     GFXBASE_ADDR,a1
            move.w  #GFX_POSSIZE/4-1,d0
gfxZero:
            clr.l   (a1)+
            dbra    d0,gfxZero

            ; --- tabella dei salti, all'indietro da -6 ---------------------
            ; Tutte le entry vanno a gfxStub tranne quelle implementate: un
            ; gioco che ne chiama una non prevista ottiene zero invece di
            ; saltare nel nulla.
            lea     GFXBASE_ADDR,a1
            move.w  #GFX_LVO_COUNT-1,d0
gfxTable:
            subq.l  #6,a1
            move.w  #$4EF9,(a1)             ; JMP xxx.l
            move.l  #gfxStub,2(a1)
            dbra    d0,gfxTable

            lea     GFXBASE_ADDR,a1
            move.l  #gfxOpenFont,-72+2(a1)
            move.l  #gfxCloseFont,-78+2(a1)
            move.l  #gfxWaitBlit,-228+2(a1)

            ; --- struct Library --------------------------------------------
            move.b  #NT_LIBRARY,LN_TYPE(a1)
            move.l  #gfxName,LN_NAME(a1)
            move.w  #GFX_LVO_COUNT*6,LIB_NEGSIZE(a1)
            move.w  #GFX_POSSIZE,LIB_POSSIZE(a1)
            move.w  #34,LIB_VERSION(a1)
            move.w  #1,LIB_REVISION(a1)
            move.l  #gfxName,LIB_IDSTRING(a1)
            move.w  #1,LIB_OPENCNT(a1)

            lea     EXECBASE_ADDR,a6
            lea     LIBLIST(a6),a0
            bsr     addTail

            ; --- il gancio da cui i giochi la pescano -----------------------
            ; IntVects[6] e' il vettore del blitter: iv_Data DEVE essere
            ; GfxBase, perche' e' li' che vanno a cercarla.
            lea     INTVECTS+6*12(a6),a0
            move.l  #GFXBASE_ADDR,(a0)      ; iv_Data
            move.l  #irqStub,4(a0)          ; iv_Code
            clr.l   8(a0)                   ; iv_Node

            bsr     buildFont

            movem.l (sp)+,d0-d2/a0-a2
            rts

; -----------------------------------------------------------------------------
; buildFont - copia il font in chip RAM e monta la struct TextFont.
;
; La copia e' obbligatoria: i giochi blittano i glifi, e la DMA del blitter
; legge solo la chip RAM.
; -----------------------------------------------------------------------------
buildFont:
            movem.l d0/a0-a1,-(sp)

            lea     fontData,a0
            lea     FONT_DATA,a1
            move.w  #FONT_DATABYTES/2-1,d0
fontCopyData:
            move.w  (a0)+,(a1)+
            dbra    d0,fontCopyData

            lea     FONT_CHARLOC,a1
            move.w  #FONT_COUNT*4/2-1,d0
fontCopyLoc:
            move.w  (a0)+,(a1)+
            dbra    d0,fontCopyLoc

            lea     FONT_TEXTFONT,a1
            move.w  #TF_SIZE/2-1,d0
fontZero:
            clr.w   (a1)+
            dbra    d0,fontZero

            lea     FONT_TEXTFONT,a1
            move.b  #NT_FONT,LN_TYPE(a1)
            move.l  #fontName,LN_NAME(a1)
            move.w  #FONT_YSIZE,TF_YSIZE(a1)
            move.b  #0,TF_STYLE(a1)
            move.b  #$60,TF_FLAGS(a1)       ; FPF_DESIGNED | FPF_PROPORTIONAL=0
            move.w  #FONT_XSIZE,TF_XSIZE(a1)
            move.w  #FONT_YSIZE-2,TF_BASELINE(a1)
            move.w  #0,TF_BOLDSMEAR(a1)
            move.w  #1,TF_ACCESSORS(a1)
            move.b  #FONT_LOCHAR,TF_LOCHAR(a1)
            move.b  #FONT_HICHAR,TF_HICHAR(a1)
            move.l  #FONT_DATA,TF_CHARDATA(a1)
            move.w  #FONT_MODULO,TF_MODULO(a1)
            move.l  #FONT_CHARLOC,TF_CHARLOC(a1)
            clr.l   TF_CHARSPACE(a1)
            clr.l   TF_CHARKERN(a1)

            movem.l (sp)+,d0/a0-a1
            rts

; -----------------------------------------------------------------------------
; gfxOpenFont (-72) - ritorna sempre il font di sistema.
;
; La TextAttr in A0 viene ignorata: ne abbiamo uno solo, e chi chiede qui vuole
; i glifi, non una scelta tipografica.
; -----------------------------------------------------------------------------
gfxOpenFont:
            move.l  #FONT_TEXTFONT,d0
            rts

gfxCloseFont:
            moveq   #0,d0
            rts

; -----------------------------------------------------------------------------
; gfxWaitBlit (-228) - aspetta che il blitter abbia finito.
;
; Il primo BTST va scartato: sul 68000 la lettura di DMACONR puo' arrivare
; prima che BBUSY si sia alzato, e chi non lo sa vede il blit "gia' finito"
; quando invece non e' nemmeno partito.
; -----------------------------------------------------------------------------
gfxWaitBlit:
            btst    #6,CUSTOM+DMACONR       ; lettura da buttare
gfxWaitLoop:
            btst    #6,CUSTOM+DMACONR       ; bit 14 = BBUSY
            bne     gfxWaitLoop
            rts

gfxStub:
            moveq   #0,d0
            rts

            even
gfxName:    dc.b 'graphics.library',0
fontName:   dc.b 'topaz.font',0
            even

; Generato a build time da romgen/FontImage: bitmap dei glifi seguita dalla
; tabella CharLoc.
fontData:
            incbin  "font.bin"
