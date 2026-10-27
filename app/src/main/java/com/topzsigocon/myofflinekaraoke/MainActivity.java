package com.topzsigocon.myofflinekaraoke;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 101;
    private static final int PICK_SOUNDFONT = 102;
    private static final String PREFS = "karaoke_settings";
    private static final String PREF_SOUNDFONT_URI = "soundfont_uri";
    private static final String PREF_FOLDER_URI = "song_folder_uri";
    private LinearLayout root, songList;
    private EditText numberInput, searchInput;
    private TextView statusText, nowPlayingText, queueText, soundFontText;
    private Uri customSoundFontUri;
    private Uri selectedTree;
    private final List<Song> songs = new ArrayList<>();
    private final List<Song> queue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private static final int MAX_SONGS = 500;
    private static final int MAX_DEPTH = 20;

    static class Song {
        String number, title, artist, fileName;
        Uri uri;
        Song(String n, String t, String a, String f, Uri u) {
            number=n; title=t; artist=a; fileName=f; uri=u;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        String savedFolder = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_FOLDER_URI, null);
        if (savedFolder != null) {
            selectedTree = Uri.parse(savedFolder);
            boolean hasSavedPermission = false;
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(selectedTree) && permission.isReadPermission()) {
                    hasSavedPermission = true;
                    break;
                }
            }
            if (hasSavedPermission) {
                statusText.setText("Awtomatikong binubuksan ang huling song folder…");
                scanFolder();
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(PREF_FOLDER_URI)
                    .remove("song_folder_flags")
                    .apply();
                statusText.setText("Nawala ang access sa dating folder. Piliin muli ang song folder.");
                renderSongs();
            }
        } else {
            renderSongs();
        }
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(13, 18, 32));

        TextView title = label("MY OFFLINE KARAOKE", 22, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = label("MIDI / KAR • SONG NUMBER • OFFLINE LIBRARY", 11, Color.rgb(160,180,210), false);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout nowCard = new LinearLayout(this);
        nowCard.setOrientation(LinearLayout.VERTICAL);
        nowCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        nowCard.setBackground(bg(Color.rgb(28, 38, 61), 14));
        nowPlayingText = label("NOW PLAYING\nWala pang napipiling kanta", 15, Color.WHITE, true);
        nowCard.addView(nowPlayingText);
        queueText = label("Queue: 0 kanta", 12, Color.rgb(180,200,230), false);
        nowCard.addView(queueText);
        root.addView(nowCard, new LinearLayout.LayoutParams(-1, -2));

        numberInput = new EditText(this);
        numberInput.setSingleLine(true);
        numberInput.setHint("Ilagay ang song number, hal. 0125");
        numberInput.setHintTextColor(Color.GRAY);
        numberInput.setTextColor(Color.WHITE);
        numberInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        numberInput.setPadding(dp(12), 0, dp(12), 0);
        numberInput.setBackground(bg(Color.rgb(36, 47, 70), 10));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(52));
        np.topMargin = dp(12);
        root.addView(numberInput, np);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button find = button("Hanapin");
        Button add = button("Add sa Queue");
        Button play = button("Play Now");
        row.addView(find, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(play, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(row);
        find.setOnClickListener(v -> findByNumber(false, false));
        add.setOnClickListener(v -> findByNumber(true, false));
        play.setOnClickListener(v -> findByNumber(false, true));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button folder = button("Piliin ang SD Card Folder");
        Button next = button("Next");
        actions.addView(folder, new LinearLayout.LayoutParams(0, dp(48), 2));
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions);
        Button lyricsCheck = button("Lyrics Check");
        root.addView(lyricsCheck, new LinearLayout.LayoutParams(-1, dp(44)));
        lyricsCheck.setOnClickListener(v -> showLyricsForSelectedSong());

        Button soundFontSettings = button("SoundFont Settings");
        root.addView(soundFontSettings, new LinearLayout.LayoutParams(-1, dp(44)));
        soundFontText = label("SoundFont: Built-in default (synthesizer not yet connected)", 12, Color.rgb(170,190,220), false);
        root.addView(soundFontText, new LinearLayout.LayoutParams(-1, -2));
        soundFontSettings.setOnClickListener(v -> showSoundFontSettings());
        folder.setOnClickListener(v -> chooseFolder());
        next.setOnClickListener(v -> playNextQueued());

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search title o filename");
        searchInput.setHintTextColor(Color.GRAY);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackground(bg(Color.rgb(36, 47, 70), 10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48));
        sp.topMargin = dp(8);
        root.addView(searchInput, sp);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int start,int before,int count){ renderSongs(); }
            public void afterTextChanged(android.text.Editable e){}
        });

        statusText = label("Pumili ng folder para i-scan ang MIDI/KAR files.", 12, Color.rgb(170,190,220), false);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(8);
        root.addView(statusText, stp);

        TextView listTitle = label("SONGBOOK", 16, Color.WHITE, true);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(-1, dp(32));
        ltp.topMargin = dp(6);
        root.addView(listTitle, ltp);

        ScrollView scroll = new ScrollView(this);
        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(songList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }



    private void loadSoundFontSetting() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SOUNDFONT_URI, null);
        if (saved != null) {
            try { customSoundFontUri = Uri.parse(saved); } catch (Exception ignored) { customSoundFontUri = null; }
        }
        updateSoundFontLabel();
    }

    private void updateSoundFontLabel() {
        if (soundFontText == null) return;
        if (customSoundFontUri != null) {
            String name = queryDisplayName(customSoundFontUri);
            soundFontText.setText("SoundFont: Custom • " + name + "\\nSaved for future playback integration");
        } else {
            soundFontText.setText("SoundFont: Built-in default (synthesizer not yet connected)");
        }
    }

    private void showSoundFontSettings() {
        String current = customSoundFontUri == null
                ? "Kasalukuyan: Built-in default\\n(Paalaala: hindi pa nakakabit ang audio synthesizer.)"
                : "Kasalukuyan: " + queryDisplayName(customSoundFontUri)
                  + "\\n(Paalaala: naka-save ang napiling file; hindi pa ito ginagamit sa pagtugtog.)";
        String[] options = {"Pumili ng .SF2 mula sa phone/SD card", "Gamitin ang built-in default", "Kanselahin"};
        new AlertDialog.Builder(this)
            .setTitle("SoundFont Settings")
            .setMessage(current)
            .setItems(options, (dialog, which) -> {
                if (which == 0) chooseSoundFont();
                else if (which == 1) {
                    customSoundFontUri = null;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_SOUNDFONT_URI).apply();
                    updateSoundFontLabel();
                    Toast.makeText(this, "Pinili ang built-in default setting.", Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    private void chooseSoundFont() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_SOUNDFONT);
        } catch (Exception e) {
            Toast.makeText(this, "Hindi mabuksan ang file picker.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SOUNDFONT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String name = queryDisplayName(uri);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".sf2") && !name.toLowerCase(Locale.ROOT).endsWith(".sf3")) {
                new AlertDialog.Builder(this)
                    .setTitle("Hindi SoundFont file")
                    .setMessage("Pumili ng file na nagtatapos sa .sf2 o .sf3.")
                    .setPositiveButton("OK", null).show();
                return;
            }
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            customSoundFontUri = uri;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_SOUNDFONT_URI, uri.toString()).apply();
            updateSoundFontLabel();
            new AlertDialog.Builder(this)
                .setTitle("SoundFont saved")
                .setMessage("Napili: " + name + "\\nNai-save ang pagpili para sa susunod na pagbukas ng app. Hindi pa ginagamit ang SoundFont sa playback sa build na ito.")
                .setPositiveButton("OK", null).show();
        }

        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedTree = data.getData();
            int grantedFlags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            boolean permissionSaved = false;
            try {
                getContentResolver().takePersistableUriPermission(selectedTree, grantedFlags);
                permissionSaved = true;
            } catch (Exception e) {
                Toast.makeText(this,
                    "Hindi na-save ang access sa folder. Piliin ulit ang folder at payagan ang access.",
                    Toast.LENGTH_LONG).show();
            }

            if (permissionSaved && (grantedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_FOLDER_URI, selectedTree.toString())
                    .putInt("song_folder_flags", grantedFlags)
                    .apply();
                scanFolder();
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(PREF_FOLDER_URI)
                    .remove("song_folder_flags")
                    .apply();
                statusText.setText("Hindi na-save ang folder access. Piliin ulit ang folder.");
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) return c.getString(ix);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "SoundFont file" : last;
    }

    private void showLyricsForSelectedSong() {
        String n = numberInput.getText().toString().trim();
        Song chosen = null;
        if (!n.isEmpty()) {
            for (Song song : songs) if (song.number.equals(n)) { chosen = song; break; }
        }
        if (chosen == null && !songs.isEmpty()) chosen = songs.get(0);
        if (chosen == null) {
            Toast.makeText(this, "Pumili muna ng folder at kanta.", Toast.LENGTH_LONG).show();
            return;
        }
        final Song selected = chosen;
        statusText.setText("Sinusuri ang lyrics track…");
        scanExecutor.execute(() -> {
            String result;
            try (InputStream in = getContentResolver().openInputStream(selected.uri)) {
                if (in == null) throw new Exception("Hindi mabuksan ang file.");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int count;
                while ((count = in.read(buf)) != -1 && out.size() < 16 * 1024 * 1024) out.write(buf, 0, count);
                result = MidiLyricsReader.extract(out.toByteArray());
            } catch (Exception e) {
                result = "Hindi mabasa ang MIDI file: " + (e.getMessage() == null ? "unknown error" : e.getMessage());
            }
            final String display = result;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Lyrics Check • " + selected.title)
                .setMessage(display)
                .setPositiveButton("OK", null)
                .show());
        });
    }

    /**
     * Lightweight Standard MIDI File reader for embedded lyric/text meta events.
     * This previews embedded lyrics only; it is not an audio synthesizer.
     */
    static class MidiLyricsReader {
        static String extract(byte[] data) throws Exception {
            if (data.length < 14 || data[0]!='M' || data[1]!='T' || data[2]!='h' || data[3]!='d')
                return "Hindi mukhang valid na MIDI file.";
            int headerLen = int32(data,4);
            int tracks = u16(data,10);
            int pos = 8 + headerLen;
            StringBuilder lyrics = new StringBuilder();
            int lyricCount = 0;
            for (int t=0; t<tracks && pos+8<=data.length; t++) {
                if (data[pos]!='M' || data[pos+1]!='T' || data[pos+2]!='r' || data[pos+3]!='k')
                    throw new Exception("Hindi mabasa ang MIDI track.");
                int len = int32(data,pos+4); pos += 8;
                int end = Math.min(data.length, pos + Math.max(0,len));
                int p = pos; int running = -1;
                while (p < end) {
                    long[] vlq = readVlq(data,p,end); long delta=vlq[0]; p=(int)vlq[1];
                    if (p>=end) break;
                    int status = data[p]&255;
                    if (status < 0x80) {
                        if (running < 0) break;
                        status = running;
                    } else { p++; if (status < 0xF0) running=status; }
                    if (status == 0xFF) {
                        if (p>=end) break;
                        int type=data[p++]&255;
                        long[] l=readVlq(data,p,end); int n=(int)Math.min(l[0], end-l[1]); p=(int)l[1];
                        if (n<0) break;
                        if (type==0x05 || type==0x01) {
                            String text = new String(data,p,n,java.nio.charset.Charset.forName("UTF-8"));
                            text = clean(text);
                            if (!text.isEmpty() && (type==0x05 || looksLikeLyric(text))) {
                                if (lyrics.length()>0) lyrics.append("\n");
                                lyrics.append(text); lyricCount++;
                            }
                        }
                        p += n;
                        if (type==0x2F) break;
                    } else if (status==0xF0 || status==0xF7) {
                        long[] l=readVlq(data,p,end); p=(int)Math.min(end,l[1]+l[0]);
                    } else {
                        int command=status & 0xF0;
                        int bytes=(command==0xC0 || command==0xD0)?1:2;
                        p += bytes;
                    }
                }
                pos=end;
            }
            if (lyricCount==0) return "Walang nakitang naka-embed na lyrics sa file na ito.\n\nMaaaring may lyrics sa ibang format, o instrumental MIDI ito.";
            String body=lyrics.toString();
            if (body.length()>9000) body=body.substring(0,9000)+"\\n…(pinutol ang preview)";
            return "May nakita akong " + lyricCount + " lyric/text event(s).\\n\\n" + body +
                "\\n\\nPaalala: preview pa lang ito. Hindi pa kasama sa build na ito ang MIDI audio synthesizer o real-time word highlighting.";
        }
        static boolean looksLikeLyric(String s) {
            return s.matches(".*[A-Za-z].*") && !s.startsWith("@");
        }
        static String clean(String s) {
            return s.replace("\u0000","").replace("\r","").trim();
        }
        static int u16(byte[] b,int p) { return ((b[p]&255)<<8)|(b[p+1]&255); }
        static int int32(byte[] b,int p) { return ((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255); }
        static long[] readVlq(byte[] b,int p,int end) throws Exception {
            long v=0; int count=0;
            while(p<end && count++<4) { int x=b[p++]&255; v=(v<<7)|(x&127); if((x&128)==0) return new long[]{v,p}; }
            throw new Exception("Hindi valid ang MIDI event length.");
        }
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_FOLDER);
    }

    private void scanFolder() {
        if (selectedTree == null) return;
        statusText.setText("Ini-scan ang folder sa background…");
        Toast.makeText(this, "Nagsisimula ang pag-scan. Maaaring magtagal kung maraming files.", Toast.LENGTH_SHORT).show();
        final Uri tree = selectedTree;
        scanExecutor.execute(() -> {
            List<Song> found = new ArrayList<>();
            String error = null;
            try {
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                };
                scanChildren(tree, childrenUri, projection, found, 0);
                Collections.sort(found, Comparator.comparing(song -> song.title.toLowerCase(Locale.ROOT)));
                for (int i = 0; i < found.size(); i++) {
                    found.get(i).number = String.format(Locale.US, "%04d", i + 1);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? "Hindi mabasa ang folder." : e.getMessage();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                songs.clear();
                songs.addAll(found);
                if (finalError != null) {
                    statusText.setText("Hindi ma-scan ang folder. Pumili ng folder na may MIDI/KAR files.");
                    Toast.makeText(this, "Hindi mabasa ang folder: " + finalError, Toast.LENGTH_LONG).show();
                } else {
                    statusText.setText("Nakita: " + songs.size() + " MIDI/KAR file(s). " +
                        (songs.size() >= MAX_SONGS ? "Limit na " + MAX_SONGS + " kanta ang ipinapakita. " : "") +
                        "Auto-number ayon sa filename.");
                }
                renderSongs();
            });
        });
    }

    private void scanChildren(Uri tree, Uri childrenUri, String[] projection,
                              List<Song> found, int depth) {
        if (depth > MAX_DEPTH || found.size() >= MAX_SONGS) return;
        try (Cursor c = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && found.size() < MAX_SONGS) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (id == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    Uri nested = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
                    scanChildren(tree, nested, projection, found, depth + 1);
                } else if (name != null) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".mid") || lower.endsWith(".midi") || lower.endsWith(".kar")) {
                        Uri childUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        String title = name.replaceFirst("(?i)\\.(mid|midi|kar)$", "");
                        found.add(new Song("", title, "", name, childUri));
                    }
                }
            }
        } catch (Exception ignored) {
            // Skip folders that the selected storage provider cannot read.
        }
    }

    @Override protected void onDestroy() {
        scanExecutor.shutdownNow();
        super.onDestroy();
    }

    private void renderSongs() {
        if (songList == null) return;
        songList.removeAllViews();
        String q = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (Song song : songs) {
            if (!q.isEmpty() && !song.title.toLowerCase(Locale.ROOT).contains(q)
                    && !song.fileName.toLowerCase(Locale.ROOT).contains(q)) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(8), dp(12), dp(8));
            card.setBackground(bg(Color.rgb(24, 32, 51), 10));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(6);
            songList.addView(card, cp);
            TextView name = label(song.number + "   " + song.title, 15, Color.WHITE, true);
            card.addView(name);
            TextView filename = label(song.fileName, 11, Color.rgb(160,180,210), false);
            card.addView(filename);
            card.setOnClickListener(v -> {
                numberInput.setText(song.number);
                numberInput.setSelection(numberInput.getText().length());
            });
        }
        if (songs.isEmpty()) {
            TextView empty = label("Wala pang kanta. Pindutin ang “Piliin ang SD Card Folder”.", 13, Color.LTGRAY, false);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            songList.addView(empty);
        }
    }

    private Song findSong(String number) {
        String n = number.trim();
        for (Song s : songs) if (s.number.equals(n) || s.number.equals(String.format(Locale.US, "%04d", parseIntSafe(n)))) return s;
        return null;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private void findByNumber(boolean addToQueue, boolean playNow) {
        Song s = findSong(numberInput.getText().toString());
        if (s == null) {
            Toast.makeText(this, "Walang kanta sa song number na iyan.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playNow) {
            nowPlayingText.setText("SELECTED SONG\n" + s.number + " — " + s.title
                    + "\n\nMIDI playback engine ay hindi pa kasama sa prototype.");
            Toast.makeText(this, "Napili ang kanta. Idadagdag ang MIDI engine sa susunod na build.", Toast.LENGTH_LONG).show();
        } else if (addToQueue) {
            queue.add(s);
            updateQueueText();
            Toast.makeText(this, "Naidagdag sa queue: " + s.title, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, s.number + " — " + s.title, Toast.LENGTH_SHORT).show();
        }
    }

    private void playNextQueued() {
        if (queue.isEmpty()) {
            Toast.makeText(this, "Walang kanta sa queue.", Toast.LENGTH_SHORT).show();
            return;
        }
        currentQueueIndex++;
        if (currentQueueIndex >= queue.size()) currentQueueIndex = 0;
        Song s = queue.get(currentQueueIndex);
        nowPlayingText.setText("SELECTED FROM QUEUE\n" + s.number + " — " + s.title
                + "\n\nMIDI playback engine ay hindi pa kasama sa prototype.");
        updateQueueText();
    }

    private void updateQueueText() {
        queueText.setText("Queue: " + queue.size() + " kanta");
    }
}
