package com.topzsigocon.myofflinekaraoke;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.OpenableColumns;

import org.billthefarmer.mididriver.MidiDriver;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Foreground MIDI playback service; the synthesizer remains alive when the activity is backgrounded. */
public class MidiPlaybackService extends Service {
    public static final String ACTION_PLAY = "com.topzsigocon.myofflinekaraoke.MIDI_PLAY";
    public static final String ACTION_PAUSE_TOGGLE = "com.topzsigocon.myofflinekaraoke.MIDI_PAUSE_TOGGLE";
    public static final String ACTION_STOP = "com.topzsigocon.myofflinekaraoke.MIDI_STOP";
    public static final String ACTION_FINISHED = "com.topzsigocon.myofflinekaraoke.MIDI_FINISHED";
    public static final String ACTION_LYRIC = "com.topzsigocon.myofflinekaraoke.MIDI_LYRIC";
    public static final String EXTRA_URI = "midi_uri";
    public static final String EXTRA_TITLE = "midi_title";
    private static final String CHANNEL = "karaoke_playback";
    private final Object gate = new Object();
    private volatile boolean stopRequested=false;
    private volatile boolean paused=false;
    private Thread playerThread;
    private MidiDriver driver;
    private String currentTitle="My Offline Karaoke";

    @Override public void onCreate() {
        super.onCreate(); createChannel();
        driver = MidiDriver.getInstance();
        try { driver.start(); driver.setVolume(100); driver.setReverb(1); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action=intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            Uri uri=intent.getData();
            currentTitle=intent.getStringExtra(EXTRA_TITLE);
            if (currentTitle == null || currentTitle.trim().isEmpty()) currentTitle="My Offline Karaoke";
            startForeground(7, notification("Naghahanda: " + currentTitle));
            startTrack(uri, currentTitle);
        } else if (ACTION_PAUSE_TOGGLE.equals(action)) {
            synchronized(gate) { paused=!paused; gate.notifyAll(); }
            updateNotification(paused ? "Naka-pause: " + currentTitle : "Tumutugtog: " + currentTitle);
        } else if (ACTION_STOP.equals(action)) {
            stopTrack(); stopForeground(true); stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startTrack(Uri uri, String title) {
        stopTrack();
        if (uri == null) { broadcastFinished("Walang file URI."); return; }
        stopRequested=false; paused=false; currentTitle=title;
        playerThread=new Thread(() -> {
            try {
                byte[] bytes=readUri(uri);
                List<MidiFileParser.Event> events=MidiFileParser.parse(bytes);
                if (events.isEmpty()) throw new Exception("Walang MIDI note events sa file.");
                Thread.sleep(250);
                long previousMicros=0;
                updateNotification("Tumutugtog: " + currentTitle);
                for (MidiFileParser.Event event : events) {
                    if (stopRequested) break;
                    long delay=Math.max(0, event.micros-previousMicros);
                    if (!waitDelay(delay)) break;
                    previousMicros=event.micros;
                    if (event.message != null && !stopRequested) driver.queueEvent(event.message);
                    if (event.lyric != null && !stopRequested) {
                        Intent lyricIntent = new Intent(ACTION_LYRIC);
                        lyricIntent.setPackage(getPackageName());
                        lyricIntent.putExtra("lyric", event.lyric);
                        sendBroadcast(lyricIntent);
                    }
                }
                allNotesOff();
                if (!stopRequested) {
                    stopForeground(true);
                    broadcastFinished("Tapos: " + currentTitle);
                }
            } catch (Exception e) {
                allNotesOff();
                broadcastFinished("Hindi mapatugtog: " + e.getMessage());
                stopForeground(true);
            }
        }, "KaraokeMidiPlayer");
        playerThread.start();
    }

    /** High-resolution delay loop to reduce timing drift on dense MIDI passages. */
    private boolean waitDelay(long micros) throws InterruptedException {
        if (micros <= 0) return !stopRequested;
        long remainingNanos = micros * 1000L;
        long deadline = System.nanoTime() + remainingNanos;
        while (!stopRequested) {
            synchronized (gate) {
                while (paused && !stopRequested) gate.wait();
            }
            if (stopRequested) return false;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return true;
            // Short parks keep the pause/stop response quick while avoiding 40 ms timing jumps.
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
            if (Thread.interrupted()) throw new InterruptedException();
            // When paused, freeze the deadline so the song resumes at the same MIDI position.
            synchronized (gate) {
                if (paused && !stopRequested) {
                    long pauseStart = System.nanoTime();
                    while (paused && !stopRequested) gate.wait();
                    deadline += System.nanoTime() - pauseStart;
                }
            }
        }
        return false;
    }

    private byte[] readUri(Uri uri) throws Exception {
        try (InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("Hindi mabuksan ang MIDI file.");
            byte[] buffer=new byte[8192]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            return out.toByteArray();
        }
    }
    private void stopTrack() {
        stopRequested=true; synchronized(gate) { paused=false; gate.notifyAll(); }
        Thread t=playerThread; if (t != null && t != Thread.currentThread()) { try { t.join(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
        playerThread=null; allNotesOff();
    }
    private void allNotesOff() {
        if (driver == null) return;
        for (int ch=0; ch<16; ch++) { try { driver.queueEvent(new byte[]{(byte)(0xb0|ch),123,0}); } catch (Exception ignored) {} }
    }
    private void broadcastFinished(String message) {
        Intent i=new Intent(ACTION_FINISHED); i.setPackage(getPackageName()); i.putExtra("message",message); sendBroadcast(i);
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Karaoke playback",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps MIDI karaoke audio playing in the background.");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private Notification notification(String text) {
        Intent open=new Intent(this, MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT:PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("My Offline Karaoke").setContentText(text).setContentIntent(pi).setOngoing(true).build();
        return new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("My Offline Karaoke").setContentText(text).setContentIntent(pi).setOngoing(true).build();
    }
    private void updateNotification(String text) { try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7,notification(text)); } catch(Exception ignored) {} }
    @Override public void onDestroy() { stopTrack(); try { if(driver!=null) { driver.stop(); } } catch(Exception ignored) {} super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
