package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import com.android.launcher3.dagger.ApplicationContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    private final Set<ComponentName> mFilteredComponents;
    private final Context mContext;
    private final boolean mFlatWorkspace;
    private final String[] mVisibleSystemApps;

    @Inject
    public AppFilter(@ApplicationContext Context context) {
        mContext = context;
        mFlatWorkspace = FlatWorkspace.isEnabled(context);
        mVisibleSystemApps = mFlatWorkspace
                ? context.getResources().getStringArray(R.array.config_flat_workspace_system_apps)
                : new String[0];
        mFilteredComponents = Arrays.stream(
                context.getResources().getStringArray(R.array.filtered_components))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
    }

    public boolean shouldShowApp(ComponentName app) {
        return !mFilteredComponents.contains(app);
    }

    /** Uses the application metadata already returned by LauncherApps. */
    public boolean shouldShowApp(ComponentName app, ApplicationInfo info) {
        return shouldShowApp(app) && shouldShowPackage(info);
    }

    /** Applies the cached system-application visibility policy to package metadata. */
    public boolean shouldShowPackage(ApplicationInfo info) {
        return !mFlatWorkspace
                || FlatWorkspace.isVisible(info.flags, info.packageName, mVisibleSystemApps);
    }

    /** Resolves providers and saved shortcuts in their own user profile. */
    public boolean shouldShowApp(ComponentName app, UserHandle user) {
        return shouldShowApp(app) && shouldShowPackage(app.getPackageName(), user);
    }

    /** Preserves unavailable packages until their own profile can resolve them. */
    public boolean shouldShowPackage(String packageName, UserHandle user) {
        if (!mFlatWorkspace || packageName == null || user == null) return true;
        try {
            return shouldShowPackage(mContext.getSystemService(LauncherApps.class)
                    .getApplicationInfo(packageName, 0, user));
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            return true;
        }
    }
}
