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
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.database.sqlite.SQLiteException
import com.android.launcher3.AppFilter
import com.android.launcher3.FlatWorkspace
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.logging.FileLog
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.LoaderParams
import com.android.launcher3.pm.UserManagerState
import com.android.launcher3.util.GridOccupancy
import java.net.URISyntaxException
import java.text.Collator
import java.util.TreeMap

/** Reconciles the persistent workspace before the loader publishes any of its contents. */
object FlatWorkspaceModel {
    private data class AppKey(val component: ComponentName, val serial: Long)

    private data class Row(
        val id: Int,
        val type: Int,
        val container: Int,
        val screen: Int,
        val x: Int,
        val y: Int,
        val serial: Long,
        var component: ComponentName?,
        val packageName: String?,
    )

    @JvmStatic
    fun reconcile(
        context: Context,
        db: ModelDbController,
        idp: InvariantDeviceProfile,
        users: UserManagerState,
        appFilter: AppFilter,
    ) {
        if (!FlatWorkspace.isEnabled(context)) return
        val launcherApps = context.getSystemService(LauncherApps::class.java)!!
        val available = LinkedHashMap<AppKey, LauncherActivityInfo>()
        val packageVisibility = HashMap<Pair<String, Long>, Boolean>()
        for (user in users.getAllCachedInfos()) {
            // Private profiles must never be exposed on the shared workspace.
            if (!user.isUnlocked || user.isQuietModeEnabled || user.iconInfo.isPrivate) continue
            val activities =
                try {
                    launcherApps.getActivityList(null, user.iconInfo.user)
                } catch (_: SecurityException) {
                    continue
                }
            for (activity in activities) {
                packageVisibility[
                    activity.applicationInfo.packageName to user.iconInfo.userSerial] =
                    appFilter.shouldShowPackage(activity.applicationInfo)
                if (appFilter.shouldShowApp(activity.componentName, activity.applicationInfo)) {
                    available[AppKey(activity.componentName, user.iconInfo.userSerial)] = activity
                }
            }
        }

        db.newTransaction().use { transaction ->
            val rows = readRows(db).toMutableList()
            // Normalize disabled aliases before deciding which application icons are missing.
            // Keep the oldest placement when an earlier load has already inserted a duplicate.
            val duplicateIds = HashSet<Int>()
            val seen = HashSet<AppKey>()
            for (row in rows) {
                if (row.type != Favorites.ITEM_TYPE_APPLICATION) continue
                val key = row.component?.let { AppKey(it, row.serial) }
                val installed =
                    if (key != null && key in available) key
                    else
                        available.keys.firstOrNull {
                            it.serial == row.serial && it.component.packageName == row.packageName
                        }
                if (installed == null) continue
                if (!seen.add(installed)) {
                    duplicateIds.add(row.id)
                    continue
                }
                if (row.component != installed.component) {
                    row.component = installed.component
                    val values =
                        ContentValues().apply {
                            put(
                                Favorites.INTENT,
                                AppInfo.makeLaunchIntent(installed.component).toUri(0),
                            )
                            put(Favorites.RESTORED, 0)
                        }
                    requireUpdate(db, values, row.id)
                }
            }
            val removed =
                rows.filter { row ->
                    row.id in duplicateIds ||
                        row.type == Favorites.ITEM_TYPE_APP_PAIR ||
                        row.type == Favorites.ITEM_TYPE_APPWIDGET ||
                        row.type == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET ||
                        (row.component != null && !appFilter.shouldShowApp(row.component)) ||
                        (row.packageName != null &&
                            !packageVisibility.getOrPut(row.packageName to row.serial) {
                                appFilter.shouldShowPackage(
                                    row.packageName,
                                    users.getUser(row.serial),
                                )
                            })
                }
            removed.forEach { requireDelete(db, it.id) }
            rows.removeAll(removed.toSet())

            // Drop empty folders left by filtering, retaining the contents of every other folder.
            val emptyFolders =
                rows.filter { folder ->
                    folder.type == Favorites.ITEM_TYPE_FOLDER &&
                        rows.none { it.container == folder.id }
                }
            emptyFolders.forEach { requireDelete(db, it.id) }
            rows.removeAll(emptyFolders.toSet())

            val screens = TreeMap<Int, GridOccupancy>()
            screens[0] = GridOccupancy(idp.numColumns, idp.numRows)
            val relocate = ArrayList<Row>()
            val folderIds =
                rows.filter { it.type == Favorites.ITEM_TYPE_FOLDER }.map { it.id }.toSet()
            for (row in rows) {
                when {
                    row.container == Favorites.CONTAINER_DESKTOP -> {
                        val grid =
                            screens.getOrPut(row.screen) {
                                GridOccupancy(idp.numColumns, idp.numRows)
                            }
                        if (
                            row.screen >= 0 &&
                                row.x >= 0 &&
                                row.y >= 0 &&
                                row.x < idp.numColumns &&
                                row.y < idp.numRows &&
                                grid.isRegionVacant(row.x, row.y, 1, 1)
                        ) {
                            grid.markCells(row.x, row.y, 1, 1, true)
                        } else {
                            relocate.add(row)
                        }
                    }
                    row.container == Favorites.CONTAINER_HOTSEAT ||
                        row.container >= 0 && row.container !in folderIds -> relocate.add(row)
                }
            }
            screens.keys.removeAll { it < 0 }

            fun place(): ContentValues {
                val cell = IntArray(2)
                var screen =
                    screens.entries.firstOrNull { it.value.findVacantCell(cell, 1, 1) }?.key
                if (screen == null) {
                    screen = maxOf(0, screens.lastKey()) + 1
                    screens[screen] = GridOccupancy(idp.numColumns, idp.numRows)
                    check(screens.getValue(screen).findVacantCell(cell, 1, 1))
                }
                screens.getValue(screen).markCells(cell[0], cell[1], 1, 1, true)
                return ContentValues().apply {
                    put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                    put(Favorites.SCREEN, screen)
                    put(Favorites.CELLX, cell[0])
                    put(Favorites.CELLY, cell[1])
                    put(Favorites.SPANX, 1)
                    put(Favorites.SPANY, 1)
                }
            }

            relocate.forEach { requireUpdate(db, place(), it.id) }
            val existing =
                rows
                    .filter { it.type == Favorites.ITEM_TYPE_APPLICATION }
                    .mapNotNull { row -> row.component?.let { AppKey(it, row.serial) } }
                    .toHashSet()
            val collator = Collator.getInstance()
            val missing =
                available.entries
                    .filter { it.key !in existing }
                    .sortedWith { a, b ->
                        val labelOrder =
                            collator.compare(a.value.label.toString(), b.value.label.toString())
                        if (labelOrder != 0) labelOrder
                        else
                            compareValuesBy(
                                a.key,
                                b.key,
                                { it.serial },
                                { it.component.flattenToString() },
                            )
                    }
            for ((key, activity) in missing) {
                val values =
                    place().apply {
                        put(Favorites._ID, db.generateNewItemId())
                        put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                        put(Favorites.PROFILE_ID, key.serial)
                        put(Favorites.TITLE, activity.label.toString())
                        put(Favorites.INTENT, AppInfo.makeLaunchIntent(key.component).toUri(0))
                    }
                if (db.insert(values) < 0)
                    throw SQLiteException("Failed to add flat workspace application")
            }
            transaction.commit()
        }
    }

