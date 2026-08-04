package io.github.zolfrik.amija.romgen.asm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.zolfrik.amija.romgen.asm.Operand.Kind;

/**
 * Assemblatore Motorola 68000, sottoinsieme sufficiente a scrivere una ROM.
 *
 * Sulla macchina non c'e' ne' vasm ne' un compilatore C, quindi la toolchain
 * della ROM AmiJa e' questa classe. La scelta di scrivere un assemblatore di
 * testo invece di un emettitore di opcode in Java e' deliberata: il sorgente
 * della ROM resta un {@code .asm} leggibile e verificabile da un umano, che per
 * un progetto GPL e' meta' del valore.
 *
 * <p><b>Come fa a lavorare in due sole passate.</b> La lunghezza di ogni
 * istruzione dipende unicamente dalla sintassi degli operandi, mai dal valore
 * di un simbolo: gli indirizzi assoluti sono {@code .l} salvo suffisso
 * {@code .w} esplicito, e i branch sono {@code .w} salvo {@code .s} esplicito.
 * Quindi la prima passata puo' codificare tutto con i simboli ignoti a zero per
 * misurare gli indirizzi, e la seconda ricodifica con i valori veri. Le due
 * passate usano lo stesso identico encoder, e la lunghezza prodotta viene
 * riverificata: se divergesse, l'assemblatore lo direbbe invece di produrre un
 * binario sbagliato in silenzio.
 */
public final class Asm68k {

    /** Risultato dell'assemblaggio. */
    public record Image(byte[] data, int org, Map<String, Integer> symbols) {
        public int end() { return org + data.length; }
    }

    // Tabella delle condizioni per Bcc / DBcc / Scc.
    private static final Map<String, Integer> CC = new HashMap<>();
    static {
        String[] names = {"t","f","hi","ls","cc","cs","ne","eq","vc","vs","pl","mi","ge","lt","gt","le"};
        for (int i = 0; i < names.length; i++) CC.put(names[i], i);
        CC.put("hs", 4); // alias di CC
        CC.put("lo", 5); // alias di CS
    }

    /** Mappa dei simboli che, in prima passata, tratta gli ignoti come 0. */
    private static final class SymView extends HashMap<String, Integer> {
        boolean lenient;
        @Override public Integer get(Object k) {
            Integer v = super.get(k);
            if (v != null) return v;
            return lenient ? Integer.valueOf(0) : null;
        }
    }

    private record Src(int lineNo, String text) {}

    private static final class Stmt {
        String label, mnemonic, sizeSuffix;
        List<String> operands = new ArrayList<>();
        int lineNo;
        String raw;
        int address;
        int length;
    }

    private final SymView symbols = new SymView();
    private final List<Stmt> statements = new ArrayList<>();
    private final Path includeRoot;
    private int origin = -1;
    private int pc;

    private Asm68k(Path includeRoot) { this.includeRoot = includeRoot; }

    // =========================================================================
    // API
    // =========================================================================

    public static Image assembleFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return new Asm68k(file.toAbsolutePath().getParent()).run(lines, file.getFileName().toString());
    }

    public static Image assemble(List<String> lines) {
        return new Asm68k(Path.of(".")).run(lines, "<memoria>");
    }

    private Image run(List<String> lines, String name) {
        List<Src> src = new ArrayList<>();
        expand(lines, name, src, 0);
        for (Src s : src) {
            Stmt st = parseLine(s);
            if (st != null) statements.add(st);
        }

        // Passata 1: indirizzi e simboli, con gli ignoti a zero.
        symbols.lenient = true;
        layout(name, true);

        // Passata 2: codifica vera, gli ignoti sono errori.
        symbols.lenient = false;
        byte[] data = layout(name, false);

        return new Image(data, origin < 0 ? 0 : origin, new TreeMap<>(symbols));
    }

    private byte[] layout(String name, boolean firstPass) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pc = origin < 0 ? 0 : origin;
        int base = pc;
        for (Stmt st : statements) {
            try {
                if (st.label != null && !isEqu(st)) symbols.put(st.label, pc);
                st.address = pc;
                byte[] bytes = encode(st, out.size() + base);
                if (!firstPass && bytes.length != st.length) {
                    throw new AsmException("lunghezza instabile fra le passate: "
                            + st.length + " -> " + bytes.length);
                }
                st.length = bytes.length;
                // 'org' puo' spostare il pc in avanti: riempi il buco.
                int want = st.address - base;
                if (want > out.size()) out.write(new byte[want - out.size()], 0, want - out.size());
                out.writeBytes(bytes);
                pc = st.address + bytes.length;
            } catch (AsmException e) {
                throw new AsmException(name + ":" + st.lineNo + ": " + e.getMessage()
                        + "\n    " + st.raw.trim());
            }
        }
        return out.toByteArray();
    }

    /**
     * Espande ricorsivamente le direttive {@code include "file"}, cosi' che la
     * ROM possa essere scritta in piu' file invece che in un unico blocco.
     */
    private void expand(List<String> lines, String name, List<Src> out, int depth) {
        if (depth > 8) throw new AsmException("include annidati troppo in profondita': " + name);
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("include")) {
                String rest = t.substring(7).trim();
                if (rest.startsWith("\"") || rest.startsWith("'")) {
                    String file = rest.substring(1, rest.length() - 1);
                    try {
                        out.add(new Src(0, "; --- include " + file + " ---"));
                        expand(Files.readAllLines(includeRoot.resolve(file), StandardCharsets.UTF_8),
                                file, out, depth + 1);
                        continue;
                    } catch (IOException e) {
                        throw new AsmException("include non leggibile: " + file);
                    }
                }
            }
            out.add(new Src(i + 1, lines.get(i)));
        }
    }

    private boolean isEqu(Stmt st) {
        return st.mnemonic != null && (st.mnemonic.equals("equ") || st.mnemonic.equals("="));
    }

    // =========================================================================
    // Parsing di riga
    // =========================================================================

    private Stmt parseLine(Src s) {
        String line = s.text();
        int semi = indexOfComment(line);
        if (semi >= 0) line = line.substring(0, semi);
        if (line.isBlank()) return null;

        Stmt st = new Stmt();
        st.lineNo = s.lineNo();
        st.raw = s.text();

        // Etichetta: in colonna 0, oppure terminata da ':'. Una parola che e'
        // gia' un mnemonico non e' un'etichetta, altrimenti un'istruzione non
        // indentata diventerebbe silenziosamente una label.
        int i = 0;
        if (!Character.isWhitespace(line.charAt(0)) && !startsWithMnemonic(line)) {
            int end = 0;
            while (end < line.length() && !Character.isWhitespace(line.charAt(end)) && line.charAt(end) != ':') end++;
            st.label = line.substring(0, end);
            i = end;
            if (i < line.length() && line.charAt(i) == ':') i++;
        }

        String rest = line.substring(i).trim();
        if (rest.isEmpty()) {
            if (st.label == null) return null;
            st.mnemonic = null;
            return st;
        }

        int sp = 0;
        while (sp < rest.length() && !Character.isWhitespace(rest.charAt(sp))) sp++;
        String mn = rest.substring(0, sp);
        String args = rest.substring(sp).trim();

        int dot = mn.lastIndexOf('.');
        if (dot > 0 && dot == mn.length() - 2) {
            st.sizeSuffix = mn.substring(dot + 1).toLowerCase(Locale.ROOT);
            mn = mn.substring(0, dot);
        }
        st.mnemonic = mn.toLowerCase(Locale.ROOT);
        if (!args.isEmpty()) st.operands = splitTopLevel(args);
        return st;
    }

    private static final java.util.Set<String> MNEMONICS = java.util.Set.of(
            "org","equ","dc","ds","dcb","even","align","incbin","end",
            "move","movea","moveq","movem","lea","pea","link","unlk","exg","swap","ext",
            "add","adda","addi","addq","sub","suba","subi","subq","cmp","cmpa","cmpi","cmpm",
            "and","andi","or","ori","eor","eori","not","neg","negx","clr","tst","tas",
            "mulu","muls","divu","divs","asl","asr","lsl","lsr","rol","ror","roxl","roxr",
            "btst","bset","bclr","bchg","jmp","jsr","rts","rte","rtr","nop","reset","stop",
            "trap","trapv","illegal","bra","bsr");

    /** Vero se la riga comincia con un mnemonico o una direttiva, non con un'etichetta. */
    private static boolean startsWithMnemonic(String line) {
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end)) && line.charAt(end) != ':') end++;
        // Coi due punti e' un'etichetta senza ambiguita', anche se si chiama
        // come un'istruzione (p.es. 'reset:').
        if (end < line.length() && line.charAt(end) == ':') return false;
        String word = line.substring(0, end).toLowerCase(Locale.ROOT);
        int dot = word.lastIndexOf('.');
        if (dot > 0 && dot == word.length() - 2) word = word.substring(0, dot);
        if (MNEMONICS.contains(word)) return true;
        if (word.length() > 1 && (word.startsWith("b") || word.startsWith("s")) && CC.containsKey(word.substring(1))) return true;
        return word.startsWith("db") && (CC.containsKey(word.substring(2)) || word.equals("dbra"));
    }

    /** Indice del ';' che apre un commento, ignorando quelli dentro apici. */
    private static int indexOfComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if ((c == ';' || (c == '*' && i == 0)) && !quoted) return i;
        }
        return -1;
    }

    /** Divide su virgole di primo livello (fuori da parentesi e apici). */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') quoted = !quoted;
            if (!quoted) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) { out.add(cur.toString().trim()); cur.setLength(0); continue; }
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    // =========================================================================
    // Direttive
    // =========================================================================

    private byte[] encode(Stmt st, int here) {
        if (st.mnemonic == null) return new byte[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int sz = sizeOf(st, 2);

        switch (st.mnemonic) {
            case "org" -> {
                int v = val(st.operands.get(0));
                if (origin < 0) origin = v;
                st.address = v;
                pc = v;
                return new byte[0];
            }
            case "equ", "=" -> {
                if (st.label == null) throw new AsmException("equ senza etichetta");
                symbols.put(st.label, val(st.operands.get(0)));
                return new byte[0];
            }
            case "even" -> {
                if ((st.address & 1) != 0) o.write(0);
                return o.toByteArray();
            }
            case "align" -> {
                int a = val(st.operands.get(0));
                while (((st.address + o.size()) % a) != 0) o.write(0);
                return o.toByteArray();
            }
            case "dc" -> {
                for (String op : st.operands) {
                    String t = op.trim();
                    if (t.startsWith("'") && t.endsWith("'") && t.length() >= 2) {
                        byte[] b = t.substring(1, t.length() - 1).getBytes(StandardCharsets.ISO_8859_1);
                        o.writeBytes(b);
                    } else {
                        emitValue(o, val(t), sz);
                    }
                }
                return o.toByteArray();
            }
            case "ds" -> {
                int n = val(st.operands.get(0)) * sz;
                o.writeBytes(new byte[n]);
                return o.toByteArray();
            }
            case "dcb" -> {
                int n = val(st.operands.get(0));
                int v = st.operands.size() > 1 ? val(st.operands.get(1)) : 0;
                for (int k = 0; k < n; k++) emitValue(o, v, sz);
                return o.toByteArray();
            }
            case "incbin" -> {
                String f = st.operands.get(0).replaceAll("^['\"]|['\"]$", "");
                try {
                    o.writeBytes(Files.readAllBytes(includeRoot.resolve(f)));
                } catch (IOException e) {
                    throw new AsmException("incbin non leggibile: " + f);
                }
                return o.toByteArray();
            }
            case "end" -> { return new byte[0]; }
            default -> { return encodeInstruction(st, here); }
        }
    }

    private static void emitValue(ByteArrayOutputStream o, int v, int size) {
        switch (size) {
            case 1 -> o.write(v & 0xFF);
            case 2 -> { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
            default -> { o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
        }
    }

    private int sizeOf(Stmt st, int deflt) {
        if (st.sizeSuffix == null) return deflt;
        return switch (st.sizeSuffix) {
            case "b", "s" -> 1;
            case "w" -> 2;
            case "l" -> 4;
            default -> throw new AsmException("suffisso di size sconosciuto: ." + st.sizeSuffix);
        };
    }

    private int val(String expr) { return Expr.eval(expr, symbols, pc); }

    // =========================================================================
    // Codifica istruzioni
    // =========================================================================

    private byte[] encodeInstruction(Stmt st, int here) {
        String m = st.mnemonic;
        List<Operand> ops = new ArrayList<>();
        for (String s : st.operands) ops.add(parseOperand(s));

        Emit e = new Emit();

        // --- Branch e salti -------------------------------------------------
        if (m.equals("bra") || m.equals("bsr") || (m.startsWith("b") && CC.containsKey(m.substring(1)))) {
            int cc = m.equals("bra") ? 0 : m.equals("bsr") ? 1 : CC.get(m.substring(1));
            int target = val(st.operands.get(0));
            boolean shrt = "s".equals(st.sizeSuffix) || "b".equals(st.sizeSuffix);
            int disp = target - (st.address + 2);
            if (shrt) {
                if (!symbols.lenient && (disp < -128 || disp > 127 || disp == 0)) {
                    throw new AsmException("branch corto fuori portata: " + disp);
                }
                e.w(0x6000 | (cc << 8) | (disp & 0xFF));
            } else {
                if (!symbols.lenient && (disp < -32768 || disp > 32767)) {
                    throw new AsmException("branch fuori portata: " + disp);
                }
                e.w(0x6000 | (cc << 8));
                e.w(disp & 0xFFFF);
            }
            return e.bytes();
        }
        if (m.startsWith("db")) {
            String c = m.substring(2);
            int cc = c.equals("ra") || c.equals("f") ? 1 : CC.getOrDefault(c, -1);
            if (cc < 0) throw new AsmException("condizione DBcc sconosciuta: " + c);
            int reg = dreg(ops.get(0));
            int target = val(st.operands.get(1));
            int disp = target - (st.address + 2);
            if (!symbols.lenient && (disp < -32768 || disp > 32767)) {
                throw new AsmException("DBcc fuori portata: " + disp);
            }
            e.w(0x50C8 | (cc << 8) | reg);
            e.w(disp & 0xFFFF);
            return e.bytes();
        }
        if (m.startsWith("s") && m.length() > 1 && CC.containsKey(m.substring(1)) && !m.equals("sub")) {
            int cc = CC.get(m.substring(1));
            Operand d = ops.get(0);
            e.w(0x50C0 | (cc << 8) | ea(d));
            ext(e, d, 1);
            return e.bytes();
        }

        switch (m) {
            // --- MOVE -------------------------------------------------------
            case "move", "movea" -> {
                Operand s = ops.get(0), d = ops.get(1);
                int size = sizeOf(st, 2);
                if (d.kind == Kind.SR) { e.w(0x46C0 | ea(s)); ext(e, s, 2); return e.bytes(); }
                if (d.kind == Kind.CCR) { e.w(0x44C0 | ea(s)); ext(e, s, 2); return e.bytes(); }
                if (s.kind == Kind.SR) { e.w(0x40C0 | ea(d)); ext(e, d, 2); return e.bytes(); }
                if (d.kind == Kind.USP) { e.w(0x4E60 | areg(s)); return e.bytes(); }
                if (s.kind == Kind.USP) { e.w(0x4E68 | areg(d)); return e.bytes(); }
                int sb = switch (size) { case 1 -> 1; case 2 -> 3; default -> 2; };
                e.w((sb << 12) | (d.eaReg() << 9) | (d.mode() << 6) | ea(s));
                ext(e, s, size);
                ext(e, d, size);
                return e.bytes();
            }
            case "moveq" -> {
                int imm = val(strip(st.operands.get(0)));
                if (!symbols.lenient && (imm < -128 || imm > 127)) {
                    throw new AsmException("moveq fuori range: " + imm);
                }
                e.w(0x7000 | (dreg(ops.get(1)) << 9) | (imm & 0xFF));
                return e.bytes();
            }
            case "movem" -> {
                int size = sizeOf(st, 2);
                boolean toMemory = ops.get(0).kind == Kind.REGLIST;
                Operand list = toMemory ? ops.get(0) : ops.get(1);
                Operand mem = toMemory ? ops.get(1) : ops.get(0);
                int mask = mem.kind == Kind.PREDEC ? reverse16(list.regMask) : list.regMask;
                e.w((toMemory ? 0x4880 : 0x4C80) | (size == 4 ? 0x40 : 0) | ea(mem));
                e.w(mask);
                ext(e, mem, size);
                return e.bytes();
            }
            case "lea" -> { e.w(0x41C0 | (areg(ops.get(1)) << 9) | ea(ops.get(0))); ext(e, ops.get(0), 4); return e.bytes(); }
            case "pea" -> { e.w(0x4840 | ea(ops.get(0))); ext(e, ops.get(0), 4); return e.bytes(); }

            // --- Aritmetica a due operandi ----------------------------------
            case "add", "sub", "and", "or", "cmp", "eor" -> {
                return alu(st, m, ops, e);
            }
            case "adda", "suba", "cmpa" -> {
                int size = sizeOf(st, 2);
                int base = switch (m) { case "adda" -> 0xD000; case "suba" -> 0x9000; default -> 0xB000; };
                e.w(base | (areg(ops.get(1)) << 9) | ((size == 4 ? 7 : 3) << 6) | ea(ops.get(0)));
                ext(e, ops.get(0), size);
                return e.bytes();
            }
            case "addi", "subi", "andi", "ori", "eori", "cmpi" -> {
                int size = sizeOf(st, 2);
                Operand d = ops.get(1);
                int base = switch (m) {
                    case "ori" -> 0x0000; case "andi" -> 0x0200; case "subi" -> 0x0400;
                    case "addi" -> 0x0600; case "eori" -> 0x0A00; default -> 0x0C00;
                };
                if (d.kind == Kind.CCR || d.kind == Kind.SR) {
                    if (m.equals("cmpi")) throw new AsmException("cmpi su SR/CCR non esiste");
                    e.w(base | (d.kind == Kind.SR ? 0x7C : 0x3C));
                    e.w(val(strip(st.operands.get(0))) & 0xFFFF);
                    return e.bytes();
                }
                e.w(base | (sizeField(size) << 6) | ea(d));
                immediate(e, val(strip(st.operands.get(0))), size);
                ext(e, d, size);
                return e.bytes();
            }
            case "addq", "subq" -> {
                int size = sizeOf(st, 2);
                int q = val(strip(st.operands.get(0)));
                if (!symbols.lenient && (q < 1 || q > 8)) throw new AsmException(m + " fuori range 1..8: " + q);
                Operand d = ops.get(1);
                e.w(0x5000 | ((q & 7) << 9) | (m.equals("subq") ? 0x100 : 0) | (sizeField(size) << 6) | ea(d));
                ext(e, d, size);
                return e.bytes();
            }
            case "cmpm" -> {
                int size = sizeOf(st, 2);
                e.w(0xB108 | (ops.get(1).reg << 9) | (sizeField(size) << 6) | ops.get(0).reg);
                return e.bytes();
            }

            // --- Un operando ------------------------------------------------
            case "clr", "neg", "negx", "not", "tst" -> {
                int size = sizeOf(st, 2);
                int base = switch (m) {
                    case "negx" -> 0x4000; case "clr" -> 0x4200; case "neg" -> 0x4400;
                    case "not" -> 0x4600; default -> 0x4A00;
                };
                e.w(base | (sizeField(size) << 6) | ea(ops.get(0)));
                ext(e, ops.get(0), size);
                return e.bytes();
            }
            case "tas" -> { e.w(0x4AC0 | ea(ops.get(0))); ext(e, ops.get(0), 1); return e.bytes(); }
            case "swap" -> { e.w(0x4840 | dreg(ops.get(0))); return e.bytes(); }
            case "ext" -> { e.w((sizeOf(st, 2) == 4 ? 0x48C0 : 0x4880) | dreg(ops.get(0))); return e.bytes(); }
            case "jmp" -> { e.w(0x4EC0 | ea(ops.get(0))); ext(e, ops.get(0), 4); return e.bytes(); }
            case "jsr" -> { e.w(0x4E80 | ea(ops.get(0))); ext(e, ops.get(0), 4); return e.bytes(); }
            case "link" -> { e.w(0x4E50 | areg(ops.get(0))); e.w(val(strip(st.operands.get(1))) & 0xFFFF); return e.bytes(); }
            case "unlk" -> { e.w(0x4E58 | areg(ops.get(0))); return e.bytes(); }
            case "trap" -> { e.w(0x4E40 | (val(strip(st.operands.get(0))) & 0xF)); return e.bytes(); }
            case "stop" -> { e.w(0x4E72); e.w(val(strip(st.operands.get(0))) & 0xFFFF); return e.bytes(); }
            case "nop" -> { e.w(0x4E71); return e.bytes(); }
            case "rts" -> { e.w(0x4E75); return e.bytes(); }
            case "rte" -> { e.w(0x4E73); return e.bytes(); }
            case "rtr" -> { e.w(0x4E77); return e.bytes(); }
            case "trapv" -> { e.w(0x4E76); return e.bytes(); }
            case "reset" -> { e.w(0x4E70); return e.bytes(); }
            case "illegal" -> { e.w(0x4AFC); return e.bytes(); }

            // --- Bit --------------------------------------------------------
            case "btst", "bchg", "bclr", "bset" -> {
                int op = switch (m) { case "btst" -> 0; case "bchg" -> 1; case "bclr" -> 2; default -> 3; };
                Operand s = ops.get(0), d = ops.get(1);
                int size = d.kind == Kind.DREG ? 4 : 1;
                if (s.kind == Kind.IMM) {
                    e.w(0x0800 | (op << 6) | ea(d));
                    e.w(val(s.expr) & 0xFF);
                } else {
                    e.w(0x0100 | (dreg(s) << 9) | (op << 6) | ea(d));
                }
                ext(e, d, size);
                return e.bytes();
            }

            // --- Shift e rotate ---------------------------------------------
            case "asl", "asr", "lsl", "lsr", "rol", "ror", "roxl", "roxr" -> {
                int type = switch (m.replaceAll("[lr]$", "")) {
                    case "as" -> 0; case "ls" -> 1; case "rox" -> 2; default -> 3;
                };
                int dir = m.endsWith("l") ? 1 : 0;
                if (ops.size() == 1) { // forma memoria, shift di 1
                    e.w(0xE0C0 | (type << 9) | (dir << 8) | ea(ops.get(0)));
                    ext(e, ops.get(0), 2);
                    return e.bytes();
                }
                int size = sizeOf(st, 2);
                Operand cnt = ops.get(0);
                int reg = dreg(ops.get(1));
                if (cnt.kind == Kind.IMM) {
                    int c = val(cnt.expr);
                    if (!symbols.lenient && (c < 1 || c > 8)) throw new AsmException("shift immediato 1..8: " + c);
                    e.w(0xE000 | ((c & 7) << 9) | (dir << 8) | (sizeField(size) << 6) | (type << 3) | reg);
                } else {
                    e.w(0xE020 | (dreg(cnt) << 9) | (dir << 8) | (sizeField(size) << 6) | (type << 3) | reg);
                }
                return e.bytes();
            }

            // --- Moltiplicazione e divisione --------------------------------
            case "mulu", "muls", "divu", "divs" -> {
                int base = switch (m) {
                    case "divu" -> 0x80C0; case "divs" -> 0x81C0;
                    case "mulu" -> 0xC0C0; default -> 0xC1C0;
                };
                e.w(base | (dreg(ops.get(1)) << 9) | ea(ops.get(0)));
                ext(e, ops.get(0), 2);
                return e.bytes();
            }
            case "exg" -> {
                Operand x = ops.get(0), y = ops.get(1);
                int opmode, rx, ry;
                if (x.kind == Kind.DREG && y.kind == Kind.DREG) { opmode = 0x08; rx = x.reg; ry = y.reg; }
                else if (x.kind == Kind.AREG && y.kind == Kind.AREG) { opmode = 0x09; rx = x.reg; ry = y.reg; }
                else if (x.kind == Kind.DREG) { opmode = 0x11; rx = x.reg; ry = y.reg; }
                else { opmode = 0x11; rx = y.reg; ry = x.reg; }
                e.w(0xC100 | (rx << 9) | (opmode << 3) | ry);
                return e.bytes();
            }
            default -> throw new AsmException("istruzione non supportata: " + m);
        }
    }

    /** ADD/SUB/AND/OR/CMP/EOR nelle forme Dn-ea ed ea-Dn. */
    private byte[] alu(Stmt st, String m, List<Operand> ops, Emit e) {
        int size = sizeOf(st, 2);
        Operand s = ops.get(0), d = ops.get(1);
        int base = switch (m) {
            case "or" -> 0x8000; case "sub" -> 0x9000; case "cmp" -> 0xB000;
            case "eor" -> 0xB000; case "and" -> 0xC000; default -> 0xD000;
        };
        // Forme immediate scritte senza la 'i' finale: delega a ADDI/SUBI/...
        if (s.kind == Kind.IMM) {
            Stmt alt = new Stmt();
            alt.mnemonic = m + "i"; alt.sizeSuffix = st.sizeSuffix; alt.operands = st.operands;
            alt.address = st.address; alt.lineNo = st.lineNo; alt.raw = st.raw;
            return encodeInstruction(alt, st.address);
        }
        if (m.equals("eor")) { // solo Dn -> ea
            e.w(base | (dreg(s) << 9) | ((4 + sizeField(size)) << 6) | ea(d));
            ext(e, d, size);
            return e.bytes();
        }
        if (d.kind == Kind.DREG) {   // ea -> Dn
            e.w(base | (d.reg << 9) | (sizeField(size) << 6) | ea(s));
            ext(e, s, size);
        } else {                      // Dn -> ea
            if (m.equals("cmp")) throw new AsmException("cmp vuole Dn come destinazione");
            e.w(base | (dreg(s) << 9) | ((4 + sizeField(size)) << 6) | ea(d));
            ext(e, d, size);
        }
        return e.bytes();
    }

    private static int sizeField(int size) {
        return switch (size) { case 1 -> 0; case 2 -> 1; default -> 2; };
    }

    private static int reverse16(int mask) {
        int r = 0;
        for (int i = 0; i < 16; i++) if ((mask & (1 << i)) != 0) r |= 1 << (15 - i);
        return r;
    }

    private int ea(Operand o) { return (o.mode() << 3) | o.eaReg(); }

    private static int dreg(Operand o) {
        if (o.kind != Kind.DREG) throw new AsmException("atteso un registro dati, trovato " + o.kind);
        return o.reg;
    }

    private static int areg(Operand o) {
        if (o.kind != Kind.AREG) throw new AsmException("atteso un registro indirizzi, trovato " + o.kind);
        return o.reg;
    }

    private static String strip(String s) {
        String t = s.trim();
        return t.startsWith("#") ? t.substring(1) : t;
    }

    /** Emette le parole di estensione dell'operando. */
    private void ext(Emit e, Operand o, int size) {
        switch (o.kind) {
            case DISP, PC_DISP -> e.w(val(o.expr) & 0xFFFF);
            case INDEX, PC_INDEX -> {
                int d8 = val(o.expr);
                e.w(((o.indexReg & 0xF) << 12) | (o.indexLong ? 0x0800 : 0) | (d8 & 0xFF));
            }
            case ABS_W -> e.w(val(o.expr) & 0xFFFF);
            case ABS_L -> e.l(val(o.expr));
            case IMM -> immediate(e, val(o.expr), size);
            default -> { }
        }
    }

    private void immediate(Emit e, int v, int size) {
        if (size == 4) e.l(v); else e.w(v & 0xFFFF);
    }

    // =========================================================================
    // Parsing degli operandi
    // =========================================================================

    private Operand parseOperand(String raw) {
        String t = raw.trim();
        if (t.isEmpty()) throw new AsmException("operando vuoto");
        String low = t.toLowerCase(Locale.ROOT);

        if (t.startsWith("#")) return Operand.withExpr(Kind.IMM, 0, t.substring(1));
        if (low.equals("sr")) return Operand.simple(Kind.SR, 0);
        if (low.equals("ccr")) return Operand.simple(Kind.CCR, 0);
        if (low.equals("usp")) return Operand.simple(Kind.USP, 0);

        Integer dn = dataRegister(low);
        if (dn != null) return Operand.simple(Kind.DREG, dn);
        Integer an = addressRegister(low);
        if (an != null) return Operand.simple(Kind.AREG, an);

        if (low.contains("/") || (isRegListRange(low))) {
            return Operand.regList(parseRegList(low));
        }

        if (low.startsWith("-(") && low.endsWith(")")) {
            return Operand.simple(Kind.PREDEC, requireA(low.substring(2, low.length() - 1)));
        }
        if (low.endsWith(")+")) {
            int open = low.indexOf('(');
            return Operand.simple(Kind.POSTINC, requireA(low.substring(open + 1, low.length() - 2)));
        }
        if (t.endsWith(")")) {
            int open = matchingOpen(t);
            if (open >= 0) {
                String prefix = t.substring(0, open).trim();
                String inner = t.substring(open + 1, t.length() - 1).trim();
                List<String> parts = splitTopLevel(inner);
                Operand o = parseParenForm(prefix, parts, t);
                if (o != null) return o;
            }
        }
        // Resta l'indirizzo assoluto, con eventuale suffisso .w/.l esplicito.
        if (low.endsWith(".w")) return Operand.withExpr(Kind.ABS_W, 0, t.substring(0, t.length() - 2));
        if (low.endsWith(".l")) return Operand.withExpr(Kind.ABS_L, 0, t.substring(0, t.length() - 2));
        return Operand.withExpr(Kind.ABS_L, 0, t);
    }

    /**
     * Forme con parentesi: {@code (An)}, {@code d(An)}, {@code (d,An)},
     * {@code d(An,Xn)}, {@code (d,An,Xn)}, e le corrispondenti PC-relative.
     */
    private Operand parseParenForm(String prefix, List<String> parts, String whole) {
        String p0 = parts.get(0).toLowerCase(Locale.ROOT);
        if (parts.size() == 1) {
            Integer a = addressRegister(p0);
            if (a != null) {
                return prefix.isEmpty() ? Operand.simple(Kind.INDIRECT, a)
                                        : Operand.withExpr(Kind.DISP, a, prefix);
            }
            if (p0.equals("pc")) return Operand.withExpr(Kind.PC_DISP, 0, prefix + "-(*+2)");
            return null; // e' solo un'espressione parentesizzata: indirizzo assoluto
        }
        if (parts.size() == 2) {
            Integer a0 = addressRegister(p0);
            String p1 = parts.get(1).toLowerCase(Locale.ROOT);
            if (a0 != null || p0.equals("pc")) {
                // prefix(An,Xn) oppure prefix(PC,Xn)
                IndexSpec ix = parseIndex(p1);
                String disp = prefix.isEmpty() ? "0" : prefix;
                if (p0.equals("pc")) return Operand.index(Kind.PC_INDEX, 0, disp + "-(*+2)", ix.reg, ix.isLong);
                return Operand.index(Kind.INDEX, a0, disp, ix.reg, ix.isLong);
            }
            Integer a1 = addressRegister(p1);
            if (a1 != null) return Operand.withExpr(Kind.DISP, a1, parts.get(0));      // (d16,An)
            if (p1.equals("pc")) return Operand.withExpr(Kind.PC_DISP, 0, parts.get(0) + "-(*+2)");
            return null;
        }
        if (parts.size() == 3) {   // (d8,An,Xn) oppure (d8,PC,Xn)
            String p1 = parts.get(1).toLowerCase(Locale.ROOT);
            IndexSpec ix = parseIndex(parts.get(2).toLowerCase(Locale.ROOT));
            Integer a1 = addressRegister(p1);
            if (a1 != null) return Operand.index(Kind.INDEX, a1, parts.get(0), ix.reg, ix.isLong);
            if (p1.equals("pc")) return Operand.index(Kind.PC_INDEX, 0, parts.get(0) + "-(*+2)", ix.reg, ix.isLong);
        }
        throw new AsmException("forma di indirizzamento non riconosciuta: " + whole);
    }

    private record IndexSpec(int reg, boolean isLong) {}

    private IndexSpec parseIndex(String s) {
        String t = s.trim();
        boolean isLong = false;
        if (t.endsWith(".l")) { isLong = true; t = t.substring(0, t.length() - 2); }
        else if (t.endsWith(".w")) { t = t.substring(0, t.length() - 2); }
        Integer d = dataRegister(t);
        if (d != null) return new IndexSpec(d, isLong);
        Integer a = addressRegister(t);
        if (a != null) return new IndexSpec(8 + a, isLong);
        throw new AsmException("registro indice non valido: " + s);
    }

    /** Indice della '(' che apre l'ultima ')' della stringa. */
    private static int matchingOpen(String t) {
        int depth = 0;
        for (int i = t.length() - 1; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static int requireA(String s) {
        Integer a = addressRegister(s.trim().toLowerCase(Locale.ROOT));
        if (a == null) throw new AsmException("atteso An, trovato: " + s);
        return a;
    }

    private static Integer dataRegister(String s) {
        if (s.length() == 2 && s.charAt(0) == 'd' && s.charAt(1) >= '0' && s.charAt(1) <= '7') {
            return s.charAt(1) - '0';
        }
        return null;
    }

    private static Integer addressRegister(String s) {
        if (s.equals("sp")) return 7;
        if (s.length() == 2 && s.charAt(0) == 'a' && s.charAt(1) >= '0' && s.charAt(1) <= '7') {
            return s.charAt(1) - '0';
        }
        return null;
    }

    private static boolean isRegListRange(String s) {
        int dash = s.indexOf('-');
        if (dash <= 0) return false;
        return registerIndex(s.substring(0, dash)) != null;
    }

    /** D0-D7 = bit 0-7, A0-A7 = bit 8-15. */
    private static Integer registerIndex(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        Integer d = dataRegister(t);
        if (d != null) return d;
        Integer a = addressRegister(t);
        if (a != null) return 8 + a;
        return null;
    }

    private static int parseRegList(String s) {
        int mask = 0;
        for (String group : s.split("/")) {
            String g = group.trim();
            int dash = g.indexOf('-');
            if (dash > 0) {
                Integer from = registerIndex(g.substring(0, dash));
                Integer to = registerIndex(g.substring(dash + 1));
                if (from == null || to == null) throw new AsmException("lista registri non valida: " + s);
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) mask |= 1 << i;
            } else {
                Integer r = registerIndex(g);
                if (r == null) throw new AsmException("lista registri non valida: " + s);
                mask |= 1 << r;
            }
        }
        return mask;
    }

    /** Accumulatore di parole big-endian. */
    private static final class Emit {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        void w(int v) { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
        void l(int v) { w((v >> 16) & 0xFFFF); w(v & 0xFFFF); }
        byte[] bytes() { return o.toByteArray(); }
    }

    /** Mappa simbolo -> indirizzo, per dump e test. */
    public static Map<String, Integer> sorted(Map<String, Integer> in) {
        return new LinkedHashMap<>(new TreeMap<>(in));
    }
}
