/*
 * AmiJa - a cycle-exact Amiga 500 emulator in Java.
 * Copyright (C) 2026 zolfrik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.zolfrik.amija.common;

/**
 * Insieme dei pin del MOS 8520.
 *
 * Gestisce le due porte I/O a 8 bit (PA0-PA7, PB0-PB7) e i pin speciali:
 *   - FLAG  : fronte di discesa genera interrupt (bit 4 dell'ICR)
 *   - TOD   : fronte di salita incrementa il contatore Time-of-Day
 *   - CNT   : clock per la porta seriale (input o output a seconda di CRA bit 6)
 *   - SP    : dati seriali (input o output a seconda di CRA bit 6)
 *   - IRQ   : linea di interrupt verso la CPU (open-drain, active-LOW)
 *
 * <h2>Modello delle porte (PERF)</h2>
 * Le 16 linee delle porte A/B non hanno bisogno né di rilevamento dei fronti né
 * di logica open-drain: servono solo a leggere/scrivere PRA/PRB. Per questo NON
 * sono modellate come oggetti pin (che imponevano ~16 chiamate virtuali + loop a
 * ogni accesso), ma come tre maschere di bit per porta:
 *   - {@code drive} : valore che il chip emette (registro PR), bit i = pin i
 *   - {@code ddr}   : direzione (1 = output, 0 = input)
 *   - {@code ext}   : livello imposto dall'hardware esterno
 * Lo stato fisico di un pin è quindi {@code (drive & ddr) | (ext & ~ddr)} su tutti
 * gli 8 bit in una singola espressione, senza loop. Il comportamento osservabile
 * (lettura registri, override timer su PB6/PB7) è identico al vecchio modello.
 *
 * I 5 pin speciali restano oggetti {@link CIAPin} perché richiedono il
 * rilevamento dei fronti (FLAG/CNT/TOD) e la logica open-drain (IRQ/SP/CNT).
 *
 * Nota sull'override dei timer su PB6/PB7:
 *   Quando PBON è attivo, il timer controlla il pin. Nell'emulazione corrente
 *   ciò è gestito da MOS8520.updatePB(), che ripiega il livello del timer nel
 *   valore scritto con writePortData; il flag di override per-pin
 *   ({@code bTimerOvr}) viene usato solo dal reset (sempre disattivo a regime).
 *
 * @author alessio.carotenuto
 */
public class CIAPinSet {

    /**
     * Modella un singolo pin fisico del MOS 8520 (usato per i 5 pin speciali).
     */
    public static class CIAPin {

        // Stato logico corrente e stato al ciclo precedente (per rilevamento fronti)
        private boolean currentLevel = true;
        private boolean lastLevel    = true;

        // Sorgenti di segnale
        private boolean internalDrive = true; // Valore che il CIA tenta di emettere (se output)
        private boolean externalInput = true; // Valore imposto dall'hardware esterno

        // Configurazione
        private boolean isOutput      = false; // Definito dal DDR
        private boolean timerOverride = false; // Se true, il timer controlla il pin (PB6/PB7)
        private boolean isOpenDrain   = false; // IRQ, SP, CNT
        private boolean dirty         = true;

        /**
         * Aggiorna lo stato del pin per il ciclo corrente.
         */
        public void update() {
            lastLevel = currentLevel;

            if (isOutput || timerOverride) {
                if (isOpenDrain) {
                    currentLevel = internalDrive && externalInput;
                } else {
                    currentLevel = internalDrive;
                }
            } else {
                currentLevel = externalInput;
            }
            dirty = false;
        }

        /** Fronte di discesa: HIGH -> LOW. */
        public boolean isFallingEdge() {
            return lastLevel && !currentLevel;
        }

        /** Fronte di salita: LOW -> HIGH. */
        public boolean isRisingEdge() {
            return !lastLevel && currentLevel;
        }

        /**
         * Stato fisico reale del pin, usato per la lettura dei registri PRA/PRB.
         */
        public boolean getPinState() {
            if (isOutput || timerOverride) {
                if (isOpenDrain) {
                    return internalDrive && externalInput;
                } else {
                    return internalDrive;
                }
            } else {
                return externalInput;
            }
        }

