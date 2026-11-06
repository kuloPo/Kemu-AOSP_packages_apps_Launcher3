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
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.launcher3.LauncherModel.ModelUpdateTask
import com.android.launcher3.util.PackageUserKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class FlatWorkspaceUpdateQueueTest {
    private val handler: Handler = mock()
    private val immediate = ArrayDeque<Runnable>()
    private val delayed = LinkedHashSet<Runnable>()
    private var ready = false
    private val applied = ArrayList<ModelUpdateTask>()
    private val reconciled = ArrayList<Pair<Set<PackageUserKey>, Boolean>>()
    private val user = Process.myUserHandle()
    private val queue =
        FlatWorkspaceUpdateQueue(handler, { ready }, applied::add) { keys, profile ->
            reconciled.add(keys to profile)
        }

    init {
        whenever(handler.post(any())).thenAnswer {
            immediate.addLast(it.getArgument(0))
            true
        }
        whenever(handler.postDelayed(any(), any<Long>())).thenAnswer {
            delayed.add(it.getArgument(0))
            true
        }
        org.mockito.kotlin
            .doAnswer {
                delayed.remove(it.getArgument<Runnable>(0))
                null
            }
            .whenever(handler)
            .removeCallbacks(any())
    }

    private fun drain() {
        while (immediate.isNotEmpty()) immediate.removeFirst().run()
        val callbacks = delayed.toList()
        delayed.clear()
        callbacks.forEach { it.run() }
    }

    @Test
    fun changesDuringLoadingAreReplayedInOrderAfterModelReady() {
        val first: ModelUpdateTask = mock()
        val second: ModelUpdateTask = mock()
        queue.enqueueTask(first)
        queue.packagesChanged(listOf("example.one"), user)
        queue.enqueueTask(second)
        queue.packagesChanged(listOf("example.two"), user)
        drain()
        assertTrue(applied.isEmpty())
        assertTrue(reconciled.isEmpty())
        ready = true
        queue.onModelReady()
        drain()
        assertEquals(listOf(first, second), applied)
        assertEquals(
            listOf(
                setOf(PackageUserKey("example.one", user), PackageUserKey("example.two", user)) to
                    false
            ),
            reconciled,
        )
        queue.onModelReady()
        drain()
        assertEquals(1, reconciled.size)
    }

    @Test
    fun burstOfPackagesAndDuplicateProfileEventsReconcilesOnce() {
        ready = true
        val task: ModelUpdateTask = mock()
        queue.enqueueTask(task)
        repeat(3) { queue.packagesChanged(listOf("example.one"), user) }
        repeat(2) { queue.profileChanged() }
        drain()
        assertEquals(listOf(task), applied)
        assertEquals(listOf(setOf(PackageUserKey("example.one", user)) to true), reconciled)
    }

    @Test
    fun destroyedQueueDiscardsPendingAndFutureChanges() {
        queue.enqueueTask(mock())
        queue.packagesChanged(listOf("example.one"), user)
        drain()
        queue.close()
        ready = true
        queue.onModelReady()
        queue.enqueueTask(mock())
        queue.profileChanged()
        drain()
        assertTrue(applied.isEmpty())
        assertTrue(reconciled.isEmpty())
    }
}