    /** Leaves the previous layout available if a synchronization write fails. */
    @JvmStatic
    fun reconcileIfNeeded(
        context: Context,
        db: ModelDbController,
        idp: InvariantDeviceProfile,
        users: UserManagerState,
        appFilter: AppFilter,
        params: LoaderParams,
    ): Boolean {
        if (
            !FlatWorkspace.isEnabled(context) ||
                params.workspaceSelection.isNotEmpty() ||
                !params.sanitizeData ||
                idp.dbFile != db.databaseName
        )
            return false
        return try {
            reconcile(context, db, idp, users, appFilter)
            true
        } catch (e: SQLiteException) {
            FileLog.e(
                "FlatWorkspaceModel",
                "Unable to synchronize workspace; retaining saved layout",
                e,
            )
            false
        }
    }

    private fun requireUpdate(db: ModelDbController, values: ContentValues, id: Int) {
        if (db.update(values, "${Favorites._ID}=?", arrayOf(id.toString())) != 1) {
            throw SQLiteException("Failed to update flat workspace item")
        }
    }

    private fun requireDelete(db: ModelDbController, id: Int) {
        if (db.delete("${Favorites._ID}=?", arrayOf(id.toString())) != 1) {
            throw SQLiteException("Failed to delete flat workspace item")
        }
    }

    private fun readRows(db: ModelDbController): List<Row> {
        val rows = ArrayList<Row>()
        db.query(null, null, null, Favorites._ID).use { cursor ->
            fun int(column: String) = cursor.getInt(cursor.getColumnIndexOrThrow(column))
            while (cursor.moveToNext()) {
                val intent =
                    try {
                        cursor.getString(cursor.getColumnIndexOrThrow(Favorites.INTENT))?.let {
                            Intent.parseUri(it, 0)
                        }
                    } catch (_: URISyntaxException) {
                        null
                    }
                rows.add(
                    Row(
                        int(Favorites._ID),
                        int(Favorites.ITEM_TYPE),
                        int(Favorites.CONTAINER),
                        int(Favorites.SCREEN),
                        int(Favorites.CELLX),
                        int(Favorites.CELLY),
                        cursor.getLong(cursor.getColumnIndexOrThrow(Favorites.PROFILE_ID)),
                        intent?.component,
                        intent?.component?.packageName ?: intent?.getPackage(),
                    )
                )
            }
        }
        return rows
    }
}
