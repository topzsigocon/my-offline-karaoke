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
        final String lyric;
        long micros;
        String lyricLine;
        String nextLyricLine;
        int lyricStart = -1;
        int lyricEnd = -1;
        Event(long tick, byte[] message, int tempo) { this(tick, message, tempo, null); }
        Event(long tick, byte[] message, int tempo, String lyric) { this.tick=tick; this.message=message; this.tempo=tempo; this.lyric=lyric; }
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
        annotateLyricLines(all);
        return all;
    }

    /** Groups word/fragment lyric meta-events into complete display lines and links the next line. */
    private static void annotateLyricLines(List<Event> events) {
        List<List<Event>> lines = new ArrayList<>();
        List<Event> pending = new ArrayList<>();
        for (Event event : events) {
            if (event.lyric == null) continue;
            if (isLineBreak(event.lyric)) {
                if (!pending.isEmpty()) { lines.add(new ArrayList<>(pending)); pending.clear(); }
            } else {
                pending.add(event);
            }
        }
        if (!pending.isEmpty()) lines.add(new ArrayList<>(pending));
        List<String> textLines = new ArrayList<>();
        for (List<Event> words : lines) textLines.add(buildLine(words));
        for (int li = 0; li < lines.size(); li++) {
            List<Event> words = lines.get(li);
            String full = textLines.get(li);
            if (full.isEmpty()) continue;
            int cursor = 0;
            for (Event word : words) {
                String part = word.lyric;
                int at = full.indexOf(part, cursor);
                if (at < 0) at = cursor;
                word.lyricLine = full;
                word.lyricStart = Math.max(0, Math.min(full.length(), at));
                word.lyricEnd = Math.max(word.lyricStart, Math.min(full.length(), at + part.length()));
                word.nextLyricLine = li + 1 < textLines.size() ? textLines.get(li + 1) : "";
                cursor = Math.min(full.length(), at + part.length());
            }
        }
    }

    private static String buildLine(List<Event> words) {
        StringBuilder line = new StringBuilder();
        for (Event word : words) line.append(word.lyric);
        return line.toString().trim();
    }

    private static boolean isLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static void assignLine(List<Event> words) {
        if (words.isEmpty()) return;
        StringBuilder line = new StringBuilder();
        int[] starts = new int[words.size()];
        int[] ends = new int[words.size()];
        for (int i = 0; i < words.size(); i++) {
            String part = words.get(i).lyric;
            starts[i] = line.length();
            line.append(part);
            ends[i] = line.length();
        }
        String full = line.toString();
        int left = 0, right = full.length();
        while (left < right && Character.isWhitespace(full.charAt(left))) left++;
        while (right > left && Character.isWhitespace(full.charAt(right - 1))) right--;
        full = full.substring(left, right);
        if (full.isEmpty()) return;
        for (int i = 0; i < words.size(); i++) {
            Event word = words.get(i);
            word.lyricLine = full;
            word.lyricStart = Math.max(0, Math.min(full.length(), starts[i] - left));
            word.lyricEnd = Math.max(word.lyricStart, Math.min(full.length(), ends[i] - left));
        }
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
                } else if (type == 0x05 && n > 0) {
                    byte[] lyricBytes = new byte[n]; in.readFully(lyricBytes);
                    String rawLyric = new String(lyricBytes, java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", "");
                    if (rawLyric.indexOf(13) >= 0 || rawLyric.indexOf(10) >= 0) {
                        out.add(new Event(tick, null, 0, "\r"));
                    } else if (!rawLyric.trim().isEmpty()) {
                        // Keep spaces: MIDI lyric tracks often store one syllable/word per event.
                        out.add(new Event(tick, null, 0, rawLyric));
                    }
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
