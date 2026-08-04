;   AmiJa ROM - exec: strutture di sistema e funzioni della tabella LVO
;   SPDX-License-Identifier: GPL-3.0-or-later
;
;   Costruisce da zero il mondo che un bootblock Amiga si aspetta di trovare:
;   la tabella dei salti a offset negativi, ExecBase, le liste di memoria, il
;   task corrente e l'IORequest di trackdisk gia' aperto.
;
;   Non e' un exec completo e non vuole esserlo: e' quanto basta perche' un
;   gioco su floppy parta e carichi le sue tracce. Le funzioni non
;   implementate esistono comunque nella tabella, e ritornano zero.

; =============================================================================
; Rilevazione della memoria
; =============================================================================

; -----------------------------------------------------------------------------
; sizeMemory - misura chip RAM e slow RAM, lasciandole in varChipSize/varSlowSize.
;
; La slow RAM va sondata con cautela: sopra la RAM installata la finestra
; $C00000-$D7FFFF espone i registri custom in mirror, quindi una scrittura
; seguita da rilettura "riesce" anche dove RAM non ce n'e'. Si scrivono percio'
; due valori diversi a due indirizzi dello stesso blocco e si rileggono
; entrambi: i registri custom non si comportano cosi'.
; -----------------------------------------------------------------------------
sizeMemory:
            movem.l d1-d2/a0-a1,-(sp)

            ; --- chip RAM ---------------------------------------------------
            ; Due prove per ogni taglia candidata, perche' oltre la RAM
            ; installata possono succedere due cose diverse: o l'immagine si
            ; specchia in basso, o non risponde nessuno. La prima si vede
            ; perche' cambia il valore in $100, la seconda perche' il valore
            ; scritto non si rilegge.
            move.l  #$00080000,d0           ; 512 KB e' il minimo di un A500
            lea     $00000100,a0
            move.l  #$C0FFEE01,(a0)
scanChip:
            cmp.l   #$00200000,d0           ; oltre 2 MB non c'e' chip RAM
            bcc     chipDone
            move.l  d0,d1
            add.l   #$00000100,d1
            move.l  d1,a1
            move.l  #$5EED5EED,(a1)
            cmp.l   #$5EED5EED,(a1)
            bne     chipDone                ; non si rilegge: RAM finita
            cmp.l   #$C0FFEE01,(a0)
            bne     chipDone                ; si e' specchiata: RAM finita
            add.l   d0,d0
            bra     scanChip
chipDone:
            move.l  d0,varChipSize

            ; --- slow RAM: blocchi da 256 KB da $C00000 ---------------------
            moveq   #0,d1
            lea     $00C00000,a0
scanSlow:
            cmp.l   #$00180000,d1           ; la finestra arriva a $D80000
            bcc     slowDone
            move.l  #$A5A5F00F,(a0)
            move.l  #$5A5A0FF0,4(a0)
            cmp.l   #$A5A5F00F,(a0)
            bne     slowDone
            cmp.l   #$5A5A0FF0,4(a0)
            bne     slowDone
            add.l   #$00040000,d1
            adda.l  #$00040000,a0
            bra     scanSlow
slowDone:
            move.l  d1,varSlowSize

            movem.l (sp)+,d1-d2/a0-a1
            rts

; =============================================================================
; Costruzione di ExecBase
; =============================================================================

; -----------------------------------------------------------------------------
; buildExecBase - tabella LVO, struct Library, campi e liste vuote.
; -----------------------------------------------------------------------------
buildExecBase:
            movem.l d0-d2/a0-a2,-(sp)

            ; --- azzera l'area di ExecBase ---------------------------------
            lea     EXECBASE_ADDR,a1
            move.w  #EXECBASE_SIZE/4-1,d0
zeroExec:
            clr.l   (a1)+
            dbra    d0,zeroExec

            ; --- tabella dei salti, all'indietro da -6 ---------------------
            lea     lvoTable,a0
            lea     EXECBASE_ADDR,a1
            move.w  #LVO_COUNT-1,d0