        /** Imposta il valore che il chip tenta di emettere sul pin. */
        public void setInternalDrive(boolean value) {
            if (this.internalDrive == value) return;
            this.internalDrive = value;
            dirty = true;
        }

        /** Imposta il segnale proveniente dall'hardware esterno connesso al pin. */
        public void setExternalInput(boolean value) {
            if (this.externalInput == value) return;
            this.externalInput = value;
            dirty = true;
        }

        /** Configura la direzione del pin: true = output, false = input. */
        public void setDirection(boolean output) {
            if (this.isOutput == output) return;
            this.isOutput = output;
            dirty = true;
        }

        /**
         * Attiva o disattiva l'override del timer sul pin.
         */
        public void setTimerOverride(boolean active) {
            if (this.timerOverride == active) return;
            this.timerOverride = active;
            dirty = true;
        }

        /**
         * Configura il pin come open-drain.
         */
        public void setOpenDrain(boolean openDrain) {
            if (this.isOpenDrain == openDrain) return;
            this.isOpenDrain = openDrain;
            dirty = true;
        }

        /** Restituisce il valore che il chip sta tentando di emettere. */
        public boolean getInternalDrive() {
            return internalDrive;
        }

        /** Indica se il pin e configurato come output. */
        public boolean isOutputMode() {
            return isOutput || timerOverride;
        }

        private boolean isDirty() {
            return dirty;
        }
    }

    // Identification and logging (Stubbed)
    public void setName(String name)                          { }
    public void setClkSupplier(java.util.function.LongSupplier s) { }
    public void setPcSupplier(java.util.function.IntSupplier s)   { }

    // ── Porte I/O da 8 bit, rappresentate come maschere di bit ────────────────
    // Default coerenti col vecchio modello a oggetti: internalDrive=true (drive=0xFF),
    // externalInput=true (ext=0xFF), isOutput=false (ddr=0). Il reset del MOS8520
    // sovrascrive comunque drive/ddr a 0.
    private int aDrive = 0xFF, aDdr = 0x00, aExt = 0xFF;
    private int bDrive = 0xFF, bDdr = 0x00, bExt = 0xFF;
    private int bTimerOvr = 0x00; // override timer su PB6/PB7 (bit 6/7); a regime resta 0

    // Pin speciali (richiedono fronti / open-drain)
    private final CIAPin flagPin = new CIAPin();
    private final CIAPin todPin  = new CIAPin();
    private final CIAPin cntPin  = new CIAPin();
    private final CIAPin spPin   = new CIAPin();
    private final CIAPin irqPin  = new CIAPin();

    /**
     * Valore corrente del registro PRB, necessario per ripristinare il bit di
     * drive su PB6/PB7 quando l'override del timer viene disattivato.
     */
    private int prbValue = 0xFF;

    public CIAPinSet() {
        // IRQ: open-drain, active-LOW.
        // Il CIA forza LOW per segnalare un interrupt; HIGH a riposo.
        irqPin.setOpenDrain(true);
        irqPin.setDirection(true);
        irqPin.setInternalDrive(true); // Rilasciato (HIGH) a riposo
        irqPin.setExternalInput(true);

        // SP e CNT: open-drain secondo le specifiche del 8520.
        spPin.setOpenDrain(true);
        cntPin.setOpenDrain(true);
    }

    // -------------------------------------------------------------------------
    // Aggiornamento ciclico
    // -------------------------------------------------------------------------

    /**
     * Aggiorna lo stato fisico dei pin speciali (fronti). Le porte A/B non
     * richiedono aggiornamento: il loro stato è calcolato on-demand dalle maschere.
     */
    public boolean updateAll() {
        if (!hasSpecialPinChanges()) return false;
        flagPin.update();
        todPin.update();
        cntPin.update();
        spPin.update();
        irqPin.update();
        return true;
    }

    public boolean hasSpecialPinChanges() {
        return flagPin.isDirty() || todPin.isDirty() || cntPin.isDirty()
                || spPin.isDirty() || irqPin.isDirty();
    }

