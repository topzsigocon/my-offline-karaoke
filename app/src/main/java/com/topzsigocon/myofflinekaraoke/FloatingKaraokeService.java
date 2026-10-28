package com.topzsigocon.myofflinekaraoke;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Draggable Messenger-style karaoke remote. Playback actions are routed to MainActivity. */
public class FloatingKaraokeService extends Service {
    public static final String ACTION_REMOTE = "com.topzsigocon.myofflinekaraoke.FLOATING_REMOTE";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_NUMBER = "song_number";
    public static final String CMD_ADD = "add";
    public static final String CMD_PLAY = "play";
    public static final String CMD_NEXT = "next";

    private WindowManager wm;
    private WindowManager.LayoutParams bubbleParams, panelParams;
    private View bubble, panel;
    private boolean panelShown = false;
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private int windowType() { return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }

    @Override public void onCreate() {
        super.onCreate(); wm = (WindowManager)getSystemService(WINDOW_SERVICE); createBubble();
    }

    private void createBubble() {
        bubble = text("🎤", 25, Color.WHITE, true);
        ((TextView) bubble).setGravity(Gravity.CENTER); bubble.setBackground(rounded(Color.rgb(218, 48, 83), 100));
        bubbleParams = new WindowManager.LayoutParams(dp(58), dp(58), windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START; bubbleParams.x = dp(12); bubbleParams.y = dp(180);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { downX=e.getRawX(); downY=e.getRawY(); startX=bubbleParams.x; startY=bubbleParams.y; moved=false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx=(int)(e.getRawX()-downX), dy=(int)(e.getRawY()-downY);
                    if (Math.abs(dx)>dp(4) || Math.abs(dy)>dp(4)) moved=true;
                    bubbleParams.x=startX+dx; bubbleParams.y=startY+dy;
                    try { wm.updateViewLayout(bubble,bubbleParams); } catch(Exception ignored) {}
                    if (panelShown) { panelParams.x=bubbleParams.x; panelParams.y=bubbleParams.y+dp(66); try { wm.updateViewLayout(panel,panelParams); } catch(Exception ignored) {} }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!moved) togglePanel(); return true; }
                return true;
            }
        });
        try { wm.addView(bubble,bubbleParams); } catch (Exception e) { Toast.makeText(this,"Hindi mabuksan ang floating controller.",Toast.LENGTH_LONG).show(); stopSelf(); }
    }

    private void togglePanel() { if (panelShown) hidePanel(); else showPanel(); }
    private void showPanel() {
        if (panelShown || bubble == null) return;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(12),dp(10),dp(12),dp(10)); box.setBackground(rounded(Color.rgb(18,25,42),16));
        TextView title=text("KARAOKE REMOTE",15,Color.WHITE,true); box.addView(title);
        TextView info=text("Ilagay ang song number",12,Color.LTGRAY,false); LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2); ip.topMargin=dp(4); box.addView(info,ip);
        EditText number=new EditText(this); number.setSingleLine(true); number.setTextColor(Color.WHITE); number.setHintTextColor(Color.LTGRAY); number.setHint("Hal. 01234"); number.setInputType(2); number.setTextSize(18); number.setBackground(rounded(Color.rgb(43,54,75),8)); number.setPadding(dp(10),0,dp(10),0);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(48)); np.topMargin=dp(6); box.addView(number,np);
        LinearLayout r1=new LinearLayout(this); r1.setOrientation(LinearLayout.HORIZONTAL);
        Button add=button("Add sa Queue"); Button play=button("Play Now"); r1.addView(add,new LinearLayout.LayoutParams(0,dp(46),1)); r1.addView(play,new LinearLayout.LayoutParams(0,dp(46),1)); box.addView(r1);
        LinearLayout r2=new LinearLayout(this); r2.setOrientation(LinearLayout.HORIZONTAL);
        Button next=button("Next ⏭"); Button close=button("Minimize"); r2.addView(next,new LinearLayout.LayoutParams(0,dp(44),1)); r2.addView(close,new LinearLayout.LayoutParams(0,dp(44),1)); box.addView(r2);
        add.setOnClickListener(v -> send(CMD_ADD, number.getText().toString().trim()));
        play.setOnClickListener(v -> send(CMD_PLAY, number.getText().toString().trim()));
        next.setOnClickListener(v -> send(CMD_NEXT, "")); close.setOnClickListener(v -> hidePanel());
        panel=box;
        panelParams=new WindowManager.LayoutParams(dp(280),WindowManager.LayoutParams.WRAP_CONTENT,windowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity=Gravity.TOP|Gravity.START; panelParams.x=bubbleParams.x; panelParams.y=bubbleParams.y+dp(66);
        try { wm.addView(panel,panelParams); panelShown=true; } catch(Exception e) { panel=null; Toast.makeText(this,"Hindi maipakita ang controller.",Toast.LENGTH_SHORT).show(); }
    }
    private void send(String command, String number) {
        Intent i=new Intent(ACTION_REMOTE); i.setPackage(getPackageName()); i.putExtra(EXTRA_COMMAND,command); i.putExtra(EXTRA_NUMBER,number); sendBroadcast(i);
        if (CMD_ADD.equals(command) || CMD_PLAY.equals(command)) Toast.makeText(this,"Ipinadala ang song number sa My Offline Karaoke.",Toast.LENGTH_SHORT).show();
    }
    private void hidePanel() { if (panel != null) { try { wm.removeView(panel); } catch(Exception ignored) {} panel=null; } panelShown=false; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) { return START_STICKY; }
    @Override public void onDestroy() { hidePanel(); if (bubble!=null) { try { wm.removeView(bubble); } catch(Exception ignored) {} bubble=null; } super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