buildLvo:
            subq.l  #6,a1
            move.w  #$4EF9,(a1)             ; JMP xxx.l
            move.l  (a0)+,2(a1)
            dbra    d0,buildLvo


            lea     EXECBASE_ADDR,a6

            ; --- struct Library --------------------------------------------
            move.b  #NT_LIBRARY,LN_TYPE(a6)
            move.l  #execName,LN_NAME(a6)
            move.b  #$04,LIB_FLAGS(a6)      ; LIBF_CHANGED
            move.w  #LVO_COUNT*6,LIB_NEGSIZE(a6)
            move.w  #EXECBASE_SIZE,LIB_POSSIZE(a6)
            move.w  #34,LIB_VERSION(a6)     ; ci si dichiara compatibili con
            move.w  #2,LIB_REVISION(a6)     ; exec 34.2, cioe' Kickstart 1.3
            move.l  #execIdString,LIB_IDSTRING(a6)
            move.w  #2,LIB_OPENCNT(a6)

            ; --- campi scalari ---------------------------------------------
            move.l  #SYS_STK_UPPER,SYSSTKUPPER(a6)
            move.l  #SYS_STK_LOWER,SYSSTKLOWER(a6)
            move.l  varChipSize,MAXLOCMEM(a6)
            move.l  #BOOT_TASK,THISTASK(a6)
            move.w  #4,QUANTUM(a6)
            move.b  #$FF,IDNESTCNT(a6)      ; -1: interruzioni abilitate
            move.b  #$FF,TDNESTCNT(a6)      ; -1: task switching abilitato
            move.w  #0,ATTNFLAGS(a6)        ; 68000 nudo
            move.l  #$0000FFFF,TASKSIGALLOC(a6)
            move.w  #$8000,TASKTRAPALLOC(a6)
            move.b  #50,VBLANKFREQ(a6)
            move.b  #50,POWERFREQ(a6)

            move.l  #$00C00000,d0
            add.l   varSlowSize,d0
            move.l  d0,MAXEXTMEM(a6)

            ; --- liste, tutte inizializzate vuote --------------------------
            lea     MEMLIST(a6),a0
            moveq   #NT_MEMORY,d1
            bsr     newList
            lea     RESOURCELIST(a6),a0
            moveq   #NT_RESOURCE,d1
            bsr     newList
            lea     DEVICELIST(a6),a0
            moveq   #NT_DEVICE,d1
            bsr     newList
            lea     INTRLIST(a6),a0
            moveq   #NT_INTERRUPT,d1
            bsr     newList
            lea     LIBLIST(a6),a0
            moveq   #NT_LIBRARY,d1
            bsr     newList
            lea     PORTLIST(a6),a0
            moveq   #NT_MSGPORT,d1
            bsr     newList
            lea     TASKREADY(a6),a0
            moveq   #NT_TASK,d1
            bsr     newList
            lea     TASKWAIT(a6),a0
            moveq   #NT_TASK,d1
            bsr     newList
            lea     SEMAPHORELIST(a6),a0
            moveq   #15,d1
            bsr     newList

            ; exec.library e' il primo elemento della lista delle librerie.
            lea     LIBLIST(a6),a0
            move.l  a6,a1
            bsr     addTail

            ; SysBase in $4: e' li' che ogni programma Amiga lo cerca.
            move.l  a6,$00000004

            movem.l (sp)+,d0-d2/a0-a2
            rts

; -----------------------------------------------------------------------------
; newList - inizializza una struct List vuota.
; a0 = lista, d1 = tipo del nodo.
; -----------------------------------------------------------------------------
newList:
            move.l  a0,d0
            addq.l  #4,d0
            move.l  d0,LH_HEAD(a0)          ; head punta a tail
            clr.l   LH_TAIL(a0)
            move.l  a0,LH_TAILPRED(a0)      ; tailPred punta a head
            move.b  d1,LH_TYPE(a0)
            rts

; -----------------------------------------------------------------------------
; addTail - accoda un nodo a una lista. a0 = lista, a1 = nodo.
; -----------------------------------------------------------------------------
addTail:
            movem.l d0/a2,-(sp)
            move.l  a0,d0
            addq.l  #4,d0
            move.l  d0,LN_SUCC(a1)          ; succ = &lh_Tail
            move.l  LH_TAILPRED(a0),a2
            move.l  a2,LN_PRED(a1)          ; pred = vecchio tailPred
            move.l  a1,LN_SUCC(a2)          ; il vecchio ultimo punta a noi
            move.l  a1,LH_TAILPRED(a0)
            movem.l (sp)+,d0/a2
            rts

