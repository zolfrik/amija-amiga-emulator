/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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

package io.github.zolfrik.amija.custom.cd;

import java.io.IOException;
import java.util.List;

/**
 * The CD drive's audio player, in emulated time.
 *
 * <p>The drive reads audio ahead in buffers of {@link #SECTORS_PER_BUFFER} sectors, two of
 * them: while one plays, the other is filled. Playback takes one stereo sample of each buffer
 * every {@link #SAMPLE_UNITS} / {@value #UNITS_PER_CCK} colour clocks (44.1 kHz); when a
 * buffer has played out the other one starts and the played one is refilled at once. A play
 * starts with ten sectors of silence. The status of the player (playing, complete, error) is
 * reported to a {@link Listener} as the drive firmware sees it: at the start of a play, at
 * every buffer filled, and when the buffer holding the end of the requested range is filled,
 * which is up to two buffers before that end is heard.
 *
 * <p>Time is counted in units of 1/{@value #UNITS_PER_CCK} colour clock; {@link #advanceTo}
 * plays what is due up to an instant.
 */
public final class CddaPlayer {

    public static final int STATUS_NOT_SUPPORTED = 0x00;
    public static final int STATUS_IN_PROGRESS = 0x11;
    public static final int STATUS_PAUSED = 0x12;
    public static final int STATUS_PLAY_COMPLETE = 0x13;
    public static final int STATUS_PLAY_ERROR = 0x14;
    public static final int STATUS_NO_STATUS = 0x15;

    public static final int UNITS_PER_CCK = 512;
    /** Units between two stereo samples: one colour clock frequency over 44100. */
    public static final int SAMPLE_UNITS_PAL = (int) (3546895f * 512 / 44100.0f);
    public static final int SAMPLE_UNITS_NTSC = (int) (3579545f * 512 / 44100.0f);
    private static final int SAMPLE_UNITS = SAMPLE_UNITS_PAL;

    public static final int SECTORS_PER_BUFFER = 14;
    private static final int FRAMES_PER_BUFFER = SECTORS_PER_BUFFER * 2352 / 4;
    /** Silent sectors a play starts with. */
    private static final int START_DELAY_FRAMES = 10;

    /** What the drive firmware is told. */
    public interface Listener {
        /** A new status of the player. */
        void status(int status);

        /** The subchannel of one sector being played, 96 bytes interleaved. */
        void subcode(byte[] interleaved);
    }

    /** Observer of every sample played (tests and tools only). */
    public interface SampleObserver {
        void sample(long time, short left, short right);
    }

    private transient SampleObserver sampleObserver;

    public void setSampleObserver(SampleObserver observer) {
        this.sampleObserver = observer;
    }

    private final CdImage image;
    private final Listener listener;
    private final int sampleUnits;

    // Player (the play thread of the drive).
    /** &gt; 0 playing, 0 idle, -1 ending. */
    private int play;
    private boolean paused;
    private int playState;
    private int start;
    private int end;
    private int pos;
    private int lastPos;
    private int idleFrames;
    private int silentFrames;
    private boolean first;
    private boolean initDone;
    private int bufnum;
    private final boolean[] bufOn = new boolean[2];
    private final byte[][] buffers = {new byte[SECTORS_PER_BUFFER * 2352], new byte[SECTORS_PER_BUFFER * 2352]};

    // Output stream.
    private boolean streamOn;
    private int streamBuffer = -1;
    private int streamOffset;
    private int streamLength;
    private long nextSample;
    private short sampleLeft;
    private short sampleRight;

    public CddaPlayer(CdImage image, Listener listener, boolean ntsc) {
        this.image = image;
        this.listener = listener;
        this.sampleUnits = ntsc ? SAMPLE_UNITS_NTSC : SAMPLE_UNITS;
    }

    /** The position the player has read up to, for the Q subchannel. */
    public int position() {
        return lastPos;
    }

    /** The status as the Q subchannel reports it; complete and error are reported once. */
    public int takeQStatus() {
        int status = playState;
        if (play > 0 && paused) status = STATUS_PAUSED;
        if (playState == STATUS_PLAY_COMPLETE || playState == STATUS_PLAY_ERROR) playState = STATUS_NO_STATUS;
        return status;
    }

    public short left() {
        return sampleLeft;
    }

    public short right() {
        return sampleRight;
    }

    private void setState(int state) {
        playState = state;
        listener.status(state);
    }

    // ---------------------------------------------------------------------------------------
    // Table of contents helpers
    // ---------------------------------------------------------------------------------------

