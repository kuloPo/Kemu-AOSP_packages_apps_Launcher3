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

import android.os.Handler
import android.os.UserHandle
import com.android.launcher3.LauncherModel.ModelUpdateTask
import com.android.launcher3.util.PackageUserKey

/** Serializes package changes across loads and coalesces workspace reconciliation requests. */
class FlatWorkspaceUpdateQueue(
    private val handler: Handler,
    private val isReady: () -> Boolean,
    private val applyTask: (ModelUpdateTask) -> Unit,
    private val reconcile: (Set<PackageUserKey>, Boolean) -> Unit,
) {
    private val pendingTasks = ArrayList<ModelUpdateTask>()
    private val packages = LinkedHashSet<PackageUserKey>()
    private var profileChanged = false
    private var closed = false

    private val flush = Runnable {
        if (!closed && isReady()) {
            val tasks = pendingTasks.toList()
            val changed = packages.toSet()
            val profiles = profileChanged
            pendingTasks.clear()
            packages.clear()
            profileChanged = false
            tasks.forEach(applyTask)
            if (changed.isNotEmpty() || profiles) reconcile(changed, profiles)
        }
    }

    fun enqueueTask(task: ModelUpdateTask) {
        handler.post {
            if (!closed) {
                if (isReady()) applyTask(task) else pendingTasks.add(task)
            }
        }
    }

    fun packagesChanged(names: List<String>, user: UserHandle) {
        handler.post {
            if (!closed) {
                names.forEach { packages.add(PackageUserKey(it, user)) }
                schedule()
            }
        }
    }

    fun profileChanged() {
        handler.post {
            if (!closed) {
                profileChanged = true
                schedule()
            }
        }
    }

    fun onModelReady() {
        handler.post {
            if (!closed && (pendingTasks.isNotEmpty() || packages.isNotEmpty() || profileChanged)) {
                schedule()
            }
        }
    }

    private fun schedule() {
        handler.removeCallbacks(flush)
        handler.postDelayed(flush, 200)
    }

    fun close() {
        handler.post {
            closed = true
            handler.removeCallbacks(flush)
            pendingTasks.clear()
            packages.clear()
        }
    }
}