; =============================================================================
; Liste di memoria
; =============================================================================

; -----------------------------------------------------------------------------
; buildMemoryLists - crea i MemHeader di chip e slow RAM.
;
; Come nel Kickstart 1.3 la slow RAM ha priorita' 0 e la chip -10: cosi'
; AllocMem senza vincoli preferisce la slow, e la chip resta a chi la richiede
; esplicitamente perche' serve alla DMA.
; -----------------------------------------------------------------------------
buildMemoryLists:
            movem.l d0-d2/a0-a2,-(sp)
            lea     EXECBASE_ADDR,a6

            ; --- slow RAM, se presente -------------------------------------
            tst.l   varSlowSize
            beq     noSlowRam

            lea     SLOW_MEMHDR,a1
            move.b  #NT_MEMORY,LN_TYPE(a1)
            move.b  #0,LN_PRI(a1)
            move.l  #slowMemName,LN_NAME(a1)
            move.w  #MEMF_PUBLIC|MEMF_FAST,MH_ATTRIBUTES(a1)
            move.l  #SLOW_MEM_LOWER,MH_LOWER(a1)
            move.l  #SYS_STK_LOWER,MH_UPPER(a1)  ; sopra c'e' lo stack supervisore
            move.l  #SLOW_FREE,MH_FIRST(a1)
            move.l  #SYS_STK_LOWER-SLOW_FREE,d0
            move.l  d0,MH_FREE(a1)
            ; unico chunk libero, che copre tutto cio' che resta
            move.l  #SLOW_FREE,a2
            clr.l   MC_NEXT(a2)
            move.l  d0,MC_BYTES(a2)

            lea     MEMLIST(a6),a0
            bsr     addTail

noSlowRam:
            ; --- chip RAM ---------------------------------------------------
            lea     CHIP_MEMHDR,a1
            move.b  #NT_MEMORY,LN_TYPE(a1)
            move.b  #-10,LN_PRI(a1)
            move.l  #chipMemName,LN_NAME(a1)
            move.w  #MEMF_PUBLIC|MEMF_CHIP,MH_ATTRIBUTES(a1)
            move.l  #CHIP_MEM_LOWER,MH_LOWER(a1)
            move.l  varChipSize,MH_UPPER(a1)
            move.l  #CHIP_FREE,MH_FIRST(a1)
            move.l  varChipSize,d0
            sub.l   #CHIP_FREE,d0
            move.l  d0,MH_FREE(a1)
            move.l  #CHIP_FREE,a2
            clr.l   MC_NEXT(a2)
            move.l  d0,MC_BYTES(a2)

            lea     MEMLIST(a6),a0
            bsr     addTail

            movem.l (sp)+,d0-d2/a0-a2
            rts

; =============================================================================
; Oggetti di boot: task, porta di risposta, IORequest di trackdisk
; =============================================================================

buildBootObjects:
            movem.l d0-d1/a0-a2,-(sp)

            ; --- il task che "sta eseguendo" il boot ------------------------
            lea     BOOT_TASK,a0
            move.w  #TC_SIZE/2-1,d0
