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

package io.github.zolfrik.amija.cpu;

public class StopInstruction extends AbstractM68kInstruction {
    public static final StopInstruction INSTANCE = new StopInstruction();
    private StopInstruction() {}
    private static final int IMM = 0;
    // Moira `execStop`: SUPERVISOR_MODE_ONLY, poi `readI` (= un `readExt`, UNA prefetch
    // dell'immediato), poi setSR + STOPPED + CYCLES_IP(word)=4. Inibiamo la prefetch di
    // refill di `prepareInstruction` così la singola readExt resta in `fetchExtension`
    // (come Moira readI) — elimina un +CCK di fase su stop3/stop4 (frontiera 2).
    @Override public boolean shouldInhibitPrefetch() { return true; }
    // The STOP round grid opens at the start of the instruction, with its first IPL fetch,
    // not once the immediate is in: rounds of 4 clocks run from this instant.
    @Override public void prepareInstruction(Motorola68000 cpu) {
        super.prepareInstruction(cpu);
        cpu.beginStopRounds();
    }
    @Override public boolean executeStep(Motorola68000 cpu) {
        // SUPERVISOR_MODE_ONLY: STOP in user mode → privilege violation immediata (Moira
        // controlla PRIMA di readI). Copre il ri-esecuzione dello `stop` in USER del
        // priv-loop stop3/stop4 (rte-in-user → priv diretta, senza entrare in STOPPED).
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstruction(cpu);
        }
        switch (getStep(cpu)) {
            // Moira execStop fa `readI` SUBITO (nessun preambolo): la readExt dell'immediato
            // è il primo passo. (Storicamente qui c'era un no-op step0 che sprecava 1 CCK e
            // sfasava l'entry stop→trace del priv-loop di stop4 — rimosso.)
            case 0 -> { if (!fetchExtension(cpu, IMM)) return false; nextStep(cpu); return false; }
            case 1 -> {
                cpu.writeSR(getTemp(cpu, IMM), true);
                // Moira slow-path: se il trace era già pending PRIMA dello STOP (T=1, es.
                // `ori #$8000,SR; stop #$0000`), il TRACE_EXC viene latchato NELLO STESSO giro
                // di execStop (Moira.cpp line 288, prima che STOPPED fermi la CPU) e scatta al
                // giro successivo SENZA sync extra e SENZA passare dal ramo STOPPED. Lo
                // riproduciamo facendo scattare il trace DIRETTAMENTE qui: elimina la
                // STOPPED-latency (1 CCK del round-trip finalize→ramo STOPPED) e l'idle
                // artificiale che prima serviva a mascherarla. stop2/stop4 → verdi.
                if (cpu.isTracePending()) {
                    cpu.fireStopTrace();
                    return finalizeInstruction(cpu);
                }
                // STOP che AZZERA il bit S (es. `stop #$0000`): privilege violation immediata,
                // senza mai entrare in STOPPED. WinUAE (op_4e72_13): il loop ri-dispatcha lo
                // stesso opcode al giro dopo e il check `if (!regs.s) Exception(8)` scatta
                // subito, senza sync aggiuntivi — il re-dispatch è gratis, quindi l'eccezione
                // parte appena l'istruzione STOP è conclusa. Il roundtrip per il ramo STOPPED
                // di risingEdge costava +1 CCK (oracolo WinUAE, stop3).
                if (!cpu.isSupervisor()) {
                    cpu.triggerPrivilegeViolation();
                    return finalizeInstruction(cpu);
                }
                // STOP senza trace pending (T=0, o STOP che CARICA T=1 come `stop #$A000`):
                // entra in STOPPED e attende un interrupt / privilege. Il CYCLES_IP(4) di
                // Moira è speso dalla macchina a step (readExt + questo step + finalize).
                cpu.cpuState = Motorola68000.CpuState.STOPPED;
                return finalizeInstruction(cpu);
            }
            default -> { return finalizeInstruction(cpu); }
        }
    }
    @Override public String getDisassembly(Motorola68000 cpu) { return "STOP #$" + Disassembly.formatHex(cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), 4); }
}
