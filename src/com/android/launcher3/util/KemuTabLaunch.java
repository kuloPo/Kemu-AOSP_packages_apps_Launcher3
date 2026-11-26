package com.android.launcher3.util;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Resolves a launch destination without waiting for the tab provider on the UI thread. */
public final class KemuTabLaunch {
    private static final Executor REQUESTS = java.util.concurrent.Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "KemuTabLaunch"));

    private KemuTabLaunch() {}

    public static boolean enabled() {
        return SystemProperties.getInt("ro.kemu.app_tabs.version", 0) == 2
                && SystemProperties.getBoolean("vendor.qemu.kemu.app_tabs", false);
    }

    public static void open(Context context, String pkg, Bundle request, Consumer<Bundle> callback) {
        Handler main = Executors.MAIN_EXECUTOR.getHandler();
        long deadline = SystemClock.uptimeMillis() + 5000;
        request.putLong("deadline", deadline);
        Runnable timeout = () -> callback.accept(null);
        main.postAtTime(timeout, deadline);
        REQUESTS.execute(() -> {
            if (SystemClock.uptimeMillis() >= deadline) return;
            Bundle reply = null;
            try {
                reply = context.getContentResolver().call(
                        Uri.parse("content://com.android.emulator.multidisplay.tabs"), "open", pkg, request);
            } catch (RuntimeException e) {
                Log.w("KemuTabs", "Cannot resolve application display", e);
            }
            final Bundle result = reply;
            main.post(() -> {
                if (!main.hasCallbacks(timeout)) return;
                main.removeCallbacks(timeout);
                callback.accept(SystemClock.uptimeMillis() < deadline ? result : null);
            });
        });
    }
}
