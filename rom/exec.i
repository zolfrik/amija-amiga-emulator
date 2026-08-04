;   AmiJa ROM - offset delle strutture exec e collocazione in memoria
;   SPDX-License-Identifier: GPL-3.0-or-later
;
;   Gli offset vengono dai formati pubblicati negli Amiga ROM Kernel Reference
;   Manual: sono interfacce che il software Amiga esistente legge direttamente,
;   quindi vanno rispettati alla lettera perche' i giochi funzionino.
;
;   Gli indirizzi assoluti riproducono quelli misurati nel Kickstart 1.3 su una
;   macchina 512K chip + 512K slow: i giochi trovano SysBase, l'IORequest di
;   boot e il bootblock dove se li aspettano.

; --- struct Node ------------------------------------------------------------
LN_SUCC      equ 0
LN_PRED      equ 4
LN_TYPE      equ 8
LN_PRI       equ 9
LN_NAME      equ 10
LN_SIZE      equ 14

NT_TASK      equ 1
NT_INTERRUPT equ 2
NT_DEVICE    equ 3
NT_MSGPORT   equ 4
NT_MESSAGE   equ 5
NT_REPLYMSG  equ 7
NT_RESOURCE  equ 8
NT_LIBRARY   equ 9
NT_MEMORY    equ 10
NT_FONT      equ 12

; --- struct List ------------------------------------------------------------
LH_HEAD      equ 0
LH_TAIL      equ 4
LH_TAILPRED  equ 8
LH_TYPE      equ 12
LH_SIZE      equ 14

; --- struct Library ---------------------------------------------------------
LIB_FLAGS    equ 14
LIB_NEGSIZE  equ 16
LIB_POSSIZE  equ 18
LIB_VERSION  equ 20
LIB_REVISION equ 22
LIB_IDSTRING equ 24
LIB_SUM      equ 28
LIB_OPENCNT  equ 32
LIB_SIZE     equ 34

; --- struct ExecBase --------------------------------------------------------
SOFTVER      equ 34
LOWMEMCHKSUM equ 36
CHKBASE      equ 38
COLDCAPTURE  equ 42
COOLCAPTURE  equ 46
WARMCAPTURE  equ 50
SYSSTKUPPER  equ 54
SYSSTKLOWER  equ 58
MAXLOCMEM    equ 62
DEBUGENTRY   equ 66
DEBUGDATA    equ 70
ALERTDATA    equ 74
MAXEXTMEM    equ 78
CHKSUM       equ 82
INTVECTS     equ 84
THISTASK     equ 276
IDLECOUNT    equ 280
DISPCOUNT    equ 284
QUANTUM      equ 288
ELAPSED      equ 290
SYSFLAGS     equ 292
IDNESTCNT    equ 294
TDNESTCNT    equ 295
ATTNFLAGS    equ 296
ATTNRESCHED  equ 298
RESMODULES   equ 300
TASKTRAPCODE equ 304
TASKEXCEPTCODE equ 308
TASKEXITCODE equ 312
TASKSIGALLOC equ 316
TASKTRAPALLOC equ 320
MEMLIST      equ 322
RESOURCELIST equ 336
DEVICELIST   equ 350
INTRLIST     equ 364
LIBLIST      equ 378
PORTLIST     equ 392
TASKREADY    equ 406
TASKWAIT     equ 420
SOFTINTS     equ 434
LASTALERT    equ 514
VBLANKFREQ   equ 530
POWERFREQ    equ 531
SEMAPHORELIST equ 532
KICKMEMPTR   equ 546
KICKTAGPTR   equ 550
KICKCHECKSUM equ 554
EXECBASE_SIZE equ 588

; --- struct MemHeader -------------------------------------------------------
MH_ATTRIBUTES equ 14
MH_FIRST     equ 16
MH_LOWER     equ 20
MH_UPPER     equ 24
MH_FREE      equ 28
MH_SIZE      equ 32

MC_NEXT      equ 0
MC_BYTES     equ 4

MEMF_PUBLIC  equ $0001
MEMF_CHIP    equ $0002
MEMF_FAST    equ $0004
MEMF_CLEAR   equ $10000

