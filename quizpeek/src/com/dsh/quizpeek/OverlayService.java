package com.dsh.quizpeek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 悬浮按钮 + 答案浮层。
 * 点「识」= 截屏 -> 直连 DeepSeek（传图）-> 答案贴在浮层；截屏失败时退回读控件文字。
 */
public class OverlayService extends Service {

    private static final String API = "https://api.deepseek.com/chat/completions";

    private static final String IMG_PROMPT =
            "这是手机屏幕截图，里面是一道题。请按这个格式回答，不要复述题目：\n"
          + "答案：<选择题必须写清选项字母+内容，填空写要填的内容>\n"
          + "解析：<一句话>\n"
          + "如果截图里没有题目，只回复：没看到题目。";

    private static final String TEXT_PROMPT =
            "下面是我从手机屏幕读到的文字，顺序可能乱。请判断是哪道题，按格式回答：\n"
          + "答案：<选择写选项字母+内容，填空写要填的内容>\n"
          + "解析：<一句话>\n"
          + "看不出题目就只回复：没看到题目。\n\n文字：\n";

    private WindowManager wm;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;

    private LinearLayout panelBox;
    private TextView answerView;
    private WindowManager.LayoutParams panelParams;

    private final Handler main = new Handler(Looper.getMainLooper());

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel("peek", "QuizPeek", NotificationManager.IMPORTANCE_LOW));
                Notification n = new Notification.Builder(this, "peek")
                        .setContentTitle("QuizPeek")
                        .setContentText("悬浮按钮已开启")
                        .setSmallIcon(android.R.drawable.ic_menu_search)
                        .build();
                startForeground(1, n);
            }
        } catch (Throwable t) {
            FileLog.log(this, "foreground failed " + t);
        }
        if (button == null) setupButton();
        FileLog.log(this, "service started, a11y=" + PeekA11yService.isReady());
        return START_STICKY;
    }

    // ---------- 悬浮按钮 ----------

    private void setupButton() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        button = new TextView(this);
        button.setText("识");
        button.setTextColor(Color.WHITE);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF1F6FEB);
        button.setBackground(bg);

        buttonParams = new WindowManager.LayoutParams(
                dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.START;
        buttonParams.x = dp(10);
        buttonParams.y = dp(320);

        button.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = buttonParams.x;
                        startY = buttonParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        buttonParams.x = startX + dx;
                        buttonParams.y = startY + dy;
                        try {
                            wm.updateViewLayout(button, buttonParams);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) onPeek();
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(button, buttonParams);
        } catch (Throwable t) {
            FileLog.log(this, "add button failed " + t);
        }
    }

    // ---------- 截屏 / 读字 ----------

    private void onPeek() {
        FileLog.log(this, "peek tapped, a11y=" + PeekA11yService.isReady());
        if (!PeekA11yService.isReady()) {
            showPanel("无障碍没开，读不到屏幕");
            return;
        }
        showPanel("截屏中…");
        PeekA11yService.capture(new PeekA11yService.Cb() {
            @Override
            public void ok(Bitmap bmp) {
                final byte[] jpg;
                try {
                    jpg = toJpeg(bmp);
                } catch (Throwable t) {
                    showPanel("压图失败：" + t);
                    return;
                }
                bmp.recycle();
                FileLog.log(OverlayService.this, "shot " + jpg.length + "B");
                showPanel("思考中…");
                run(new Call() {
                    @Override
                    public String run() throws Exception {
                        return chat(Base64.encodeToString(jpg, Base64.NO_WRAP), null);
                    }
                });
            }

            @Override
            public void fail(String err) {
                FileLog.log(OverlayService.this, "capture failed: " + err);
                final String text = PeekA11yService.readScreenText();
                if (text.trim().isEmpty()) {
                    showPanel("截屏失败，也没读到文字");
                    return;
                }
                showPanel("思考中…");
                run(new Call() {
                    @Override
                    public String run() throws Exception {
                        return chat(null, text);
                    }
                });
            }
        });
    }

    interface Call {
        String run() throws Exception;
    }

    private void run(final Call call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    out = call.run();
                    FileLog.log(OverlayService.this, "answer: " + out);
                } catch (Throwable t) {
                    FileLog.log(OverlayService.this, "api failed " + t);
                    out = "请求失败：" + t.getMessage();
                }
                final String result = out;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        showPanel(result);
                    }
                });
            }
        }).start();
    }

    private byte[] toJpeg(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        Bitmap src = bmp;
        if (w > 1080) {
            int nh = (int) ((long) h * 1080 / w);
            src = Bitmap.createScaledBitmap(bmp, 1080, nh, true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        src.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        if (src != bmp) src.recycle();
        return bos.toByteArray();
    }

    // ---------- DeepSeek ----------

    private String chat(String imageB64, String text) throws Exception {
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String key = sp.getString("key", "").trim();
        if (key.isEmpty()) {
            return "还没填 API Key\n打开 QuizPeek 填好、保存，再回来点「识」";
        }
        String model = sp.getString("model", "deepseek-chat").trim();
        if (model.isEmpty()) model = "deepseek-chat";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", false);
        body.put("max_tokens", 900);

        JSONArray content = new JSONArray();
        JSONObject t = new JSONObject();
        t.put("type", "text");
        t.put("text", imageB64 != null ? IMG_PROMPT : TEXT_PROMPT + (text.length() > 6000 ? text.substring(0, 6000) : text));
        content.put(t);
        if (imageB64 != null) {
            JSONObject img = new JSONObject();
            img.put("type", "image_url");
            img.put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + imageB64));
            content.put(img);
        }
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", content);
        JSONArray msgs = new JSONArray();
        msgs.put(msg);
        body.put("messages", msgs);

        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = readAll(is);
        if (code != 200) {
            throw new RuntimeException("HTTP " + code + " " + resp.substring(0, Math.min(160, resp.length())));
        }
        JSONObject j = new JSONObject(resp);
        JSONObject m = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String out = m.optString("content", "").trim();
        if (out.isEmpty()) {
            String r = m.optString("reasoning_content", "");
            out = r.isEmpty() ? "模型没返回内容，换个模型试试" : "模型只输出了思考，没给答案，把模型换成 deepseek-chat";
        }
        return out;
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ---------- 答案浮层 ----------

    private void ensurePanel() {
        if (panelBox != null) return;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xF7FFFFFF);
        bg.setStroke(dp(1), 0x33000000);
        panelBox.setBackground(bg);
        panelBox.setElevation(dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(8), dp(8), dp(4));

        TextView title = new TextView(this);
        title.setText("答案");
        title.setTextSize(13);
        title.setTextColor(0xFF888888);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView resizeHandle = new TextView(this);
        resizeHandle.setText("⤢");
        resizeHandle.setTextSize(16);
        resizeHandle.setTextColor(0xFF666666);
        resizeHandle.setPadding(dp(10), 0, dp(10), dp(6));
        head.addView(resizeHandle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(20);
        close.setTextColor(0xFF666666);
        close.setPadding(dp(10), 0, dp(6), dp(6));
        head.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (panelBox != null) panelBox.setVisibility(View.GONE);
            }
        });

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        answerView = new TextView(this);
        answerView.setTextSize(15);
        answerView.setTextColor(0xFF111111);
        answerView.setLineSpacing(dp(3), 1f);
        answerView.setPadding(dp(14), 0, dp(14), dp(12));
        sc.addView(answerView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        panelBox.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panelBox.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panelParams = new WindowManager.LayoutParams(
                dp(300), dp(230),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        panelParams.y = dp(110);

        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startW, startH;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startW = panelParams.width;
                        startH = panelParams.height;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        panelParams.width = clamp(startW + dx, dp(190), dp(380));
                        panelParams.height = clamp(startH - dy, dp(120), dp(560));
                        try {
                            wm.updateViewLayout(panelBox, panelParams);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });

        head.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = panelParams.x;
                        startY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        panelParams.x = startX + dx;
                        panelParams.y = startY - dy;
                        try {
                            wm.updateViewLayout(panelBox, panelParams);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(panelBox, panelParams);
        } catch (Throwable t) {
            FileLog.log(this, "add panel failed " + t);
        }
    }

    private CharSequence style(String text) {
        int i = text.indexOf("解析");
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (i > 0) {
            b.append(text.substring(0, i));
            b.setSpan(new AbsoluteSizeSpan(18, true), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new StyleSpan(Typeface.BOLD), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int start = b.length();
            b.append(text.substring(i));
            b.setSpan(new AbsoluteSizeSpan(14, true), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            b.append(text);
            b.setSpan(new AbsoluteSizeSpan(17, true), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private void showPanel(String text) {
        try {
            ensurePanel();
            if (panelBox == null) return;
            panelBox.setVisibility(View.VISIBLE);
            answerView.setText(style(text));
        } catch (Throwable t) {
            FileLog.log(this, "panel failed " + t);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (panelBox != null && wm != null) wm.removeView(panelBox);
        } catch (Throwable ignored) {
        }
        try {
            if (button != null && wm != null) wm.removeView(button);
        } catch (Throwable ignored) {
        }
        panelBox = null;
        button = null;
        FileLog.log(this, "service destroyed");
        super.onDestroy();
    }
}
