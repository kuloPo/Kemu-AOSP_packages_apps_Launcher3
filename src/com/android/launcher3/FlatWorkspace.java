/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import com.android.launcher3.model.data.CollectionInfo;
import com.android.launcher3.model.data.ItemInfo;

import java.util.Arrays;

/** Product policy for a workspace containing all launchable applications. */
public final class FlatWorkspace {
    private FlatWorkspace() {}

    /** Returns whether this product uses a flat application workspace. */
    public static boolean isEnabled(Context context) {
        return context.getResources().getBoolean(R.bool.config_flat_workspace);
    }

    /** App icons and folders must remain reachable when there is no application drawer. */
    public static boolean canRemoveItem(Context context, ItemInfo item) {
        return !isEnabled(context)
                || (item.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                        && !(item instanceof CollectionInfo));
    }

    /** Keeps user applications and the configured system application exceptions visible. */
    public static boolean isVisible(Context context, ApplicationInfo info) {
        return !isEnabled(context)
                || isVisible(
                        info.flags,
                        info.packageName,
                        context.getResources()
                                .getStringArray(R.array.config_flat_workspace_system_apps));
    }

    static boolean isVisible(int flags, String packageName, String[] allowedSystemApps) {
        return (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                        == 0
                || Arrays.asList(allowedSystemApps).contains(packageName);
    }

    /**
     * Resolves visibility in the package's own profile, preserving temporarily unavailable items.
     */
    public static boolean isPackageVisible(Context context, String packageName, UserHandle user) {
        if (!isEnabled(context) || packageName == null) {
            return true;
        }
        try {
            return isVisible(
                    context,
                    context.getSystemService(LauncherApps.class)
                            .getApplicationInfo(packageName, 0, user));
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            // Keep unavailable items until the loader can resolve their package or profile.
            return true;
        }
    }
}
