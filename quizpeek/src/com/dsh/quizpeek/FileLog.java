package com.dsh.quizpeek;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 把日志写到 /sdcard/Download/QuizPeek/quizpeek-log.txt，方便在电脑/终端里看。 */
public class FileLog {
    private static final StringBuilder BUF = new StringBuilder(8192);
    private static Uri uri = null;

    public static synchronized void log(Context ctx, String msg) {
        BUF.append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()))
           .append("  ").append(msg).append('\n');
        if (BUF.length() > 60000) BUF.delete(0, BUF.length() - 40000);
        write(ctx);
    }

    private static void write(Context ctx) {
        if (ctx == null) return;
        try {
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "quizpeek-log.txt");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/QuizPeek");
                uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            }
            if (uri == null) return;
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) return;
                os.write(BUF.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
