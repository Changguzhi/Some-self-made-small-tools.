package com.dsh.quizpeek;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/** 只做一件事：截屏。截完交给 OverlayService。 */
public class PeekA11yService extends AccessibilityService {

    public interface Cb {
        void ok(Bitmap bmp);
        void fail(String err);
    }

    private static PeekA11yService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isReady() {
        return instance != null;
    }

    public static void capture(Cb cb) {
        PeekA11yService s = instance;
        if (s == null) {
            cb.fail("无障碍没开");
            return;
        }
        s.doCapture(cb);
    }

    @Override
    public void onServiceConnected() {
        instance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            FileLog.log(this, "setServiceInfo failed " + t);
        }
        FileLog.log(this, "a11y connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        FileLog.log(this, "a11y destroyed");
        super.onDestroy();
    }


    /** 读当前页面的文字（跳过本 App 自己的浮层）。 */
    public static String readScreenText() {
        PeekA11yService s = instance;
        if (s == null) return "";
        try {
            return s.collectText();
        } catch (Throwable t) {
            FileLog.log(s, "read text failed " + t);
            return "";
        }
    }

    private String collectText() {
        String own = getPackageName();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !own.equals(String.valueOf(root.getPackageName()))) {
            return walk(root, own, new StringBuilder()).toString();
        }
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) {
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (own.equals(String.valueOf(r.getPackageName()))) continue;
                String t = walk(r, own, new StringBuilder()).toString();
                if (t.trim().length() > 0) return t;
            }
        }
        return root == null ? "" : walk(root, own, new StringBuilder()).toString();
    }

    private StringBuilder walk(AccessibilityNodeInfo n, String own, StringBuilder sb) {
        if (n == null || sb.length() > 20000) return sb;
        try {
            if (own.equals(String.valueOf(n.getPackageName()))) return sb;
            CharSequence t = n.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty()) sb.append(s).append('\n');
            }
            CharSequence d = n.getContentDescription();
            if (d != null) {
                String s = d.toString().trim();
                if (!s.isEmpty() && (t == null || !s.contentEquals(t))) sb.append(s).append('\n');
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch == null) continue;
                walk(ch, own, sb);
            }
        } catch (Throwable ignored) {
        }
        return sb;
    }

    private void doCapture(final Cb cb) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = null;
                    try {
                        hb = r.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        Bitmap copy = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                        if (copy == null) {
                            cb.fail("图转换失败");
                            return;
                        }
                        FileLog.log(PeekA11yService.this, "shot " + copy.getWidth() + "x" + copy.getHeight());
                        cb.ok(copy);
                    } catch (Throwable t) {
                        FileLog.log(PeekA11yService.this, "shot error " + t);
                        cb.fail(String.valueOf(t));
                    } finally {
                        try {
                            if (hb != null) hb.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    FileLog.log(PeekA11yService.this, "shot failure " + errorCode);
                    cb.fail("error " + errorCode);
                }
            });
        } catch (Throwable t) {
            FileLog.log(this, "takeScreenshot threw " + t);
            cb.fail(String.valueOf(t));
        }
    }
}
