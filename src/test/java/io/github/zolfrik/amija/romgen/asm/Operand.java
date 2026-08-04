package io.github.zolfrik.amija.romgen.asm;

/**
 * Un operando 68000 gia' classificato per modo di indirizzamento.
 *
 * La lunghezza in byte di un operando dipende solo dalla <em>sintassi</em> (e,
 * per l'immediato, dalla size dell'istruzione): mai dal valore. E' quello che
 * permette all'assemblatore di calcolare tutti gli indirizzi in una passata
 * sola, senza punto fisso, e di risolvere i riferimenti in avanti nella
 * seconda.
 */
public final class Operand {

    public enum Kind {
        DREG,       // Dn
        AREG,       // An
        INDIRECT,   // (An)
        POSTINC,    // (An)+
        PREDEC,     // -(An)
        DISP,       // d16(An)
        INDEX,      // d8(An,Xn.s)
        ABS_W,      // xxx.w
        ABS_L,      // xxx.l
        PC_DISP,    // d16(PC)
        PC_INDEX,   // d8(PC,Xn.s)
        IMM,        // #xxx
        SR, CCR, USP,
        REGLIST     // per MOVEM
    }

    public final Kind kind;
    public final int reg;          // Dn/An coinvolto, o registro base
    public final String expr;      // espressione da valutare in passata 2
    public final int indexReg;     // 0-7 = D0-D7, 8-15 = A0-A7
    public final boolean indexLong;
    public final int regMask;      // per REGLIST

    private Operand(Kind kind, int reg, String expr, int indexReg, boolean indexLong, int regMask) {
        this.kind = kind;
        this.reg = reg;
        this.expr = expr;
        this.indexReg = indexReg;
        this.indexLong = indexLong;
        this.regMask = regMask;
    }

    public static Operand simple(Kind k, int reg)          { return new Operand(k, reg, null, 0, false, 0); }
    public static Operand withExpr(Kind k, int reg, String e) { return new Operand(k, reg, e, 0, false, 0); }
    public static Operand index(Kind k, int base, String e, int ix, boolean lng) {
        return new Operand(k, base, e, ix, lng, 0);
    }
    public static Operand regList(int mask)                 { return new Operand(Kind.REGLIST, 0, null, 0, false, mask); }

    /** Campo "mode" a 3 bit dell'effective address. */
    public int mode() {
        return switch (kind) {
            case DREG -> 0;
            case AREG -> 1;
            case INDIRECT -> 2;
            case POSTINC -> 3;
            case PREDEC -> 4;
            case DISP -> 5;
            case INDEX -> 6;
            case ABS_W, ABS_L, PC_DISP, PC_INDEX, IMM -> 7;
            default -> throw new IllegalStateException("operando senza effective address: " + kind);
        };
    }

    /** Campo "register" a 3 bit dell'effective address. */
    public int eaReg() {
        return switch (kind) {
            case DREG, AREG, INDIRECT, POSTINC, PREDEC, DISP, INDEX -> reg;
            case ABS_W -> 0;
            case ABS_L -> 1;
            case PC_DISP -> 2;
            case PC_INDEX -> 3;
            case IMM -> 4;
            default -> throw new IllegalStateException("operando senza effective address: " + kind);
        };
    }

    /** Byte di parole di estensione, nota la size dell'istruzione (1/2/4). */
    public int extensionBytes(int opSize) {
        return switch (kind) {
            case DISP, INDEX, ABS_W, PC_DISP, PC_INDEX -> 2;
            case ABS_L -> 4;
            case IMM -> (opSize == 4) ? 4 : 2;
            default -> 0;
        };
    }

    public boolean isMemory() {
        return switch (kind) {
            case DREG, AREG, IMM, SR, CCR, USP, REGLIST -> false;
            default -> true;
        };
    }

    /** Vero se il modo e' alterabile (scrivibile): esclude PC-relativo e immediato. */
    public boolean isAlterable() {
        return switch (kind) {
            case PC_DISP, PC_INDEX, IMM, SR, CCR, USP, REGLIST -> false;
            default -> true;
        };
    }

    @Override
    public String toString() {
        return kind + (expr != null ? "(" + expr + ")" : "") + (kind == Kind.DREG || kind == Kind.AREG ? "" + reg : "");
    }
}