zeroTask:
            clr.w   (a0)+
            dbra    d0,zeroTask

            lea     BOOT_TASK,a0
            move.b  #NT_TASK,LN_TYPE(a0)
            move.b  #0,LN_PRI(a0)
            move.l  #bootTaskName,LN_NAME(a0)
            move.b  #2,TC_STATE(a0)              ; TS_RUN
            move.b  #$FF,TC_IDNESTCNT(a0)
            move.b  #$FF,TC_TDNESTCNT(a0)
            move.l  #$0000FFFF,TC_SIGALLOC(a0)
            move.l  #USER_STACK,TC_SPREG(a0)
            move.l  #USER_STACK,TC_SPUPPER(a0)
            move.l  #USER_STACK-$1000,TC_SPLOWER(a0)
            lea     TC_MEMENTRY(a0),a0
            moveq   #0,d1
            bsr     newList

            ; --- porta di risposta dell'IORequest --------------------------
            lea     BOOT_REPLYPORT,a0
            move.b  #NT_MSGPORT,LN_TYPE(a0)
            move.l  #bootPortName,LN_NAME(a0)
            move.b  #0,MP_FLAGS(a0)              ; PA_SIGNAL
            move.b  #0,MP_SIGBIT(a0)
            move.l  #BOOT_TASK,MP_SIGTASK(a0)
            lea     MP_MSGLIST(a0),a0
            moveq   #NT_MESSAGE,d1
            bsr     newList

            ; --- device trackdisk ------------------------------------------
            lea     TD_DEVICE,a1
            move.b  #NT_DEVICE,LN_TYPE(a1)
            move.b  #0,LN_PRI(a1)
            move.l  #trackdiskName,LN_NAME(a1)
            move.w  #36,LIB_NEGSIZE(a1)
            move.w  #168,LIB_POSSIZE(a1)
            move.w  #34,LIB_VERSION(a1)
            move.w  #1,LIB_REVISION(a1)
            move.l  #trackdiskName,LIB_IDSTRING(a1)
            move.w  #1,LIB_OPENCNT(a1)

            lea     EXECBASE_ADDR,a6
            lea     DEVICELIST(a6),a0
            bsr     addTail

            ; --- l'unita' 0, cioe' DF0 -------------------------------------
            lea     TD_UNIT,a0
            move.b  #NT_DEVICE,LN_TYPE(a0)
            move.l  #df0Name,LN_NAME(a0)

            ; --- l'IORequest che il bootblock riceve in A1 -----------------
            ; Lo si lascia nello stato in cui si trova dopo la lettura del
            ; bootblock: e' esattamente la richiesta che l'ha caricato.
            lea     BOOT_IOREQ,a0
            move.b  #NT_REPLYMSG,LN_TYPE(a0)
            move.l  #BOOT_REPLYPORT,MN_REPLYPORT(a0)
            move.w  #192,MN_LENGTH(a0)
            move.l  #TD_DEVICE,IO_DEVICE(a0)
            move.l  #TD_UNIT,IO_UNIT(a0)
            move.w  #CMD_READ,IO_COMMAND(a0)
            move.b  #0,IO_FLAGS(a0)
            move.b  #0,IO_ERROR(a0)
            move.l  #1024,IO_ACTUAL(a0)
            move.l  #1024,IO_LENGTH(a0)
            move.l  #BOOT_BUFFER,IO_DATA(a0)
            move.l  #0,IO_OFFSET(a0)

            movem.l (sp)+,d0-d1/a0-a2
            rts

; =============================================================================
; Vettori di eccezione
; =============================================================================

; -----------------------------------------------------------------------------
; buildVectors - riempie i vettori $000-$0FF.
;
; Tutti puntano a gestori che non fanno nulla di distruttivo: un gioco
; sostituisce quelli che gli servono, e gli altri non devono mandare la
; macchina in un ciclo di reset se scattano.
; -----------------------------------------------------------------------------
buildVectors:
            movem.l d0/a0-a1,-(sp)

            lea     $00000008,a0            ; $0 e $4 sono SSP e PC di reset
            move.w  #(256-8)/4-1,d0
fillVectors:
            move.l  #genericException,(a0)+
            dbra    d0,fillVectors

            ; TRAP #0 serve a exec.Supervisor per passare in modo supervisore.
            move.l  #trap0Handler,$00000080
            move.l  #trap1Handler,$00000084  ; exec.SuperState
            move.l  #trap2Handler,$00000088  ; exec.UserState

            ; Le sette interruzioni autovettorizzate tornano subito: il gioco
            ; le rimpiazzera' con le sue.
            lea     $00000064,a0
            moveq   #6,d0
fillIrq:
            move.l  #irqStub,(a0)+
            dbra    d0,fillIrq

            movem.l (sp)+,d0/a0-a1
            rts

genericException:
            rte

; -----------------------------------------------------------------------------
; irqStub - gestore predefinito delle sette interruzioni autovettorizzate.
;
; Deve fare l'acknowledge, non solo la RTE: se la richiesta resta pendente in
; INTREQ la linea rimane asserita e l'interruzione si riscatena all'istruzione
; successiva, all'infinito. Il programma principale avanzerebbe di un'istruzione
; per giro e sembrerebbe bloccato.
;
; Si azzerano solo le richieste attualmente abilitate: quelle disabilitate non
; ci riguardano e un gioco potrebbe usarle come flag da leggere.
; -----------------------------------------------------------------------------
irqStub:
            movem.l d0/a0,-(sp)
            lea     CUSTOM,a0
            move.w  INTREQR(a0),d0
            and.w   INTENAR(a0),d0
            move.w  d0,INTREQ(a0)           ; bit 15 a zero: azzera i bit alti
            movem.l (sp)+,d0/a0
            rte