; --- struct Task ------------------------------------------------------------
TC_FLAGS     equ 14
TC_STATE     equ 15
TC_IDNESTCNT equ 16
TC_TDNESTCNT equ 17
TC_SIGALLOC  equ 18
TC_SIGWAIT   equ 22
TC_SIGRECVD  equ 26
TC_SIGEXCEPT equ 30
TC_TRAPALLOC equ 34
TC_TRAPABLE  equ 36
TC_EXCEPTDATA equ 38
TC_EXCEPTCODE equ 42
TC_TRAPDATA  equ 46
TC_TRAPCODE  equ 50
TC_SPREG     equ 54
TC_SPLOWER   equ 58
TC_SPUPPER   equ 62
TC_SWITCH    equ 66
TC_LAUNCH    equ 70
TC_MEMENTRY  equ 74
TC_USERDATA  equ 88
TC_SIZE      equ 92

; --- struct MsgPort ---------------------------------------------------------
MP_FLAGS     equ 14
MP_SIGBIT    equ 15
MP_SIGTASK   equ 16
MP_MSGLIST   equ 20
MP_SIZE      equ 34

; --- struct IOStdReq --------------------------------------------------------
MN_REPLYPORT equ 14
MN_LENGTH    equ 18
IO_DEVICE    equ 20
IO_UNIT      equ 24
IO_COMMAND   equ 28
IO_FLAGS     equ 30
IO_ERROR     equ 31
IO_ACTUAL    equ 32
IO_LENGTH    equ 36
IO_DATA      equ 40
IO_OFFSET    equ 44
IO_SIZE      equ 48

CMD_READ     equ 2
CMD_WRITE    equ 3
TD_MOTOR     equ 9
TD_CHANGENUM equ 13
TD_CHANGESTATE equ 14
TD_PROTSTATUS equ 15

IOERR_NOCMD  equ -3

; --- collocazione in memoria, misurata dal Kickstart 1.3 --------------------
JUMPTABLE_ADDR  equ $00C00000
EXECBASE_ADDR   equ $00C00276
LVO_COUNT       equ 105                 ; -6 .. -630, coerente con LIB_NEGSIZE
SLOW_MEMHDR     equ $00C004C2
SLOW_MEM_LOWER  equ $00C004E8
; Il primo chunk libero deve stare SOPRA task, porta e IORequest, altrimenti
; la prima AllocMem li restituirebbe e il gioco si scriverebbe sopra
; l'IORequest che ha in A1.
SLOW_FREE       equ $00C03000
SYS_STK_LOWER   equ $00C7E800
SYS_STK_UPPER   equ $00C80000

CHIP_MEMHDR     equ $00000400
CHIP_MEM_LOWER  equ $00000420

BOOT_REPLYPORT  equ $00C01512
BOOT_IOREQ      equ $00C014E2
BOOT_TASK       equ $00C01570
USER_STACK      equ $00C014B6
TD_DEVICE       equ $00C03AA4
TD_UNIT         equ $00C04730

; --- variabili della ROM ----------------------------------------------------
; Una ROM non e' scrivibile: tutto cio' che il firmware deve ricordare vive
; qui, in chip RAM. Sta sotto CHIP_FREE, quindi AllocMem non lo assegnera' mai.
VARS            equ $00000500
varChipSize     equ VARS+0
varSlowSize     equ VARS+4
varCurCyl       equ VARS+8              ; cilindro sotto la testina, -1 = ignoto
varCurTrack     equ VARS+12             ; traccia in TRACK_BUFFER, -1 = nessuna
varPrbShadow    equ VARS+16             ; ultimo valore scritto su CIA-B PRB
varMotorOn      equ VARS+20             ; il motore di DF0 sta girando
varCurSide      equ VARS+24             ; faccia selezionata, -1 = ignota

; --- disposizione della chip RAM --------------------------------------------
; Il bootblock va dove lo mette il Kickstart 1.3. I buffer del trackdisk e i
; bitplane della schermata di cortesia condividono la stessa area: i due
; percorsi si escludono a vicenda, perche' lo splash parte solo dopo che il
; tentativo di boot da floppy e' fallito e i suoi buffer sono ormai morti.
BOOT_BUFFER     equ $00001558
CHIP_COPPER     equ $00001000
TRACK_BUFFER    equ $00002000           ; 11*512 traccia decodificata
MFM_BUFFER      equ $00003800           ; traccia grezza MFM
CHIP_BPL        equ $00002000           ; 3 bitplane da 320x256
FONT_DATA       equ $00009800           ; bitmap del font, 8 righe da 96 byte
FONT_CHARLOC    equ $00009B00           ; 96 coppie offset/larghezza
FONT_TEXTFONT   equ $00009C80           ; struct TextFont
CHIP_FREE       equ $0000A000           ; primo byte di chip RAM libera
