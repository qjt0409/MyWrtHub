package com.whykangkang.wrthub.manager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录器:装一个全局未捕获异常处理器,把最后一次崩溃的堆栈存下来,
 * 原处理器照常执行(行为不变,App 该退还是退);下次打开 App 时在首页
 * 弹出来,用户可一键复制发给开发者定位。没有它,闪退只能靠猜。
 */
public final class CrashTrap {

    private static final String PREFS = "crash_trap";
    private static final String KEY_TRACE = "last_crash";

    private CrashTrap() {
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String record = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date())
                        + "  thread=" + thread.getName() + "\n" + sw;
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_TRACE, record).commit();
            } catch (Throwable ignored) {
                // 记录失败也不能影响原崩溃流程
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** 有待展示的崩溃记录时弹出来(复制/关闭后清除,不会反复弹) */
    public static void maybeShow(Activity activity) {
        SharedPreferences prefs =
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String trace = prefs.getString(KEY_TRACE, null);
        if (trace == null || trace.isEmpty()) return;
        prefs.edit().remove(KEY_TRACE).apply();

        TextView body = new TextView(activity);
        body.setText(trace);
        body.setTextIsSelectable(true);
        body.setTextSize(11);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_hint)
                .setView(scroll)
                .setPositiveButton(R.string.crash_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("crash", trace));
                        Toast.makeText(activity, R.string.crash_copied,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
