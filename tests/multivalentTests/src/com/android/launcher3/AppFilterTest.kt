/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.launcher3

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppFilterTest {

    @Mock private lateinit var mockContext: Context

    @Mock // Mock the Resources object as well
    private lateinit var mockResources: Resources

    @get:Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var appFilter: AppFilter

    @Before
    fun setUp() {
        `when`(mockContext.resources).thenReturn(mockResources) // Link the context and resources
        `when`(mockResources.getStringArray(R.array.filtered_components))
            .thenReturn(arrayOf("com.example.app1/Activity1"))
        appFilter = AppFilter(mockContext)
    }

    @Test
    fun shouldShowApp_notFiltered_returnsTrue() {
        val appToShow = ComponentName("com.example.app2", "Activity2")
        assertThat(appFilter.shouldShowApp(appToShow)).isTrue()
    }

    @Test
    fun shouldShowApp_filtered_returnsFalse() {
        val appToHide = ComponentName("com.example.app1", "Activity1")
        assertThat(appFilter.shouldShowApp(appToHide)).isFalse()
    }

    @Test
    fun cachedMetadataFiltersSystemAppsWithoutPackageLookup() {
        `when`(mockResources.getBoolean(R.bool.config_flat_workspace)).thenReturn(true)
        `when`(mockResources.getStringArray(R.array.config_flat_workspace_system_apps))
            .thenReturn(arrayOf("com.android.settings"))
        val filter = AppFilter(mockContext)
        val app =
            ApplicationInfo().apply {
                packageName = "com.android.browser"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        val component = ComponentName(app.packageName, "Main")
        assertThat(filter.shouldShowApp(component, app)).isFalse()
        app.flags = 0
        repeat(3) { assertThat(filter.shouldShowApp(component, app)).isTrue() }
        verify(mockContext, never()).getSystemService(LauncherApps::class.java)
        verify(mockResources, times(1)).getStringArray(R.array.config_flat_workspace_system_apps)
    }

    @Test
    fun providerLookupUsesItsOwnProfile() {
        `when`(mockResources.getBoolean(R.bool.config_flat_workspace)).thenReturn(true)
        `when`(mockResources.getStringArray(R.array.config_flat_workspace_system_apps))
            .thenReturn(arrayOf("com.android.settings"))
        val service = mock(LauncherApps::class.java)
        `when`(mockContext.getSystemService(LauncherApps::class.java)).thenReturn(service)
        val user = UserHandle.of(10)
        val app = ApplicationInfo().apply { packageName = "work.app" }
        `when`(service.getApplicationInfo("work.app", 0, user)).thenReturn(app)
        assertThat(AppFilter(mockContext).shouldShowApp(ComponentName("work.app", "Widget"), user))
            .isTrue()
        verify(service).getApplicationInfo("work.app", 0, user)
    }
}
