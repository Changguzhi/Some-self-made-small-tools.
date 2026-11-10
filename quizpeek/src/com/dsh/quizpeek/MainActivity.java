package com.dsh.quizpeek;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    public static final String PREFS = "quizpeek";

    private TextView status;
    private EditText keyInput;
    private EditText modelInput;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        TextView title = new TextView(this);
        title.setText("QuizPeek");
        title.setTextSize(22);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, p, 0, p);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        TextView keyLabel = new TextView(this);
        keyLabel.setText("DeepSeek API Key");
        keyInput = new EditText(this);
        keyInput.setHint("sk-…");
        keyInput.setText(sp.getString("key", ""));
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSingleLine(true);

        TextView modelLabel = new TextView(this);
        modelLabel.setText("模型");
        modelInput = new EditText(this);
        modelInput.setHint("deepseek-chat");
        modelInput.setText(sp.getString("model", "deepseek-chat"));
        modelInput.setSingleLine(true);

        Button save = new Button(this);
        save.setText("保存设置");
        save.setAllCaps(false);
        save.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("key", keyInput.getText().toString().trim())
                    .putString("model", modelInput.getText().toString().trim())
                    .apply();
            FileLog.log(this, "settings saved, key len=" + keyInput.getText().toString().trim().length());
            status.setText(describe());
        });

        Button b1 = new Button(this);
        b1.setText("1. 授权悬浮窗");
        b1.setAllCaps(false);
        b1.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                FileLog.log(this, "overlay settings failed " + t);
            }
        });

        Button b2 = new Button(this);
        b2.setText("2. 开启无障碍（读题用）");
        b2.setAllCaps(false);
        b2.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                FileLog.log(this, "a11y settings failed " + t);
            }
        });

        Button b3 = new Button(this);
        b3.setText("3. 开始（显示悬浮按钮）");
        b3.setAllCaps(false);
        b3.setOnClickListener(v -> {
            FileLog.log(this, "start requested");
            try {
                startForegroundService(new Intent(this, OverlayService.class));
            } catch (Throwable t) {
                FileLog.log(this, "start failed " + t);
                status.setText("启动失败：" + t);
            }
        });

        Button b4 = new Button(this);
        b4.setText("4. 停止悬浮按钮");
        b4.setAllCaps(false);
        b4.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            FileLog.log(this, "stop requested");
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        root.addView(title);
        root.addView(status);
        root.addView(keyLabel);
        root.addView(keyInput, lp);
        root.addView(modelLabel);
        root.addView(modelInput, lp);
        root.addView(save, lp);
        root.addView(b1, lp);
        root.addView(b2, lp);
        root.addView(b3, lp);
        root.addView(b4, lp);
        setContentView(root);
        FileLog.log(this, "activity created");
    }

    private String describe() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean a11y = PeekA11yService.isReady();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean hasKey = !sp.getString("key", "").isEmpty();
        return "悬浮窗：" + (ov ? "已授权" : "未授权")
                + "\n无障碍：" + (a11y ? "已开启" : "未开启")
                + "\nAPI Key：" + (hasKey ? "已保存" : "未填")
                + "\n\n日志：Download/QuizPeek/quizpeek-log.txt";
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(describe());
        status.setTextColor(Color.DKGRAY);
    }
}