trap0Handler:
            jsr     (a5)                    ; exec.Supervisor chiama tramite A5
            rte

; -----------------------------------------------------------------------------
; trap1Handler - il motore di exec.SuperState.
;
; Sul 68000 il bit S non si alza da modo utente: l'unico modo di tornare al
; chiamante gia' in supervisore e' rientrare con una RTE su un frame in cui il
; bit S e' stato acceso a mano. Da li' in poi il task usa il proprio stack
; utente come stack supervisore, che e' esattamente la semantica di SuperState;
; il vecchio SSP torna in d0 perche' UserState possa rimetterlo a posto.
; -----------------------------------------------------------------------------
trap1Handler:
            move.w  (sp)+,d1                ; SR del chiamante
            move.l  (sp)+,d2                ; indirizzo di ritorno
            btst    #13,d1                  ; era gia' in supervisore?
            bne     superAlready
            move.l  a7,d0                   ; SSP da restituire
            move.l  usp,a0
            move.l  a0,a7                   ; adotta lo stack utente
            bset    #13,d1                  ; il chiamante tornera' supervisore
            move.l  d2,-(sp)
            move.w  d1,-(sp)
            rte
superAlready:
            moveq   #0,d0                   ; niente da ripristinare
            move.l  d2,-(sp)
            move.w  d1,-(sp)
            rte

; -----------------------------------------------------------------------------
; trap2Handler - il motore di exec.UserState.
; d0 = SSP restituito a suo tempo da SuperState.
; -----------------------------------------------------------------------------
trap2Handler:
            move.w  (sp)+,d1                ; SR del chiamante
            move.l  (sp)+,d2                ; indirizzo di ritorno
            ; SuperState restituisce 0 quando era gia' in supervisore. Chi
            ; ripassa quello zero qui non vuole azzerare lo stack supervisore:
            ; senza questa guardia A7 diventerebbe 0 e la macchina morirebbe
            ; al primo push.
            tst.l   d0
            beq     userStateNoop
            move.l  a7,a0                   ; qui restera' lo stack utente
            move.l  d0,a7                   ; SSP vero ripristinato
            move.l  a0,usp
            bclr    #13,d1                  ; solo ora si torna in modo utente
userStateNoop:
            move.l  d2,-(sp)
            move.w  d1,-(sp)
            rte

; =============================================================================
; Funzioni di exec
; =============================================================================

; Ritorno neutro per tutto cio' che non e' implementato.
execStub:
            moveq   #0,d0
            rts

; --- Supervisor (-30): esegue (A5) in modo supervisore ----------------------
execSupervisor:
            trap    #0
            rts

; --- SuperState (-150) / UserState (-156) ---------------------------------
execSuperState:
            trap    #1
            rts

execUserState:
            trap    #2
            rts

; --- Disable (-120) / Enable (-126) -----------------------------------------
execDisable:
            move.w  #INTF_INTEN,CUSTOM+INTENA
            addq.b  #1,IDNESTCNT(a6)
            rts

execEnable:
            subq.b  #1,IDNESTCNT(a6)
            bge     enableDone
            move.w  #$8000|INTF_INTEN,CUSTOM+INTENA
enableDone:
            rts

; --- Forbid (-132) / Permit (-138) ------------------------------------------
execForbid:
            addq.b  #1,TDNESTCNT(a6)
            rts

execPermit:
            subq.b  #1,TDNESTCNT(a6)
            rts

; --- FindTask (-294): A1 = nome, 0 = il task corrente -----------------------
execFindTask:
            move.l  a1,d0
            bne     findTaskNamed
            move.l  THISTASK(a6),d0
            rts
findTaskNamed:
            moveq   #0,d0                   ; esiste un solo task
            rts

