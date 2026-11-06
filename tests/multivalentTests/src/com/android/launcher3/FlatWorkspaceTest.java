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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.views.ActivityContext;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Product visibility is based on package identity, including updated system packages. */
@RunWith(AndroidJUnit4.class)
public class FlatWorkspaceTest {
    private static final String[] ALLOWED = {"com.android.settings"};

    @Test
    public void popupCannotRemoveAppsOrFoldersWithoutDrawer() {
        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        View view = mock(View.class);
        ActivityContext activity = mock(ActivityContext.class);
        when(view.getContext()).thenReturn(context);
        when(context.getResources()).thenReturn(resources);
        when(resources.getBoolean(R.bool.config_flat_workspace)).thenReturn(true);
        WorkspaceItemInfo app = new WorkspaceItemInfo();
        app.itemType = LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
        assertNull(SystemShortcut.REMOVE.getShortcut(activity, app, view));
        assertNull(SystemShortcut.REMOVE.getShortcut(activity, new FolderInfo(), view));
        WorkspaceItemInfo shortcut = new WorkspaceItemInfo();
        shortcut.itemType = LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT;
        assertNotNull(SystemShortcut.REMOVE.getShortcut(activity, shortcut, view));
        when(resources.getBoolean(R.bool.config_flat_workspace)).thenReturn(false);
        assertNotNull(SystemShortcut.REMOVE.getShortcut(activity, app, view));
    }

    @Test
    public void settingsIsVisible() {
        assertTrue(
                FlatWorkspace.isVisible(
                        ApplicationInfo.FLAG_SYSTEM, "com.android.settings", ALLOWED));
    }

    @Test
    public void otherSystemAppsAreHidden() {
        assertFalse(
                FlatWorkspace.isVisible(
                        ApplicationInfo.FLAG_SYSTEM, "com.android.camera2", ALLOWED));
    }

    @Test
    public void updatedSystemAppsRemainHidden() {
        assertFalse(
                FlatWorkspace.isVisible(
                        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, "com.android.browser", ALLOWED));
    }

    @Test
    public void nonSystemPreinstalledBrowserIsVisible() {
        assertTrue(FlatWorkspace.isVisible(0, "com.android.browser", ALLOWED));
    }

    @Test
    public void packagePrefixDoesNotHideUserApps() {
        assertTrue(FlatWorkspace.isVisible(0, "com.android.game", ALLOWED));
    }
}
