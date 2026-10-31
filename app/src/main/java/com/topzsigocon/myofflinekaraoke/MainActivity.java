package com.topzsigocon.myofflinekaraoke;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
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
import android.widget.FrameLayout;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.media.MediaPlayer;
import android.view.Surface;
import android.widget.Toast;
import android.app.AlertDialog;
import android.provider.Settings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
    private TextView statusText, nowPlayingText, queueText, soundFontText, liveLyricsText;
    private Uri customSoundFontUri;
    private Uri selectedTree;
    private final List<Song> songs = new ArrayList<>();
    private final List<Song> queue = new ArrayList<>();
    private int currentQueueIndex = -1;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private static final int MAX_VISIBLE_ROWS = 100;
    private static final int MAX_DEPTH = 30;
    private SongDb songDb;
    private BroadcastReceiver floatingRemoteReceiver;
    private static final int PICK_BACKGROUND_VIDEO = 103;
    private static final String PREF_VIDEO_URI = "karaoke_background_video_uri";
    private Uri backgroundVideoUri;
    private FrameLayout karaokeFrame;
    private TextureView karaokeVideo;
    private MediaPlayer backgroundPlayer;
    private Surface videoSurface;
    private TextView karaokeLyrics, karaokeNextLyrics, karaokeQueue, karaokeSongTitle;
    private static final String PREF_LYRICS_POSITION = "lyrics_position";
    private static final String PREF_LYRICS_ALIGNMENT = "lyrics_alignment";
    private String lyricsPosition = "Bottom";
    private String lyricsAlignment = "Centered";
    private String currentSongTitle = "My Offline Karaoke";
    private View normalRoot;
    private boolean karaokeMode = false;

    static class Song {
        String number, title, artist, fileName;
        Uri uri;
        Song(String n, String t, String a, String f, Uri u) {
            number=n; title=t; artist=a; fileName=f; uri=u;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        songDb = new SongDb(this);
        buildUi();
        lyricsPosition = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LYRICS_POSITION, "Center");
        lyricsAlignment = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LYRICS_ALIGNMENT, "Centered");
        loadSoundFontSetting();
        String savedVideo = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_VIDEO_URI, null);
        if (savedVideo != null) try { backgroundVideoUri = Uri.parse(savedVideo); } catch (Exception ignored) {}
        registerFloatingRemoteReceiver();
        loadCachedSongs();
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
                if (songs.isEmpty()) {
                    statusText.setText("Unang scan ng song folder…");
                    scanFolder();
                } else {
                    statusText.setText("Naka-load ang " + songs.size() +
                        " kanta mula sa naka-save na catalog. Pindutin ang folder button para i-refresh ang library.");
                }
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

    private LinearLayout tabBar;
    private LinearLayout libraryPage, nowPlayingPage, settingsPage;
    private TextView tabLibrary, tabNow, tabKaraoke, tabSettings;
    private int activeTab = 0;

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(8, 15, 30));

        TextView title = label("🎤  MY OFFLINE KARAOKE", 20, Color.rgb(255, 205, 75), true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabBar.setBackground(bg(Color.rgb(20, 33, 58), 14));
        tabLibrary = tabButton("♫\nLibrary");
        tabNow = tabButton("▶\nNow Playing");
        tabKaraoke = tabButton("🎤\nKaraoke");
        tabSettings = tabButton("⚙\nSettings");
        tabBar.addView(tabLibrary, new LinearLayout.LayoutParams(0, dp(54), 1));
        tabBar.addView(tabNow, new LinearLayout.LayoutParams(0, dp(54), 1));
        tabBar.addView(tabKaraoke, new LinearLayout.LayoutParams(0, dp(54), 1));
        tabBar.addView(tabSettings, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(tabBar);

        FrameLayout pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));

        libraryPage = new LinearLayout(this);
        libraryPage.setOrientation(LinearLayout.VERTICAL);
        libraryPage.setPadding(dp(2), dp(10), dp(2), dp(4));
        TextView libraryTitle = label("SONGBOOK / LIBRARY", 18, Color.WHITE, true);
        libraryPage.addView(libraryTitle, new LinearLayout.LayoutParams(-1, dp(34)));
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search title, artist o filename...");
        searchInput.setHintTextColor(Color.GRAY);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackground(bg(Color.rgb(29, 43, 69), 12));
        libraryPage.addView(searchInput, new LinearLayout.LayoutParams(-1, dp(46)));
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int start,int before,int count){ renderSongs(); }
            public void afterTextChanged(android.text.Editable e){}
        });
        statusText = label("Pumili ng folder para i-scan ang MIDI/KAR files.", 12, Color.rgb(170,190,220), false);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2); stp.topMargin = dp(6);
        libraryPage.addView(statusText, stp);
        ScrollView scroll = new ScrollView(this);
        songList = new LinearLayout(this); songList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(songList);
        libraryPage.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(libraryPage, new FrameLayout.LayoutParams(-1, -1));

        nowPlayingPage = new LinearLayout(this);
        nowPlayingPage.setOrientation(LinearLayout.VERTICAL);
        nowPlayingPage.setPadding(dp(4), dp(12), dp(4), dp(8));
        TextView npTitle = label("NOW PLAYING", 19, Color.rgb(255,205,75), true);
        nowPlayingPage.addView(npTitle, new LinearLayout.LayoutParams(-1, dp(34)));
        nowPlayingText = label("Wala pang napipiling kanta", 17, Color.WHITE, true);
        nowPlayingText.setPadding(dp(12), dp(14), dp(12), dp(14));
        nowPlayingText.setBackground(bg(Color.rgb(24, 39, 66), 14));
        nowPlayingPage.addView(nowPlayingText, new LinearLayout.LayoutParams(-1, -2));
        liveLyricsText = label("♫  Hihintayin ang lyrics…", 17, Color.rgb(255, 210, 90), true);
        liveLyricsText.setGravity(Gravity.CENTER);
        liveLyricsText.setPadding(dp(8), dp(12), dp(8), dp(12));
        nowPlayingPage.addView(liveLyricsText, new LinearLayout.LayoutParams(-1, -2));
        queueText = label("Queue: 0 kanta", 14, Color.rgb(190,210,235), true);
        queueText.setPadding(dp(8), dp(8), dp(8), dp(8));
        nowPlayingPage.addView(queueText, new LinearLayout.LayoutParams(-1, -2));
        Button queueButton = button("☷  Tingnan ang Queue");
        nowPlayingPage.addView(queueButton, new LinearLayout.LayoutParams(-1, dp(44)));
        queueButton.setOnClickListener(v -> showKaraokeQueue());

        numberInput = new EditText(this);
        numberInput.setSingleLine(true);
        numberInput.setHint("Ilagay ang song number");
        numberInput.setHintTextColor(Color.GRAY);
        numberInput.setTextColor(Color.WHITE);
        numberInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        numberInput.setPadding(dp(12), 0, dp(12), 0);
        numberInput.setBackground(bg(Color.rgb(29,43,69), 12));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(50)); np.topMargin = dp(8);
        nowPlayingPage.addView(numberInput, np);
        LinearLayout numberActions = new LinearLayout(this);
        Button add = button("+ Queue"); Button play = button("▶ Play Now");
        numberActions.addView(add, new LinearLayout.LayoutParams(0, dp(46), 1));
        numberActions.addView(play, new LinearLayout.LayoutParams(0, dp(46), 1));
        nowPlayingPage.addView(numberActions);
        add.setOnClickListener(v -> findByNumber(true, false));
        play.setOnClickListener(v -> findByNumber(false, true));

        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        Button search = button("⌕ Search"); Button pause = button("Ⅱ Pause"); Button next = button("▶| Next"); Button stop = button("■ Stop");
        controls.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(pause, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        nowPlayingPage.addView(controls);
        search.setOnClickListener(v -> { showTab(0); searchInput.requestFocus(); ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); });
        pause.setOnClickListener(v -> sendMidiService(MidiPlaybackService.ACTION_PAUSE_TOGGLE, null, null));
        next.setOnClickListener(v -> playNextQueued());
        stop.setOnClickListener(v -> { sendMidiService(MidiPlaybackService.ACTION_STOP, null, null); nowPlayingText.setText("Nakahinto"); });
        Button enterKaraoke = button("⛶  Buksan ang Karaoke Mode");
        nowPlayingPage.addView(enterKaraoke, new LinearLayout.LayoutParams(-1, dp(50)));
        enterKaraoke.setOnClickListener(v -> enterKaraokeMode());
        Button floating = button("🎤  Enable Floating Controller");
        nowPlayingPage.addView(floating, new LinearLayout.LayoutParams(-1, dp(46)));
        floating.setOnClickListener(v -> enableFloatingController());
        pages.addView(nowPlayingPage, new FrameLayout.LayoutParams(-1, -1));

        settingsPage = new LinearLayout(this);
        settingsPage.setOrientation(LinearLayout.VERTICAL);
        settingsPage.setPadding(dp(4), dp(12), dp(4), dp(8));
        TextView settingsTitle = label("SETTINGS", 19, Color.rgb(255,205,75), true);
        settingsPage.addView(settingsTitle, new LinearLayout.LayoutParams(-1, dp(38)));
        Button folder = button("📁  Piliin / I-refresh ang Music Folder");
        settingsPage.addView(folder, new LinearLayout.LayoutParams(-1, dp(50)));
        folder.setOnClickListener(v -> chooseFolder());
        Button chooseVideo = button("🎞  Pumili ng Shared Background Video");
        settingsPage.addView(chooseVideo, new LinearLayout.LayoutParams(-1, dp(50)));
        chooseVideo.setOnClickListener(v -> chooseBackgroundVideo());
        Button soundFontSettings = button("♫  SoundFont Settings");
        settingsPage.addView(soundFontSettings, new LinearLayout.LayoutParams(-1, dp(50)));
        soundFontSettings.setOnClickListener(v -> showSoundFontSettings());
        soundFontText = label("SoundFont: Built-in default (custom playback support depends on synthesizer)", 12, Color.rgb(170,190,220), false);
        settingsPage.addView(soundFontText, new LinearLayout.LayoutParams(-1, -2));
        Button lyricsCheck = button("Lyrics Check");
        settingsPage.addView(lyricsCheck, new LinearLayout.LayoutParams(-1, dp(46)));
        lyricsCheck.setOnClickListener(v -> showLyricsForSelectedSong());
        Button lyricsPositionButton = button("Lyrics Position: " + lyricsPosition);
        settingsPage.addView(lyricsPositionButton, new LinearLayout.LayoutParams(-1, dp(46)));
        lyricsPositionButton.setOnClickListener(v -> {
            String[] options = {"Center", "Bottom"};
            new AlertDialog.Builder(this).setTitle("Lyrics Position")
                .setItems(options, (d, which) -> {
                    lyricsPosition = options[which];
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LYRICS_POSITION, lyricsPosition).apply();
                    lyricsPositionButton.setText("Lyrics Position: " + lyricsPosition);
                    updateLyricsLayout();
                }).show();
        });
        Button lyricsAlignmentButton = button("Lyrics Alignment: " + lyricsAlignment);
        settingsPage.addView(lyricsAlignmentButton, new LinearLayout.LayoutParams(-1, dp(46)));
        lyricsAlignmentButton.setOnClickListener(v -> {
            String[] options = {"Centered", "Opposing (Left / Right)"};
            new AlertDialog.Builder(this).setTitle("Lyrics Alignment")
                .setItems(options, (d, which) -> {
                    lyricsAlignment = which == 0 ? "Centered" : "Opposing";
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LYRICS_ALIGNMENT, lyricsAlignment).apply();
                    lyricsAlignmentButton.setText("Lyrics Alignment: " + lyricsAlignment);
                    updateLyricsLayout();
                }).show();
        });
        TextView appearance = label("UI / APPEARANCE", 15, Color.WHITE, true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(38)); ap.topMargin = dp(12);
        settingsPage.addView(appearance, ap);
        Button karaokeToggle = button("⛶  Preview Karaoke Mode");
        settingsPage.addView(karaokeToggle, new LinearLayout.LayoutParams(-1, dp(46)));
        karaokeToggle.setOnClickListener(v -> enterKaraokeMode());
        TextView note = label("Tip: Pumili ng video nang isang beses; iyon ang gagamiting background sa lahat ng kanta. Maaari itong i-crop sa portrait screen.", 12, Color.rgb(170,190,220), false);
        note.setPadding(dp(4), dp(12), dp(4), dp(4));
        settingsPage.addView(note);
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));

        tabLibrary.setOnClickListener(v -> showTab(0));
        tabNow.setOnClickListener(v -> showTab(1));
        tabKaraoke.setOnClickListener(v -> enterKaraokeMode());
        tabSettings.setOnClickListener(v -> showTab(3));
        normalRoot = root;
        showTab(0);
        setContentView(root);
    }

    private TextView tabButton(String text) {
        TextView v = label(text, 12, Color.rgb(190,205,225), true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(bg(Color.TRANSPARENT, 10));
        return v;
    }

    private void showTab(int tab) {
        activeTab = tab;
        libraryPage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        nowPlayingPage.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        TextView[] tabs = {tabLibrary, tabNow, tabKaraoke, tabSettings};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == tab ? Color.rgb(20,30,48) : Color.rgb(190,205,225));
            tabs[i].setBackground(bg(i == tab ? Color.rgb(255,204,74) : Color.TRANSPARENT, 10));
        }
    }



    private void chooseBackgroundVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_BACKGROUND_VIDEO);
    }

    private void enterKaraokeMode() {
        if (karaokeFrame == null) buildKaraokeMode();
        karaokeMode = true;
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(karaokeFrame);
        if (karaokeVideo.isAvailable()) startBackgroundVideo(karaokeVideo.getSurfaceTexture());
        karaokeQueue.setText("QUEUE: " + queue.size());
        if (karaokeSongTitle != null) karaokeSongTitle.setText(currentSongTitle);
        startFloatingControllerIfAllowed();
    }

    private void buildKaraokeMode() {
        karaokeFrame = new FrameLayout(this);
        karaokeFrame.setBackgroundColor(Color.BLACK);
        karaokeVideo = new TextureView(this);
        karaokeVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { startBackgroundVideo(surface); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { applyVideoCenterCrop(width, height); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        karaokeFrame.addView(karaokeVideo, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackgroundColor(0x55000000);
        karaokeFrame.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        karaokeSongTitle = label(currentSongTitle, 18, Color.WHITE, true);
        karaokeSongTitle.setGravity(Gravity.CENTER);
        karaokeSongTitle.setPadding(dp(12), dp(8), dp(12), dp(8));
        karaokeSongTitle.setBackground(bg(0x990D1830, 18));
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleParams.setMargins(dp(18), dp(18), dp(118), 0);
        karaokeFrame.addView(karaokeSongTitle, titleParams);

        LinearLayout lyricsColumn = new LinearLayout(this);
        lyricsColumn.setOrientation(LinearLayout.VERTICAL);
        lyricsColumn.setGravity(Gravity.CENTER);
        lyricsColumn.setPadding(dp(18), dp(14), dp(18), dp(14));
        karaokeLyrics = label("Hihintayin ang lyrics…", 25, Color.WHITE, true);
        karaokeLyrics.setGravity(Gravity.CENTER);
        karaokeLyrics.setShadowLayer(dp(4), 1, 2, Color.BLACK);
        karaokeLyrics.setMaxLines(2);
        karaokeLyrics.setPadding(dp(4), dp(4), dp(4), dp(4));
        lyricsColumn.addView(karaokeLyrics, new LinearLayout.LayoutParams(-1, -2));
        karaokeNextLyrics = label("", 22, Color.WHITE, true);
        karaokeNextLyrics.setGravity(Gravity.CENTER);
        karaokeNextLyrics.setShadowLayer(dp(4), 1, 2, Color.BLACK);
        karaokeNextLyrics.setMaxLines(2);
        karaokeNextLyrics.setPadding(dp(4), dp(4), dp(4), dp(4));
        lyricsColumn.addView(karaokeNextLyrics, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lyricsParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lyricsParams.setMargins(dp(12), 0, dp(12), dp(82));
        karaokeFrame.addView(lyricsColumn, lyricsParams);
        updateLyricsLayout();

        karaokeQueue = label("QUEUE: 0", 15, Color.WHITE, true);
        karaokeQueue.setPadding(dp(14), dp(9), dp(14), dp(9));
        karaokeQueue.setBackground(bg(0x990D1830, 24));
        FrameLayout.LayoutParams queueParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        queueParams.setMargins(0, dp(18), dp(18), 0);
        karaokeFrame.addView(karaokeQueue, queueParams);
        karaokeQueue.setOnClickListener(v -> showKaraokeQueue());

        Button showUi = button("◉ Show UI / Tabs");
        FrameLayout.LayoutParams showParams = new FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM | Gravity.END);
        showParams.setMargins(0, 0, dp(18), dp(22));
        karaokeFrame.addView(showUi, showParams);
        showUi.setOnClickListener(v -> leaveKaraokeMode());

    }

    private void startBackgroundVideo(SurfaceTexture texture) {
        if (backgroundVideoUri == null || texture == null || isFinishing()) return;
        try {
            if (backgroundPlayer != null) {
                if (backgroundPlayer.isPlaying()) return;
                backgroundPlayer.start();
                return;
            }
            backgroundPlayer = new MediaPlayer();
            backgroundPlayer.setDataSource(this, backgroundVideoUri);
            videoSurface = new Surface(texture);
            backgroundPlayer.setSurface(videoSurface);
            backgroundPlayer.setVolume(0f, 0f);
            backgroundPlayer.setLooping(true);
            backgroundPlayer.setOnPreparedListener(mp -> {
                applyVideoCenterCrop(karaokeVideo.getWidth(), karaokeVideo.getHeight());
                mp.start();
            });
            backgroundPlayer.setOnVideoSizeChangedListener((mp, width, height) -> applyVideoCenterCrop(karaokeVideo.getWidth(), karaokeVideo.getHeight()));
            backgroundPlayer.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "Hindi mabuksan ang background video.", Toast.LENGTH_LONG).show();
                releaseBackgroundVideo();
                return true;
            });
            backgroundPlayer.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Video error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            releaseBackgroundVideo();
        }
    }

    /** Center-crop the video without stretching its original aspect ratio. */
    private void applyVideoCenterCrop(int viewWidth, int viewHeight) {
        if (backgroundPlayer == null || karaokeVideo == null || viewWidth <= 0 || viewHeight <= 0) return;
        int videoWidth = backgroundPlayer.getVideoWidth();
        int videoHeight = backgroundPlayer.getVideoHeight();
        if (videoWidth <= 0 || videoHeight <= 0) return;
        float scale = Math.max((float)viewWidth / videoWidth, (float)viewHeight / videoHeight);
        float scaledWidth = videoWidth * scale;
        float scaledHeight = videoHeight * scale;
        float dx = (viewWidth - scaledWidth) / 2f;
        float dy = (viewHeight - scaledHeight) / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        karaokeVideo.setTransform(matrix);
    }

    private void releaseBackgroundVideo() {
        if (backgroundPlayer != null) {
            try { backgroundPlayer.stop(); } catch (Exception ignored) {}
            try { backgroundPlayer.release(); } catch (Exception ignored) {}
            backgroundPlayer = null;
        }
        if (videoSurface != null) { try { videoSurface.release(); } catch (Exception ignored) {} videoSurface = null; }
    }

    private void updateLyricsLayout() {
        if (karaokeLyrics == null || karaokeNextLyrics == null || karaokeFrame == null) return;
        int gravity = "Center".equalsIgnoreCase(lyricsPosition) ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM;
        // karaokeLyrics is a child of lyricsColumn; lyricsColumn is a direct child of karaokeFrame.
        View lyricsContainer = (View) karaokeLyrics.getParent();
        if (lyricsContainer == null) return;
        ViewGroup.LayoutParams rawParams = lyricsContainer.getLayoutParams();
        if (!(rawParams instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawParams;
        lp.gravity = Gravity.CENTER_HORIZONTAL | gravity;
        lp.bottomMargin = "Bottom".equalsIgnoreCase(lyricsPosition) ? dp(82) : 0;
        lyricsContainer.setLayoutParams(lp);
        if ("Opposing".equalsIgnoreCase(lyricsAlignment)) {
            karaokeLyrics.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            karaokeNextLyrics.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        } else {
            karaokeLyrics.setGravity(Gravity.CENTER);
            karaokeNextLyrics.setGravity(Gravity.CENTER);
        }
    }

    private void leaveKaraokeMode() {
        karaokeMode = false;
        releaseBackgroundVideo();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        setContentView(normalRoot);
    }

    private void showKaraokeQueue() {
        if (queue.isEmpty()) { Toast.makeText(this, "Walang kanta sa queue.", Toast.LENGTH_SHORT).show(); return; }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < queue.size(); i++) list.append(i + 1).append(". ").append(queue.get(i).title).append("\n");
        new AlertDialog.Builder(this).setTitle("Queue (" + queue.size() + ")")
            .setMessage(list.toString()).setPositiveButton("OK", null).show();
    }

    private boolean hasFloatingPermission() {
        return android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void startFloatingControllerIfAllowed() {
        if (!hasFloatingPermission()) return;
        try {
            startService(new Intent(this, FloatingKaraokeService.class));
        } catch (Exception e) {
            Toast.makeText(this, "Hindi masimulan ang floating controller.", Toast.LENGTH_LONG).show();
        }
    }

    private void enableFloatingController() {
        if (!hasFloatingPermission()) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { Toast.makeText(this, "Buksan ang Settings at payagan ang Display over other apps.", Toast.LENGTH_LONG).show(); }
            return;
        }
        startFloatingControllerIfAllowed();
        Toast.makeText(this, "Lumitaw ang floating karaoke bubble habang bukas ang app.", Toast.LENGTH_LONG).show();
    }

    @Override protected void onResume() {
        super.onResume();
        // Show the floating bubble only while this app is in the foreground.
        // This also starts it after returning from the overlay-permission Settings screen.
        startFloatingControllerIfAllowed();
    }

    @Override protected void onStop() {
        // Remove the bubble when the user leaves or exits the app. It will return on resume.
        stopService(new Intent(this, FloatingKaraokeService.class));
        super.onStop();
    }

    private void registerFloatingRemoteReceiver() {
        floatingRemoteReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String command = intent.getStringExtra(FloatingKaraokeService.EXTRA_COMMAND);
                String number = intent.getStringExtra(FloatingKaraokeService.EXTRA_NUMBER);
                if (MidiPlaybackService.ACTION_LYRIC.equals(intent.getAction())) {
                    String lyric = intent.getStringExtra("lyric");
                    String line = intent.getStringExtra("line");
                    String nextLine = intent.getStringExtra("nextLine");
                    int start = intent.getIntExtra("highlightStart", -1);
                    int end = intent.getIntExtra("highlightEnd", -1);
                    if (lyric != null && (line == null || line.trim().isEmpty())) return; // ignore rests/line-break markers
                    if (line != null && !line.trim().isEmpty()) {
                        android.text.SpannableString styled = new android.text.SpannableString(line);
                        int passedEnd = end > 0 ? Math.min(end, styled.length()) : 0;
                        if (passedEnd > 0) styled.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(255, 205, 55)), 0, passedEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (start >= 0 && end > start && end <= styled.length()) {
                            styled.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(255, 215, 65)), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        if (liveLyricsText != null) liveLyricsText.setText(styled);
                        if (karaokeLyrics != null) karaokeLyrics.setText(styled);
                        if (karaokeNextLyrics != null) karaokeNextLyrics.setText(nextLine == null ? "" : nextLine);
                    }
                    return;
                }
                if (MidiPlaybackService.ACTION_FINISHED.equals(intent.getAction())) {
                    String message = intent.getStringExtra("message");
                    if (message != null && message.startsWith("Hindi mapatugtog:")) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (message != null && message.startsWith("Tapos:")) playNextQueued();
                    return;
                }
                if (FloatingKaraokeService.CMD_NEXT.equals(command)) { playNextQueued(); return; }
                if (FloatingKaraokeService.CMD_PAUSE.equals(command)) { sendMidiService(MidiPlaybackService.ACTION_PAUSE_TOGGLE, null, null); Toast.makeText(MainActivity.this, "Pause/Resume command natanggap.", Toast.LENGTH_SHORT).show(); return; }
                if (FloatingKaraokeService.CMD_STOP.equals(command)) { sendMidiService(MidiPlaybackService.ACTION_STOP, null, null); nowPlayingText.setText("NOW PLAYING\nNakahinto"); Toast.makeText(MainActivity.this, "Playback stopped.", Toast.LENGTH_SHORT).show(); return; }
                if (numberInput != null) numberInput.setText(number == null ? "" : number);
                if (FloatingKaraokeService.CMD_ADD.equals(command)) findByNumber(true, false);
                else if (FloatingKaraokeService.CMD_PLAY.equals(command)) findByNumber(false, true);
            }
        };
        IntentFilter filter = new IntentFilter(FloatingKaraokeService.ACTION_REMOTE);
        filter.addAction(MidiPlaybackService.ACTION_FINISHED);
        filter.addAction(MidiPlaybackService.ACTION_LYRIC);
        registerReceiver(floatingRemoteReceiver, filter);
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
            soundFontText.setText("SoundFont file saved: " + name + "\nCustom SF2 loading is not connected yet; playback uses the built-in soundbank.");
        } else {
            soundFontText.setText("SoundFont: Built-in General MIDI soundbank");
        }
    }

    private void showSoundFontSettings() {
        String current = customSoundFontUri == null
                ? "Kasalukuyan: Built-in General MIDI soundbank."
                : "Naka-save na file: " + queryDisplayName(customSoundFontUri)
                  + "\nPaalaala: ang kasalukuyang playback engine ay hindi pa sumusuporta sa custom SF2 loading; built-in soundbank muna ang gagamitin.";
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
        if (requestCode == PICK_BACKGROUND_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            backgroundVideoUri = data.getData();
            releaseBackgroundVideo();
            try { getContentResolver().takePersistableUriPermission(backgroundVideoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_VIDEO_URI, backgroundVideoUri.toString()).apply();
            Toast.makeText(this, "Nai-save ang shared background video.", Toast.LENGTH_SHORT).show();
            return;
        }
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
                .setMessage("Napili at nai-save: " + name + "\\nPaalaala: built-in soundbank pa rin ang gagamitin sa Stage 2 beta. Hindi pa nakakabit ang custom SF2 loading.")
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
                    found.get(i).number = String.format(Locale.US, "%05d", i + 1);
                }
                songDb.replaceAll(found);
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
                    statusText.setText("Catalog updated: " + songs.size() +
                        " MIDI/KAR file(s). Naka-save sa phone para mabilis buksan sa susunod.");
                }
                renderSongs();
            });
        });
    }

    private void scanChildren(Uri tree, Uri childrenUri, String[] projection,
                              List<Song> found, int depth) {
        if (depth > MAX_DEPTH) return;
        try (Cursor c = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
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


    private static class SongDb extends SQLiteOpenHelper {
        private static final String DB_NAME = "offline_karaoke_catalog.db";
        private static final int DB_VERSION = 1;
        private static final String TABLE = "songs";

        SongDb(android.content.Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (" +
                "number TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, " +
                "filename TEXT NOT NULL, uri TEXT NOT NULL)");
            db.execSQL("CREATE INDEX idx_song_title ON " + TABLE + "(title COLLATE NOCASE)");
            db.execSQL("CREATE INDEX idx_song_filename ON " + TABLE + "(filename COLLATE NOCASE)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }

        synchronized void replaceAll(List<Song> items) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(TABLE, null, null);
                ContentValues values = new ContentValues();
                for (Song song : items) {
                    values.clear();
                    values.put("number", song.number);
                    values.put("title", song.title);
                    values.put("artist", song.artist == null ? "" : song.artist);
                    values.put("filename", song.fileName);
                    values.put("uri", song.uri.toString());
                    db.insertOrThrow(TABLE, null, values);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        synchronized List<Song> readAll() {
            List<Song> items = new ArrayList<>();
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(TABLE,
                    new String[]{"number", "title", "artist", "filename", "uri"},
                    null, null, null, null, "title COLLATE NOCASE ASC")) {
                while (c.moveToNext()) {
                    items.add(new Song(c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), Uri.parse(c.getString(4))));
                }
            }
            return items;
        }
    }

    @Override protected void onDestroy() {
        stopService(new Intent(this, FloatingKaraokeService.class));
        scanExecutor.shutdownNow();
        if (floatingRemoteReceiver != null) { try { unregisterReceiver(floatingRemoteReceiver); } catch (Exception ignored) {} }
        if (songDb != null) songDb.close();
        releaseBackgroundVideo();
        super.onDestroy();
    }

    private void loadCachedSongs() {
        try {
            List<Song> cached = songDb.readAll();
            songs.clear();
            songs.addAll(cached);
        } catch (Exception e) {
            // If the cache is unavailable, the user can rescan the selected folder.
        }
    }

    private void renderSongs() {
        if (songList == null) return;
        songList.removeAllViews();
        String q = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        int matches = 0;
        for (Song song : songs) {
            if (!q.isEmpty() && !song.title.toLowerCase(Locale.ROOT).contains(q)
                    && !song.fileName.toLowerCase(Locale.ROOT).contains(q)
                    && !song.number.contains(q)) continue;
            matches++;
            if (shown >= MAX_VISIBLE_ROWS) continue;
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
                String[] actions = {"Add to Queue", "Play Now"};
                new AlertDialog.Builder(this).setTitle(song.number + " — " + song.title)
                    .setItems(actions, (dialog, which) -> {
                        if (which == 0) {
                            queue.add(song);
                            updateQueueText();
                            Toast.makeText(this, "Naidagdag sa queue: " + song.title, Toast.LENGTH_SHORT).show();
                        } else {
                            playSong(song);
                            showTab(1);
                        }
                    }).show();
            });
            shown++;
        }
        if (songs.isEmpty()) {
            TextView empty = label("Wala pang naka-save na kanta. Pindutin ang “Piliin ang SD Card Folder” para mag-scan.", 13, Color.LTGRAY, false);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            songList.addView(empty);
        } else if (matches == 0) {
            TextView empty = label("Walang tugmang kanta sa search.", 13, Color.LTGRAY, false);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            songList.addView(empty);
        } else if (matches > MAX_VISIBLE_ROWS) {
            TextView note = label("May " + matches + " resulta. Ipinapakita ang unang " + MAX_VISIBLE_ROWS +
                " lang para hindi bumagal ang phone. Gamitin ang search o song number para mahanap ang iba.", 12,
                Color.rgb(170,190,220), false);
            note.setPadding(dp(8), dp(8), dp(8), dp(12));
            songList.addView(note, 0);
        }
    }

    private Song findSong(String number) {
        String n = number.trim();
        if (n.isEmpty()) return null;
        String padded = String.format(Locale.US, "%05d", parseIntSafe(n));
        for (Song s : songs) if (s.number.equals(n) || s.number.equals(padded)) return s;
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
            playSong(s);
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
        playSong(s);
        updateQueueText();
    }

    private void playSong(Song song) {
        currentSongTitle = song.title;
        if (karaokeSongTitle != null) karaokeSongTitle.setText(currentSongTitle);
        nowPlayingText.setText("NOW PLAYING\n" + song.number + " — " + song.title + "\nBuilt-in MIDI soundbank");
        if (liveLyricsText != null) liveLyricsText.setText("♫  Naghahanda ng lyrics…");
        sendMidiService(MidiPlaybackService.ACTION_PLAY, song.uri, song.title + " — " + song.number);
        Toast.makeText(this, "Sinimulan ang MIDI: " + song.title, Toast.LENGTH_SHORT).show();
    }

    private void sendMidiService(String action, Uri uri, String title) {
        Intent intent = new Intent(this, MidiPlaybackService.class);
        intent.setAction(action);
        if (uri != null) intent.setData(uri);
        if (title != null) intent.putExtra(MidiPlaybackService.EXTRA_TITLE, title);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && MidiPlaybackService.ACTION_PLAY.equals(action)) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Hindi masimulan ang MIDI playback service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateQueueText() {
        queueText.setText("Queue: " + queue.size() + " kanta");
        if (karaokeQueue != null) karaokeQueue.setText("QUEUE: " + queue.size());
    }
}