; --- SetIntVector (-162): D0 = numero, A1 = struct Interrupt ----------------
execSetIntVector:
            movem.l d1/a0,-(sp)
            move.l  d0,d1
            and.l   #$0F,d1
            mulu    #12,d1
            lea     INTVECTS(a6),a0
            adda.l  d1,a0
            move.l  (a0),d0                 ; ritorna il precedente
            move.l  a1,(a0)
            movem.l (sp)+,d1/a0
            rts

; --- TypeOfMem (-534): A1 = indirizzo --------------------------------------
execTypeOfMem:
            move.l  a1,d0
            cmp.l   varChipSize,d0
            bcc     typeNotChip
            moveq   #MEMF_PUBLIC|MEMF_CHIP,d0
            rts
typeNotChip:
            moveq   #MEMF_PUBLIC|MEMF_FAST,d0
            rts

; --- CopyMem (-624): A0 = sorgente, A1 = destinazione, D0 = byte ------------
execCopyMem:
            movem.l d0-d1/a0-a1,-(sp)
            move.l  d0,d1
            beq     copyDone
copyLoop:
            move.b  (a0)+,(a1)+
            subq.l  #1,d1
            bne     copyLoop
copyDone:
            movem.l (sp)+,d0-d1/a0-a1
            rts

; --- AllocMem (-198): D0 = byte, D1 = requisiti -----------------------------
;
; Allocatore a scorrimento sul primo chunk libero dell'header scelto. Non
; sostiene FreeMem, che qui e' un no-op: una ROM di avvio alloca poche cose e
; non le restituisce mai, e un allocatore completo non porterebbe alcun gioco
; ad avviarsi in piu'.
execAllocMem:
            movem.l d1-d4/a0-a2,-(sp)
            move.l  d0,d2                   ; d2 = dimensione richiesta
            beq     allocFail
            add.l   #7,d2
            and.l   #$FFFFFFF8,d2           ; arrotonda a 8 byte

            ; Scelta dell'header: chip se richiesta esplicitamente o se non
            ; c'e' slow RAM.
            btst    #1,d1                   ; MEMF_CHIP
            bne     allocFromChip
            tst.l   varSlowSize
            beq     allocFromChip
            lea     SLOW_MEMHDR,a1
            bra     allocHave
allocFromChip:
            lea     CHIP_MEMHDR,a1
allocHave:
            move.l  MH_FIRST(a1),d3
            beq     allocFail
            move.l  d3,a2
            move.l  MC_BYTES(a2),d4
            cmp.l   d2,d4
            bcs     allocFail               ; non ci sta

            ; Scorri l'inizio del chunk in avanti.
            sub.l   d2,d4
            move.l  d3,d0                   ; il blocco assegnato
            add.l   d2,d3
            move.l  d3,MH_FIRST(a1)
            move.l  d3,a2
            clr.l   MC_NEXT(a2)
            move.l  d4,MC_BYTES(a2)
            move.l  MH_FREE(a1),d4
            sub.l   d2,d4
            move.l  d4,MH_FREE(a1)

            ; MEMF_CLEAR: azzera il blocco.
            btst    #16,d1
            beq     allocDone
            move.l  d0,a2
            move.l  d2,d4
            lsr.l   #2,d4
            subq.l  #1,d4
allocClear:
            clr.l   (a2)+
            dbra    d4,allocClear
allocDone:
            movem.l (sp)+,d1-d4/a0-a2
            rts
allocFail:
            moveq   #0,d0
            movem.l (sp)+,d1-d4/a0-a2
            rts

; --- FreeMem (-210): non restituisce nulla ----------------------------------
execFreeMem:
            rts

; --- OpenLibrary (-552) / OldOpenLibrary (-408): A1 = nome ------------------
execOpenLibrary:
            movem.l d1/a0-a1,-(sp)
            move.l  a1,a0

            lea     execName,a1
            bsr     strEqual
            tst.l   d0
            bne     openLibExec

            ; graphics.library c'e' gia' costruita: chi la apre per la via
            ; ufficiale deve trovarla come chi la pesca da IntVects[6].
            ; strEqual preserva a0, quindi il nome richiesto e' ancora li'.
            lea     gfxName,a1
            bsr     strEqual
            tst.l   d0
            bne     openLibGfx

            movem.l (sp)+,d1/a0-a1
            moveq   #0,d0                   ; libreria sconosciuta
            rts
openLibExec:
            movem.l (sp)+,d1/a0-a1
            move.l  a6,d0
            rts
