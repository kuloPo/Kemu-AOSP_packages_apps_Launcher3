package com.android.quickstep;

import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import com.android.launcher3.util.Executors;
import com.android.launcher3.util.KemuTabLaunch;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.function.Consumer;

/** Keeps Quickstep on the desktop and app tasks on their Kemu tab displays. */
public final class KemuAppTabs {
    private KemuAppTabs() { }

    private static boolean isTab(Context context, int displayId) {
        if (displayId <= Display.DEFAULT_DISPLAY) return false;
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        return display != null && "com.android.emulator.multidisplay".equals(
                display.getOwnerPackageName());
    }

    public static boolean usesAppTabForRecents(Context context, Task.TaskKey task) {
        if (!KemuTabLaunch.enabled() || context.getDisplayId() != Display.DEFAULT_DISPLAY
                || task.userId != UserHandle.USER_SYSTEM
                || (task.topActivityType != WindowConfiguration.ACTIVITY_TYPE_STANDARD
                    && task.topActivityType != WindowConfiguration.ACTIVITY_TYPE_UNDEFINED)) {
            return false;
        }
        String pkg = task.getPackageName();
        if (pkg == null || pkg.equals(context.getPackageName())) return false;
        if (task.displayId > Display.DEFAULT_DISPLAY) {
            Display display = context.getSystemService(DisplayManager.class).getDisplay(task.displayId);
            if (display != null) {
                return "com.android.emulator.multidisplay".equals(display.getOwnerPackageName());
            }
        }
        return task.baseIntent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || context.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    public enum LaunchResult { STARTED, HANDLED, FAILED }

    public static void startRecentTab(Context context, Task.TaskKey task, ActivityOptions options,
            Consumer<LaunchResult> completed) {
        Bundle request = new Bundle();
        request.putInt("recentTaskId", task.id);
        KemuTabLaunch.open(context, task.getPackageName(), request, reply -> {
            if (reply != null && reply.getBoolean("handled")) {
                completed.accept(LaunchResult.HANDLED);
                return;
            }
            if (reply == null || !reply.containsKey("display")) {
                completed.accept(LaunchResult.FAILED);
                return;
            }
            options.setLaunchDisplayId(reply.getInt("display"));
            completed.accept(ActivityManagerWrapper.getInstance().startActivityFromRecents(task, options)
                    ? LaunchResult.STARTED : LaunchResult.FAILED);
        });
    }

    public static void onDesktopFocusChanged(Context context) {
        if (!KemuTabLaunch.enabled()) return;
        // TaskStackListener reports focus within each display. The top resumed activity
        // also changes when Android switches between displays with unchanged task stacks.
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            try {
                context.getContentResolver().call(
                        Uri.parse("content://com.android.emulator.multidisplay.tabs"),
                        "refreshFocus", null, null);
            } catch (RuntimeException e) {
                Log.w("KemuTabs", "Tab provider unavailable", e);
            }
        });
    }

    static boolean focusDesktopForOverview(Context context) {
        if (!KemuTabLaunch.enabled()) return false;
        try {
            var service = ActivityTaskManager.getService();
            var focused = service.getFocusedRootTaskInfo();
            if (focused == null || !isTab(context, focused.displayId)) return false;
            service.focusTopTask(Display.DEFAULT_DISPLAY);
            return true;
        } catch (RemoteException e) {
            Log.w("KemuTabs", "Cannot focus the overview display", e);
            return false;
        }
    }

    public static boolean belongsToDesktopOverview(Context context, int taskDisplay) {
        return taskDisplay > Display.DEFAULT_DISPLAY && KemuTabLaunch.enabled() && isTab(context, taskDisplay);
    }
}
