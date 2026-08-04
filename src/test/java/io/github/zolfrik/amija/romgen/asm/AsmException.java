package io.github.zolfrik.amija.romgen.asm;

/** Errore di assemblaggio, con contesto di riga aggiunto dal driver. */
public class AsmException extends RuntimeException {
    public AsmException(String message) { super(message); }
}