openLibGfx:
            movem.l (sp)+,d1/a0-a1
            move.l  #GFXBASE_ADDR,d0
            rts

execCloseLibrary:
            moveq   #0,d0
            rts

; --- OpenDevice (-444): A0 = nome, D0 = unita', A1 = IORequest --------------
execOpenDevice:
            movem.l d1/a0-a2,-(sp)
            move.l  a1,a2
            lea     trackdiskName,a1
            bsr     strEqual
            tst.l   d0
            beq     openDevFail
            move.l  #TD_DEVICE,IO_DEVICE(a2)
            move.l  #TD_UNIT,IO_UNIT(a2)
            move.b  #0,IO_ERROR(a2)
            movem.l (sp)+,d1/a0-a2
            moveq   #0,d0
            rts
openDevFail:
            move.b  #-1,IO_ERROR(a2)
            movem.l (sp)+,d1/a0-a2
            moveq   #-1,d0
            rts

execCloseDevice:
            moveq   #0,d0
            rts

; --- DoIO (-456) / SendIO (-462): A1 = IORequest ----------------------------
execDoIO:
            bsr     tdPerformIO
            rts

execSendIO:
            bsr     tdPerformIO
            rts

execWaitIO:
            moveq   #0,d0
            move.b  IO_ERROR(a1),d0
            ext.w   d0
            ext.l   d0
            rts

execCheckIO:
            move.l  a1,d0
            rts

execAbortIO:
            moveq   #0,d0
            rts

; -----------------------------------------------------------------------------
; strEqual - confronto di stringhe terminate da zero.
; a0, a1 = stringhe. d0 <> 0 se uguali. Distrugge d1.
; -----------------------------------------------------------------------------
strEqual:
            movem.l a0-a1,-(sp)
strLoop:
            move.b  (a0)+,d1
            cmp.b   (a1)+,d1
            bne     strDiffer
            tst.b   d1
            bne     strLoop
            movem.l (sp)+,a0-a1
            moveq   #1,d0
            rts
strDiffer:
            movem.l (sp)+,a0-a1
            moveq   #0,d0
            rts

; =============================================================================
; Dati
; =============================================================================

            even

execName:      dc.b 'exec.library',0
trackdiskName: dc.b 'trackdisk.device',0
df0Name:       dc.b 'DF0',0
slowMemName:   dc.b 'slow memory',0
chipMemName:   dc.b 'chip memory',0
bootTaskName:  dc.b 'AmiJa boot',0
bootPortName:  dc.b 'AmiJa boot port',0
execIdString:  dc.b 'AmiJa exec 34.2 (GPL)',13,10,0
            even

