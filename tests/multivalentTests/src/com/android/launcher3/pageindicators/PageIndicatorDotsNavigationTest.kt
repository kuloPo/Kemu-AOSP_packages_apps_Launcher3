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
package com.android.launcher3.pageindicators

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageIndicatorDotsNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val selected = ArrayList<Int>()

    private class TestDots(context: Context) : PageIndicatorDots(context) {
        fun drawMarkers() {
            super.onDraw(Canvas(Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun dots(rtl: Boolean = false): PageIndicatorDots {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLayoutDirection(if (rtl) Locale("ar") else Locale.US)
        return TestDots(context.createConfigurationContext(configuration)).apply {
            setMarkersCount(4)
            setOnPageClickListener { selected.add(it) }
            setPaintColor(Color.WHITE)
            measure(
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 240, 48)
            // Finish the fade-in before exercising visible page markers.
            setShouldAutoHide(true)
            setScroll(1, 300)
            skipAnimationsToEnd()
            setShouldAutoHide(false)
            stopAllAnimations()
            drawMarkers()
        }
    }

    @Test
    fun eachAccessiblePageHasALabelSelectionAndIndependentAction() {
        val dots = dots()
        val provider = dots.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertFalse(host.isClickable)
        for (page in 0..3) {
            val node = provider.createAccessibilityNodeInfo(page)!!
            assertTrue(node.contentDescription.isNotEmpty())
            assertEquals(page == 0, node.isSelected)
            assertTrue(provider.performAction(page, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        }
        assertEquals(listOf(0, 1, 2, 3), selected)
        dots.setEnabled(false)
        assertFalse(provider.performAction(1, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
    }

    @Test
    fun keyboardFocusAndEnterSelectTheFocusedPage() {
        val dots = dots()
        // A detached view has no window to leave touch mode on keyboard input.
        dots.isFocusableInTouchMode = true
        val provider = dots.accessibilityNodeProvider!!
        provider.performAction(1, AccessibilityNodeInfoCompat.ACTION_FOCUS, null)
        assertTrue(
            dots.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        )
        assertTrue(dots.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(2), selected)
    }

    @Test
    fun rtlAccessibleBoundsFollowLogicalPages() {
        val dots = dots(true)
        val provider = dots.accessibilityNodeProvider!!
        val first = Rect()
        val last = Rect()
        provider.createAccessibilityNodeInfo(0)!!.getBoundsInParent(first)
        provider.createAccessibilityNodeInfo(3)!!.getBoundsInParent(last)
        assertTrue(first.centerX() > last.centerX())
        assertTrue(provider.performAction(0, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        assertEquals(listOf(0), selected)
    }

    @Test
    fun horizontalGestureReplaysDownAndForwardsThroughUpWithoutClick() {
        val dots = dots()
        val events = ArrayList<Int>()
        dots.setPageSwipeTarget(
            object : View(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    events.add(event.actionMasked)
                    return true
                }
            }
        )
        fun send(action: Int, x: Float, time: Long) {
            val event = MotionEvent.obtain(0, time, action, x, 24f, 0)
            dots.onTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 200f, 0)
        send(MotionEvent.ACTION_MOVE, 20f, 100)
        send(MotionEvent.ACTION_UP, 0f, 200)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            events,
        )
        assertTrue(selected.isEmpty())
    }
}
