package com.topzsigocon.myofflinekaraoke;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Small Standard MIDI File parser for type 0/1 files, including tempo maps and running status. */
final class MidiFileParser {
    static final class Event {
        final long tick;
        final byte[] message;
        final int tempo;
        long micros;
        Event(long tick, byte[] message, int tempo) { this.tick=tick; this.message=message; this.tempo=tempo; }
    }
    static final class TrackData { final List<Event> events = new ArrayList<>(); }
    private MidiFileParser() {}

    static List<Event> parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != 0x4d546864) throw new IOException("Hindi valid na MIDI header.");
        int headerLen = in.readInt();
        if (headerLen < 6 || headerLen > data.length - 8) throw new IOException("Hindi valid ang MIDI header length.");
        int format = in.readUnsignedShort();
        int tracks = in.readUnsignedShort();
        int division = in.readUnsignedShort();
        if (format > 1 || tracks < 1 || division == 0 || (division & 0x8000) != 0) throw new IOException("Hindi suportadong MIDI format o timing.");
        if (headerLen > 6) in.skipBytes(headerLen - 6);
        List<Event> all = new ArrayList<>();
        for (int ti=0; ti<tracks; ti++) {
            if (in.available() < 8) break;
            int chunk = in.readInt(); int len = in.readInt();
            if (chunk != 0x4d54726b || len < 0 || len > in.available()) throw new IOException("Hindi mabasa ang MIDI track.");
            byte[] trackBytes = new byte[len]; in.readFully(trackBytes);
            parseTrack(trackBytes, all);
        }
        Collections.sort(all, Comparator.comparingLong(e -> e.tick));
        long prevTick=0, elapsed=0; int tempo=500000;
        for (Event e : all) {
            long delta = Math.max(0, e.tick-prevTick);
            elapsed += (delta * (long)tempo) / division;
            e.micros=elapsed; prevTick=e.tick;
            if (e.tempo > 0) tempo=e.tempo;
        }
        return all;
    }

    private static void parseTrack(byte[] bytes, List<Event> out) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        long tick=0; int running=0;
        while (in.available() > 0) {
            tick += readVarLen(in);
            int first=in.readUnsignedByte(); int status;
            if (first < 0x80) { if (running == 0) throw new IOException("MIDI running status error."); status=running; }
            else status=first;
            if (status == 0xff) {
                int type=in.readUnsignedByte(); int n=readVarLen(in);
                if (n < 0 || n > in.available()) throw new IOException("MIDI meta event error.");
                if (type == 0x51 && n == 3) {
                    int tempo=(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
                    out.add(new Event(tick,null,tempo));
                } else in.skipBytes(n);
                running=0;
            } else if (status == 0xf0 || status == 0xf7) {
                int n=readVarLen(in); if (n < 0 || n > in.available()) throw new IOException("MIDI SysEx error."); in.skipBytes(n); running=0;
            } else {
                running=status;
                int kind=status & 0xf0;
                int d1 = first < 0x80 ? first : in.readUnsignedByte();
                int d2 = (kind == 0xc0 || kind == 0xd0) ? -1 : in.readUnsignedByte();
                if (kind >= 0x80 && kind <= 0xe0) {
                    byte[] msg = d2 < 0 ? new byte[]{(byte)status,(byte)d1} : new byte[]{(byte)status,(byte)d1,(byte)d2};
                    out.add(new Event(tick,msg,0));
                }
            }
        }
    }
    private static int readVarLen(DataInputStream in) throws IOException {
        int value=0; int b;
        do { b=in.readUnsignedByte(); value=(value<<7)|(b&0x7f); } while ((b&0x80)!=0);
        return value;
    }
}