    // -------------------------------------------------------------------------
    // Interfaccia con i registri del CIA
    // -------------------------------------------------------------------------

    /**
     * Scrive nel registro DDR (Data Direction Register).
     * Bit = 1 → output, bit = 0 → input.
     *
     * @param portIdx 0 = Porta A, 1 = Porta B
     * @param value   Byte da scrivere nel DDR
     */
    public void writeDDR(int portIdx, byte value) {
        if (portIdx == 0) aDdr = value & 0xFF;
        else              bDdr = value & 0xFF;
    }

    /**
     * Scrive nel registro PR (Peripheral Register).
     * Imposta il valore che il chip tenta di emettere sui pin configurati come output.
     *
     * @param portIdx 0 = Porta A, 1 = Porta B
     * @param value   Byte da scrivere nel PR
     */
    public void writePortData(int portIdx, byte value) {
        if (portIdx == 0) {
            aDrive = value & 0xFF;
        } else {
            bDrive = value & 0xFF;
            prbValue = value & 0xFF; // copia di PRB per il ripristino dell'override
        }
    }

    /**
     * Legge il registro PR.
     * Restituisce lo stato fisico reale dei pin: per i bit configurati come output
     * il valore emesso dal chip (drive), per i bit input il livello esterno (ext).
     * Rispecchia il comportamento hardware (si legge ciò che c'è sul piedino).
     *
     * @param portIdx 0 = Porta A, 1 = Porta B
     * @return Stato corrente degli 8 pin della porta
     */
    public byte readPortData(int portIdx) {
        int out, drive, ext;
        if (portIdx == 0) {
            out = aDdr;
            drive = aDrive;
            ext = aExt;
        } else {
            out = bDdr | bTimerOvr; // i pin sotto override timer contano come output
            drive = bDrive;
            ext = bExt;
        }
        return (byte) (((drive & out) | (ext & ~out)) & 0xFF);
    }

    /**
     * Gestisce l'override dei pin PB6 e PB7 da parte dei timer (PBON).
     * A regime non viene usato (l'override è ripiegato in writePortData da
     * MOS8520.updatePB); il reset lo disattiva.
     *
     * @param timerIdx 0 = Timer A / PB6, 1 = Timer B / PB7
     * @param active   true se il timer deve controllare il pin
     * @param level    Livello logico emesso dal timer (usato solo se active = true)
     */
    public void setTimerOutputOverride(int timerIdx, boolean active, boolean level) {
        int mask = (timerIdx == 0) ? 0x40 : 0x80;
        if (active) {
            bTimerOvr |= mask;
            if (level) bDrive |= mask; else bDrive &= ~mask;
        } else {
            bTimerOvr &= ~mask;
            // Ripristina il bit dal registro PRB
            if ((prbValue & mask) != 0) bDrive |= mask; else bDrive &= ~mask;
        }
    }

    // -------------------------------------------------------------------------
    // Interfaccia con l'hardware esterno
    // -------------------------------------------------------------------------

    /**
     * Simula un segnale in ingresso proveniente da una periferica esterna
     * (es. drive floppy, joystick, tastiera).
     *
     * @param portIdx 0 = Porta A, 1 = Porta B
     * @param bit     Indice del pin (0-7)
     * @param level   Livello logico del segnale esterno (false = LOW)
     */
    public void setExternalInput(int portIdx, int bit, boolean level) {
        int mask = 1 << bit;
        if (portIdx == 0) {
            if (level) aExt |= mask; else aExt &= ~mask;
        } else {
            if (level) bExt |= mask; else bExt &= ~mask;
        }
    }

    // -------------------------------------------------------------------------
    // Accessori pin speciali
    // -------------------------------------------------------------------------

    public CIAPin getFlagPin() { return flagPin; }
    public CIAPin getTodPin()  { return todPin;  }
    public CIAPin getCntPin()  { return cntPin;  }
    public CIAPin getSpPin()   { return spPin;   }
    public CIAPin getIrqPin()  { return irqPin;  }
}
