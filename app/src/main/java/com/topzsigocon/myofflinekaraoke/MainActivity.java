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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 101;
    private LinearLayout root, songList;
    private EditText numberInput, searchInput;
    private TextView statusText, nowPlayingText, queueText;
    private Uri selectedTree;
    private final List<Song> songs = new ArrayList<>();
    private final List<Song> queue = new ArrayList<>();
    private int currentQueueIndex = -1;

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
        renderSongs();
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

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            selectedTree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(selectedTree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            scanFolder();
        }
    }

    private void scanFolder() {
        songs.clear();
        if (selectedTree == null) return;
        try {
            String docId = DocumentsContract.getTreeDocumentId(selectedTree);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(selectedTree, docId);
            String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE };
            scanChildren(childrenUri, projection);
            Collections.sort(songs, Comparator.comparing(s -> s.title.toLowerCase(Locale.ROOT)));
            for (int i=0; i<songs.size(); i++) songs.get(i).number = String.format(Locale.US, "%04d", i+1);
            statusText.setText("Nakita: " + songs.size() + " MIDI/KAR file(s). Song numbers ay auto-generated sa filename order.");
        } catch (Exception e) {
            statusText.setText("Hindi ma-scan ang folder. Subukan pumili ng folder na may MIDI/KAR files.");
            Toast.makeText(this, "Hindi mabasa ang folder: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        renderSongs();
    }

    private void scanChildren(Uri childrenUri, String[] projection) {
        try (Cursor c = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(selectedTree, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    Uri nested = DocumentsContract.buildChildDocumentsUriUsingTree(selectedTree, id);
                    scanChildren(nested, projection);
                } else if (name != null && (name.toLowerCase(Locale.ROOT).endsWith(".mid")
                        || name.toLowerCase(Locale.ROOT).endsWith(".midi")
                        || name.toLowerCase(Locale.ROOT).endsWith(".kar"))) {
                    String title = name.replaceFirst("(?i)\\.(mid|midi|kar)$", "");
                    songs.add(new Song("", title, "", name, childUri));
                }
            }
        } catch (Exception ignored) {}
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