    /** True if {@code lsn} lies in an audio track; past the last track the last one counts. */
    private boolean isAudioTrack(int lsn) {
        List<CdImage.Track> t = image.tracks();
        for (int i = 1; i < t.size(); i++) {
            if (lsn < t.get(i).address) return !t.get(i - 1).isData();
        }
        return !t.get(t.size() - 1).isData();
    }

    /**
     * The track of {@code lsn} as the player looks it up, the index into the track list and the
     * sector inside its file; {@code null} in the pregap that the file does not hold.
     */
    private int findTrack(int lsn, int[] rel) {
        if (lsn < 0) return -1;
        List<CdImage.Track> t = image.tracks();
        for (int i = 0; i <= t.size(); i++) {
            int startOf = i < t.size() ? t.get(i).address - t.get(i).index1 : image.leadOut();
            if (startOf > lsn) {
                if (i == 0) {
                    rel[0] = 0;
                    return 0;
                }
                CdImage.Track tr = t.get(i - 1);
                int sector = lsn - (tr.address - tr.index1);
                if (sector < tr.pregap) return -1;
                rel[0] = sector;
                return i - 1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------

    /**
     * Plays from {@code startLsn} to {@code endLsn}. {@code delayFrames} silent sectors lead
     * it; {@code false} if the start is not in an audio track. The start and the first two
     * buffers happen now.
     */
    public boolean play(int startLsn, int endLsn, long now) {
        if (play != 0) end();
        lastPos = startLsn;
        start = startLsn;
        end = endLsn;
        setState(STATUS_NOT_SUPPORTED);
        if (!isAudioTrack(startLsn)) {
            setState(STATUS_PLAY_ERROR);
            return false;
        }
        play = 1;
        initDone = false;
        first = true;
        bufOn[0] = bufOn[1] = false;
        bufnum = 0;
        run(now);
        return true;
    }

    /** Stops the player; nothing is reported. */
    public void stop() {
        if (play != 0) end();
        paused = false;
        playState = STATUS_NOT_SUPPORTED;
    }

    public void pause(boolean pause, long now) {
        if ((pause && play != 0) || !pause) paused = pause;
        if (!pause) run(now);
    }

    // ---------------------------------------------------------------------------------------
    // The player
    // ---------------------------------------------------------------------------------------

    /** Runs the player until it has to wait for a buffer to play out, or ends. */
    private void run(long now) {
        while (play > 0) {
            if (!initDone) {
                initDone = true;
                startPlay();
            }
            if (bufOn[bufnum]) return;
            bufOn[bufnum] = false;
            if (idleFrames <= 0 && pos >= start && !isAudioTrack(pos)) {
                setState(STATUS_PLAY_ERROR);
                end();
                return;
            }
            if (pos < end && !paused) {
                boolean finish = fill(buffers[bufnum]);
                if (idleFrames <= 0) lastPos = pos;
                if (!bufOn[0] && !bufOn[1]) {
                    bufOn[bufnum] = true;
                    startStream(bufnum, now);
                }
                bufOn[bufnum] = true;
                first = false;
                if (finish) {
                    pos = end + 1;
                    setState(STATUS_PLAY_COMPLETE);
                    play = -1;
                    end();
                    return;
                }
            }
            if (!bufOn[0] && !bufOn[1] && paused) return;
            bufnum = 1 - bufnum;
        }
    }

    /** The play thread ends: the buffer playing is dropped at the next sample. */
    private void end() {
        play = 0;
        streamBuffer = -1;
    }

    /** The start of a play: the silent lead-in and the silence before a track change. */
    private void startPlay() {
        silentFrames = 0;
        pos = start;
        int[] rel = new int[1];
        if (findTrack(pos, rel) < 0 && findTrack(pos + 2 * 75, rel) < 0) {
            setState(STATUS_PLAY_ERROR);
        }
        idleFrames = START_DELAY_FRAMES;
        pos -= idleFrames;
        setState(STATUS_IN_PROGRESS);
        int t1 = findTrack(pos, rel);
        int t2 = findTrack(pos + 2 * 75, rel);
        if (t1 != t2) {
            for (int sector = pos; sector < pos + 2 * 75; sector++) {
                if (findTrack(sector, rel) == t2) break;
                silentFrames++;
            }
        }
    }

    /** Fills one buffer; {@code true} if it reached the end of the play. */
    private boolean fill(byte[] buf) {
        setState(STATUS_IN_PROGRESS);
        java.util.Arrays.fill(buf, (byte) 0);
        boolean finish = false;
        int[] rel = new int[1];
        for (int cnt = 0; cnt < SECTORS_PER_BUFFER && play > 0; cnt++) {
            int at = cnt * 2352;
            byte[] sub = new byte[96];
            int ti = findTrack(pos, rel);
            if (ti >= 0) {
                CdImage.Track t = image.tracks().get(ti);
                if (!t.isData() && t.source != null) {
                    long off = (long) rel[0] * t.size + t.offset;
                    if ((long) rel[0] * t.size + t.offset + t.size < t.filesize) {
                        try {
                            t.source.read(off, buf, at, t.size);
                        } catch (IOException e) {
                            java.util.Arrays.fill(buf, at, at + 2352, (byte) 0);
                        }
                    }
                }
                regenerateQ(sub, ti, pos);
            }
            if (idleFrames > 0 || silentFrames > 0) {
                if (idleFrames > 0) {
                    idleFrames--;
                    java.util.Arrays.fill(sub, (byte) 0);
                }
                if (silentFrames > 0) silentFrames--;
                java.util.Arrays.fill(buf, at, at + 2352, (byte) 0);
            }
            if (pos < start) java.util.Arrays.fill(buf, at, at + 2352, (byte) 0);
            byte[] inter = new byte[96];
            subToInterleaved(sub, inter);
            listener.subcode(inter);
            pos++;
            if (pos - SECTORS_PER_BUFFER < end && pos >= end) finish = true;
        }
        return finish;
    }

    /** The Q subchannel the player makes for a sector: track, index 1, track and disc time. */
    private void regenerateQ(byte[] sub, int trackIndex, int sector) {
        CdImage.Track t = image.tracks().get(trackIndex);
        int q = 12;
        sub[q] = (byte) ((t.ctrl << 4) | t.adr);
        sub[q + 1] = (byte) bcd(trackIndex + 1);
        sub[q + 2] = (byte) bcd(1);
        int msf = lsnToMsf(sector - t.address - 150);
        sub[q + 3] = (byte) bcd(msf >> 16);
        sub[q + 4] = (byte) bcd(msf >> 8);
        sub[q + 5] = (byte) bcd(msf);
        msf = lsnToMsf(sector);
        sub[q + 7] = (byte) bcd(msf >> 16);
        sub[q + 8] = (byte) bcd(msf >> 8);
        sub[q + 9] = (byte) bcd(msf);
    }

    private static int bcd(int v) {
        v &= 0xFF;
        return ((v / 10) << 4) | (v % 10);
    }

    private static int lsnToMsf(int lsn) {
        lsn += 150;
        int m = lsn / (60 * 75);
        lsn -= m * 60 * 75;
        int s = lsn / 75;
        int f = lsn - s * 75;
        return (m << 16) | (s << 8) | f;
    }

    /** Eight 12-byte channels P..W into 96 bytes holding one bit of each channel. */
    static void subToInterleaved(byte[] s, byte[] d) {
        for (int i = 0; i < 96; i++) {
            int dmask = 0x80;
            int smask = 1 << (7 - (i & 7));
            int v = 0;
            for (int j = 0; j < 8; j++) {
                if ((s[(i / 8) + j * 12] & smask) != 0) v |= dmask;
                dmask >>= 1;
            }
            d[i] = (byte) v;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Output stream
    // ---------------------------------------------------------------------------------------

    private void startStream(int buffer, long now) {
        streamBuffer = buffer;
        streamOffset = 0;
        streamLength = FRAMES_PER_BUFFER;
        if (!streamOn) {
            streamOn = true;
            nextSample = now + UNITS_PER_CCK;
        }
    }

    private void stopStream() {
        streamOn = false;
        streamBuffer = -1;
        sampleLeft = 0;
        sampleRight = 0;
    }

    /** The buffer that played out is refilled; the other one plays on, if it is ready. */
    private void bufferPlayed(int buffer, long now) {
        bufOn[buffer] = false;
        int other = 1 - buffer;
        if (bufOn[other]) {
            streamBuffer = other;
            streamOffset = 0;
            streamLength = FRAMES_PER_BUFFER;
        } else {
            streamBuffer = -1;
        }
        run(now);
    }

    /** Plays every sample due up to {@code now} (units of 1/512 colour clock). */
    public void advanceTo(long now) {
        while (streamOn && nextSample <= now) {
            long t = nextSample;
            if (streamBuffer < 0) {
                stopStream();
                return;
            }
            byte[] b = buffers[streamBuffer];
            int i = streamOffset * 4;
            sampleLeft = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            sampleRight = (short) ((b[i + 2] & 0xFF) | (b[i + 3] << 8));
            if (sampleObserver != null) sampleObserver.sample(t, sampleLeft, sampleRight);
            streamOffset++;
            nextSample = t + sampleUnits;
            if (--streamLength <= 0) bufferPlayed(streamBuffer, t);
        }
    }
}