; -----------------------------------------------------------------------------
; La tabella dei 105 indirizzi della libreria exec, da LVO -6 a -630.
; L'elenco e' quello di exec 34: la sua lunghezza e' cio' che rende
; LIB_NEGSIZE pari a 630, e i giochi indirizzano per offset, non per nome.
; -----------------------------------------------------------------------------
lvoTable:
            dc.l execStub           ; -6   Open
            dc.l execStub           ; -12  Close
            dc.l execStub           ; -18  Expunge
            dc.l execStub           ; -24  ExtFunc
            dc.l execSupervisor     ; -30  Supervisor
            dc.l execStub           ; -36  ExitIntr
            dc.l execStub           ; -42  Schedule
            dc.l execStub           ; -48  Reschedule
            dc.l execStub           ; -54  Switch
            dc.l execStub           ; -60  Dispatch
            dc.l execStub           ; -66  Exception
            dc.l execStub           ; -72  InitCode
            dc.l execStub           ; -78  InitStruct
            dc.l execStub           ; -84  MakeLibrary
            dc.l execStub           ; -90  MakeFunctions
            dc.l execStub           ; -96  FindResident
            dc.l execStub           ; -102 InitResident
            dc.l execStub           ; -108 Alert
            dc.l execStub           ; -114 Debug
            dc.l execDisable        ; -120 Disable
            dc.l execEnable         ; -126 Enable
            dc.l execForbid         ; -132 Forbid
            dc.l execPermit         ; -138 Permit
            dc.l execStub           ; -144 SetSR
            dc.l execSuperState     ; -150 SuperState
            dc.l execUserState      ; -156 UserState
            dc.l execSetIntVector   ; -162 SetIntVector
            dc.l execStub           ; -168 AddIntServer
            dc.l execStub           ; -174 RemIntServer
            dc.l execStub           ; -180 Cause
            dc.l execStub           ; -186 Allocate
            dc.l execStub           ; -192 Deallocate
            dc.l execAllocMem       ; -198 AllocMem
            dc.l execStub           ; -204 AllocAbs
            dc.l execFreeMem        ; -210 FreeMem
            dc.l execStub           ; -216 AvailMem
            dc.l execStub           ; -222 AllocEntry
            dc.l execStub           ; -228 FreeEntry
            dc.l execStub           ; -234 Insert
            dc.l execStub           ; -240 AddHead
            dc.l execStub           ; -246 AddTail
            dc.l execStub           ; -252 Remove
            dc.l execStub           ; -258 RemHead
            dc.l execStub           ; -264 RemTail
            dc.l execStub           ; -270 Enqueue
            dc.l execStub           ; -276 FindName
            dc.l execStub           ; -282 AddTask
            dc.l execStub           ; -288 RemTask
            dc.l execFindTask       ; -294 FindTask
            dc.l execStub           ; -300 SetTaskPri
            dc.l execStub           ; -306 SetSignal
            dc.l execStub           ; -312 SetExcept
            dc.l execStub           ; -318 Wait
            dc.l execStub           ; -324 Signal
            dc.l execStub           ; -330 AllocSignal
            dc.l execStub           ; -336 FreeSignal
            dc.l execStub           ; -342 AllocTrap
            dc.l execStub           ; -348 FreeTrap
            dc.l execStub           ; -354 AddPort
            dc.l execStub           ; -360 RemPort
            dc.l execStub           ; -366 PutMsg
            dc.l execStub           ; -372 GetMsg
            dc.l execStub           ; -378 ReplyMsg
            dc.l execStub           ; -384 WaitPort
            dc.l execStub           ; -390 FindPort
            dc.l execStub           ; -396 AddLibrary
            dc.l execStub           ; -402 RemLibrary
            dc.l execOpenLibrary    ; -408 OldOpenLibrary
            dc.l execCloseLibrary   ; -414 CloseLibrary
            dc.l execStub           ; -420 SetFunction
            dc.l execStub           ; -426 SumLibrary
            dc.l execStub           ; -432 AddDevice
            dc.l execStub           ; -438 RemDevice
            dc.l execOpenDevice     ; -444 OpenDevice
            dc.l execCloseDevice    ; -450 CloseDevice
            dc.l execDoIO           ; -456 DoIO
            dc.l execSendIO         ; -462 SendIO
            dc.l execCheckIO        ; -468 CheckIO
            dc.l execWaitIO         ; -474 WaitIO
            dc.l execAbortIO        ; -480 AbortIO
            dc.l execStub           ; -486 AddResource
            dc.l execStub           ; -492 RemResource
            dc.l execStub           ; -498 OpenResource
            dc.l execStub           ; -504 RawIOInit
            dc.l execStub           ; -510 RawMayGetChar
            dc.l execStub           ; -516 RawPutChar
            dc.l execStub           ; -522 RawDoFmt
            dc.l execStub           ; -528 GetCC
            dc.l execTypeOfMem      ; -534 TypeOfMem
            dc.l execStub           ; -540 Procure
            dc.l execStub           ; -546 Vacate
            dc.l execOpenLibrary    ; -552 OpenLibrary
            dc.l execStub           ; -558 InitSemaphore
            dc.l execStub           ; -564 ObtainSemaphore
            dc.l execStub           ; -570 ReleaseSemaphore
            dc.l execStub           ; -576 AttemptSemaphore
            dc.l execStub           ; -582 ObtainSemaphoreList
            dc.l execStub           ; -588 ReleaseSemaphoreList
            dc.l execStub           ; -594 FindSemaphore
            dc.l execStub           ; -600 AddSemaphore
            dc.l execStub           ; -606 RemSemaphore
            dc.l execStub           ; -612 SumKickData
            dc.l execStub           ; -618 AddMemList
            dc.l execCopyMem        ; -624 CopyMem
            dc.l execStub           ; -630 CopyMemQuick
lvoTableEnd:
