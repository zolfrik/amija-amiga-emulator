package io.github.zolfrik.amija.romgen.asm;

import java.util.Map;

/**
 * Valutatore di espressioni intere per l'assemblatore.
 *
 * Sintassi Motorola: {@code $hex}, {@code %binario}, {@code 'c'}, decimale,
 * simboli, {@code *} = program counter corrente, operatori
 * {@code + - * / & | ^ << >>} con parentesi e unari {@code + - ~}.
 */
public final class Expr {

    private final String s;
    private int p;
    private final Map<String, Integer> symbols;
    private final int pc;

    private Expr(String s, Map<String, Integer> symbols, int pc) {
        this.s = s;
        this.symbols = symbols;
        this.pc = pc;
    }

    public static int eval(String text, Map<String, Integer> symbols, int pc) {
        Expr e = new Expr(text.trim(), symbols, pc);
        int v = e.parseOr();
        e.skipWs();
        if (e.p < e.s.length()) {
            throw new AsmException("espressione non consumata del tutto: '" + text
                    + "' (resto: '" + e.s.substring(e.p) + "')");
        }
        return v;
    }

    private void skipWs() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private boolean eat(String tok) {
        skipWs();
        if (s.startsWith(tok, p)) { p += tok.length(); return true; }
        return false;
    }

    private int parseOr() {
        int v = parseXor();
        while (true) {
            skipWs();
            if (p < s.length() && s.charAt(p) == '|') { p++; v |= parseXor(); }
            else return v;
        }
    }

    private int parseXor() {
        int v = parseAnd();
        while (true) {
            skipWs();
            if (p < s.length() && s.charAt(p) == '^') { p++; v ^= parseAnd(); }
            else return v;
        }
    }

    private int parseAnd() {
        int v = parseShift();
        while (true) {
            skipWs();
            if (p < s.length() && s.charAt(p) == '&') { p++; v &= parseShift(); }
            else return v;
        }
    }

    private int parseShift() {
        int v = parseAdd();
        while (true) {
            if (eat("<<")) v <<= parseAdd();
            else if (eat(">>")) v >>= parseAdd();
            else return v;
        }
    }

    private int parseAdd() {
        int v = parseMul();
        while (true) {
            skipWs();
            if (p < s.length() && s.charAt(p) == '+') { p++; v += parseMul(); }
            else if (p < s.length() && s.charAt(p) == '-') { p++; v -= parseMul(); }
            else return v;
        }
    }

    private int parseMul() {
        int v = parseUnary();
        while (true) {
            skipWs();
            // '*' e' moltiplicazione solo se non e' in posizione di operando (dove
            // vale il PC): qui siamo dopo un valore, quindi e' l'operatore.
            if (p < s.length() && s.charAt(p) == '*') { p++; v *= parseUnary(); }
            else if (p < s.length() && s.charAt(p) == '/') { p++; int d = parseUnary(); v /= d; }
            else return v;
        }
    }

    private int parseUnary() {
        skipWs();
        if (p >= s.length()) throw new AsmException("espressione troncata: '" + s + "'");
        char c = s.charAt(p);
        if (c == '-') { p++; return -parseUnary(); }
        if (c == '+') { p++; return parseUnary(); }
        if (c == '~') { p++; return ~parseUnary(); }
        return parsePrimary();
    }

    private int parsePrimary() {
        skipWs();
        char c = s.charAt(p);
        if (c == '(') {
            p++;
            int v = parseOr();
            if (!eat(")")) throw new AsmException("manca ')' in '" + s + "'");
            return v;
        }
        if (c == '*') { p++; return pc; }
        if (c == '$') { p++; return (int) Long.parseLong(scan("0123456789abcdefABCDEF"), 16); }
        if (c == '%') { p++; return (int) Long.parseLong(scan("01"), 2); }
        if (c == '\'') return parseCharLiteral();
        if (Character.isDigit(c)) return (int) Long.parseLong(scan("0123456789"), 10);
        if (Character.isLetter(c) || c == '_' || c == '.') {
            String name = scanSymbol();
            Integer v = symbols.get(name);
            if (v == null) throw new AsmException("simbolo non definito: " + name);
            return v;
        }
        throw new AsmException("carattere inatteso '" + c + "' in '" + s + "'");
    }

    /** Letterale di carattere Motorola: fino a 4 caratteri, big-endian ('DOS' + 0). */
    private int parseCharLiteral() {
        p++; // apice iniziale
        int v = 0, n = 0;
        while (p < s.length() && s.charAt(p) != '\'') {
            v = (v << 8) | (s.charAt(p) & 0xFF);
            n++;
            p++;
        }
        if (p >= s.length()) throw new AsmException("letterale di carattere non chiuso in '" + s + "'");
        p++; // apice finale
        if (n == 0 || n > 4) throw new AsmException("letterale di carattere di lunghezza " + n);
        return v;
    }

    private String scan(String allowed) {
        int start = p;
        while (p < s.length() && allowed.indexOf(s.charAt(p)) >= 0) p++;
        if (p == start) throw new AsmException("cifra attesa in '" + s + "' a " + start);
        return s.substring(start, p);
    }

    private String scanSymbol() {
        int start = p;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$') p++;
            else break;
        }
        return s.substring(start, p);
    }
}
