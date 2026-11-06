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
package com.android.launcher3.model

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.launcher3.AppFilter
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.data.LoaderParams
import com.android.launcher3.pm.UserCache.CachedUserInfo
import com.android.launcher3.pm.UserManagerState
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction
import com.android.launcher3.util.UserIconInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Exercises real database transactions without installing or restarting Launcher. */
@RunWith(AndroidJUnit4::class)
class FlatWorkspaceModelTest {
    private val context: Context = mock()
    private val resources: Resources = mock()
    private val launcherApps: LauncherApps = mock()
    private val db: ModelDbController = mock()
    private val idp: InvariantDeviceProfile = mock()
    private val user = Process.myUserHandle()
    private val users =
        UserManagerState(
            mapOf(
                user to CachedUserInfo(UserIconInfo(user, UserIconInfo.TYPE_MAIN, 0), true, false)
            )
        )
    private val activities = ArrayList<LauncherActivityInfo>()
    private val packages = HashMap<String, ApplicationInfo>()
    private lateinit var database: SQLiteDatabase
    private var nextId = 1000

    @Before
    fun setUp() {
        database = SQLiteDatabase.create(null)
        Favorites.addTableToDb(database, 0, false)
        idp.dbFile = "launcher.db"
        whenever(db.databaseName).thenReturn("launcher.db")
        idp.numColumns = 2
        idp.numRows = 2
        whenever(context.resources).thenReturn(resources)
        whenever(resources.getBoolean(R.bool.config_flat_workspace)).thenReturn(true)
        whenever(resources.getStringArray(R.array.config_flat_workspace_system_apps))
            .thenReturn(arrayOf("com.android.settings"))
        whenever(resources.getStringArray(R.array.filtered_components)).thenReturn(emptyArray())
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        whenever(launcherApps.getActivityList(null, user)).thenReturn(activities)
        whenever(launcherApps.getApplicationInfo(any(), eq(0), eq(user))).thenAnswer {
            packages[it.getArgument<String>(0)] ?: throw PackageManager.NameNotFoundException()
        }
        whenever(db.newTransaction()).thenAnswer { SQLiteTransaction(database) }
        whenever(db.generateNewItemId()).thenAnswer { nextId++ }
        whenever(db.query(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            database.query(
                Favorites.TABLE_NAME,
                it.getArgument(0),
                it.getArgument(1),
                it.getArgument(2),
                null,
                null,
                it.getArgument(3),
            )
        }
        whenever(db.insert(any())).thenAnswer {
            database.insertOrThrow(Favorites.TABLE_NAME, null, it.getArgument(0)).toInt()
        }
        whenever(db.update(any(), anyOrNull(), anyOrNull())).thenAnswer {
            database.update(
                Favorites.TABLE_NAME,
                it.getArgument(0),
                it.getArgument(1),
                it.getArgument(2),
            )
        }
        whenever(db.delete(anyOrNull(), anyOrNull())).thenAnswer {
            database.delete(Favorites.TABLE_NAME, it.getArgument(0), it.getArgument(1))
        }
    }

    @After fun tearDown() = database.close()

    @Test
    fun fillsFirstPageAndCreatesPagesForAllVisibleApps() {
        app("com.android.settings", ApplicationInfo.FLAG_SYSTEM)
        app("com.android.camera", ApplicationInfo.FLAG_SYSTEM)
        app("com.android.browser")
        repeat(4) { app("game.$it") }
        sync()
        val rows = rows()
        assertEquals(6, rows.size)
        assertEquals(setOf(0, 1), rows.map { it.screen }.toSet())
        assertFalse(rows.any { it.pkg == "com.android.camera" })
        assertTrue(rows.any { it.pkg == "com.android.browser" })
        assertEquals(rows.size, rows.map { Triple(it.screen, it.x, it.y) }.toSet().size)
    }

    @Test
    fun migratesHotseatAndFiltersFoldersWithoutDuplicatingApps() {
        app("game.desktop")
        app("game.docked")
        app("game.folder")
        app("com.android.camera", ApplicationInfo.FLAG_SYSTEM)
        app("com.android.browser", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        row(1, "game.desktop", x = 1, y = 1)
        row(2, "game.docked", container = Favorites.CONTAINER_HOTSEAT)
        row(3, null, type = Favorites.ITEM_TYPE_FOLDER, x = 1)
        row(4, "game.folder", container = 3)
        row(5, "com.android.camera", container = 3)
        row(6, "com.android.browser", y = 1)
        row(7, null, type = Favorites.ITEM_TYPE_APPWIDGET)
        row(8, null, type = Favorites.ITEM_TYPE_FOLDER)
        row(9, "com.android.camera", container = 8)
        sync()
        val migrated = rows()
        assertEquals(setOf(1, 2, 3, 4), migrated.map { it.id }.toSet())
        assertEquals(Favorites.CONTAINER_DESKTOP, migrated.single { it.id == 2 }.container)
        assertEquals(3, migrated.single { it.id == 4 }.container)
        assertEquals(1, migrated.single { it.id == 1 }.x)
        assertEquals(1, migrated.single { it.id == 1 }.y)
        sync()
        assertEquals(migrated, rows())
    }

    @Test
    fun repairsOverlapsAndPreservesExistingOrderWhenAnAppIsAdded() {
        app("game.first")
        app("game.second")
        row(1, "game.first")
        row(2, "game.second")
        sync()
        val original = rows()
        assertEquals(2, original.map { Triple(it.screen, it.x, it.y) }.toSet().size)
        app("game.new")
        sync()
        assertEquals(original, rows().filter { it.id < 1000 })
        assertEquals(3, rows().size)
    }

    @Test
    fun insertFailureRollsBackFilteringAndMigration() {
        app("com.android.camera", ApplicationInfo.FLAG_SYSTEM)
        app("game.new")
        row(1, "com.android.camera", container = Favorites.CONTAINER_HOTSEAT)
        val original = rows()
        whenever(db.insert(any())).thenReturn(-1)
        var failed = false
        try {
            sync()
        } catch (_: SQLiteException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(original, rows())
    }

    @Test
    fun completedInstallationPlaceholderDoesNotDuplicateItsApp() {
        app("game.pending")
        row(1, "game.pending", x = 1, y = 1)
        val placeholder =
            ContentValues().apply {
                put(Favorites.RESTORED, 2)
                put(
                    Favorites.INTENT,
                    Intent(Intent.ACTION_MAIN)
                        .setComponent(ComponentName("game.pending", "pending"))
                        .toUri(0),
                )
            }
        database.update(Favorites.TABLE_NAME, placeholder, "${Favorites._ID}=1", null)
        sync()
        assertEquals(1, rows().size)
        assertEquals(1, rows().single().id)
        assertEquals(1, rows().single().x)
        sync()
        assertEquals(1, rows().size)
    }

    @Test
    fun expandsAppPairsWithoutLosingTheirApplications() {
        app("game.first")
        app("game.second")
        row(1, null, type = Favorites.ITEM_TYPE_APP_PAIR)
        row(2, "game.first", container = 1)
        row(3, "game.second", container = 1)
        sync()
        assertEquals(setOf(2, 3), rows().map { it.id }.toSet())
        assertTrue(rows().all { it.container == Favorites.CONTAINER_DESKTOP })
    }

    @Test
    fun disabledModeLeavesDatabaseUnchanged() {
        app("game.new")
        row(1, null, type = Favorites.ITEM_TYPE_APPWIDGET)
        val original = rows()
        whenever(resources.getBoolean(R.bool.config_flat_workspace)).thenReturn(false)
        sync()
        assertEquals(original, rows())
    }

    @Test
    fun disabledAliasReusesItsFolderPositionAndRemovesAnExistingDuplicate() {
        app("game.alias")
        row(1, null, type = Favorites.ITEM_TYPE_FOLDER, x = 1, y = 1)
        row(2, "game.alias", container = 1)
        row(3, "game.alias")
        database.update(
            Favorites.TABLE_NAME,
            ContentValues().apply {
                put(
                    Favorites.INTENT,
                    Intent(Intent.ACTION_MAIN)
                        .setComponent(ComponentName("game.alias", "game.alias.OldAlias"))
                        .toUri(0),
                )
            },
            "${Favorites._ID}=2",
            null,
        )
        sync()
        assertEquals(setOf(1, 2), rows().map { it.id }.toSet())
        assertEquals(1, rows().single { it.id == 2 }.container)
        val original = rows()
        sync()
        assertEquals(original, rows())
    }

    @Test
    fun multipleEnabledActivitiesFromOnePackageRemainDistinct() {
        app("game.multiple")
        val second: LauncherActivityInfo = mock()
        whenever(second.applicationInfo).thenReturn(packages.getValue("game.multiple"))
        whenever(second.componentName)
            .thenReturn(ComponentName("game.multiple", "game.multiple.Other"))
        whenever(second.label).thenReturn("Other")
        activities.add(second)
        sync()
        sync()
        assertEquals(2, rows().size)
    }

    @Test
    fun writeFailureKeepsSavedLayoutAndDoesNotEscapeTheLoaderBoundary() {
        app("com.android.camera", ApplicationInfo.FLAG_SYSTEM)
        app("game.new")
        row(1, "com.android.camera")
        val original = rows()
        whenever(db.insert(any())).thenReturn(-1)
        assertFalse(load())
        assertEquals(original, rows())
    }

    @Test
    fun updateFailureRollsBackRelocation() {
        app("game.alias")
        row(1, "game.alias", container = Favorites.CONTAINER_HOTSEAT)
        val original = rows()
        whenever(db.update(any(), anyOrNull(), anyOrNull())).thenReturn(0)
        assertFalse(load())
        assertEquals(original, rows())
    }

    @Test
    fun alternateDatabaseAndPreviewParametersAreNotReconciled() {
        app("game.new")
        row(1, null, type = Favorites.ITEM_TYPE_APPWIDGET)
        val original = rows()
        whenever(db.databaseName).thenReturn("preview.db")
        assertFalse(load())
        whenever(db.databaseName).thenReturn("launcher.db")
        assertFalse(load(LoaderParams("", false, false)))
        assertFalse(load(LoaderParams("screen=0", true, true)))
        assertEquals(original, rows())
    }

    @Test
    fun injectedComponentFilterAlsoAppliesToWorkspace() {
        app("game.hidden")
        val filter =
            object : AppFilter(context) {
                override fun shouldShowApp(component: ComponentName) = false
            }
        FlatWorkspaceModel.reconcile(context, db, idp, users, filter)
        assertTrue(rows().isEmpty())
    }

    private fun load(params: LoaderParams = LoaderParams()) =
        FlatWorkspaceModel.reconcileIfNeeded(context, db, idp, users, AppFilter(context), params)

    private fun sync() = FlatWorkspaceModel.reconcile(context, db, idp, users, AppFilter(context))

    private fun app(pkg: String, flags: Int = 0) {
        val info =
            ApplicationInfo().apply {
                packageName = pkg
                this.flags = flags
            }
        packages[pkg] = info
        val activity: LauncherActivityInfo = mock()
        whenever(activity.applicationInfo).thenReturn(info)
        whenever(activity.componentName).thenReturn(ComponentName(pkg, "$pkg.Main"))
        whenever(activity.label).thenReturn(pkg)
        activities.add(activity)
    }

    private fun row(
        id: Int,
        pkg: String?,
        type: Int = Favorites.ITEM_TYPE_APPLICATION,
        container: Int = Favorites.CONTAINER_DESKTOP,
        x: Int = 0,
        y: Int = 0,
    ) {
        val values =
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.ITEM_TYPE, type)
                put(Favorites.CONTAINER, container)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, x)
                put(Favorites.CELLY, y)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                if (pkg != null)
                    put(
                        Favorites.INTENT,
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(ComponentName(pkg, "$pkg.Main"))
                            .toUri(0),
                    )
            }
        database.insertOrThrow(Favorites.TABLE_NAME, null, values)
    }

    private data class SavedRow(
        val id: Int,
        val container: Int,
        val screen: Int,
        val x: Int,
        val y: Int,
        val pkg: String?,
    )

    private fun rows(): List<SavedRow> {
        val rows = ArrayList<SavedRow>()
        database.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { c ->
            fun int(name: String) = c.getInt(c.getColumnIndexOrThrow(name))
            while (c.moveToNext()) {
                val intent = c.getString(c.getColumnIndexOrThrow(Favorites.INTENT))
                rows.add(
                    SavedRow(
                        int(Favorites._ID),
                        int(Favorites.CONTAINER),
                        int(Favorites.SCREEN),
                        int(Favorites.CELLX),
                        int(Favorites.CELLY),
                        intent?.let { Intent.parseUri(it, 0).component?.packageName },
                    )
                )
            }
        }
        return rows
    }
}
