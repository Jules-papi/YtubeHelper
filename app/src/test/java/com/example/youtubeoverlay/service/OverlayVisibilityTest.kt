package com.example.youtubeoverlay.service

import android.view.accessibility.AccessibilityEvent
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.Rule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = HiltTestApplication::class)
@HiltAndroidTest
class OverlayVisibilityTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private lateinit var service: OverlayAccessibilityService

    @Before
    fun setup() {
        service = spyk(Robolectric.buildService(OverlayAccessibilityService::class.java).create().get())
        // Avoid actually trying to start foreground service in Robolectric easily here
        every { service.startForegroundService(any()) } returns null
    }

    @Test
    fun `when youtube package is active, should trigger start overlay service`() {
        val event = mockk<AccessibilityEvent>()
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns "com.google.android.youtube"

        service.onAccessibilityEvent(event)

        verify(exactly = 1) { service.startForegroundService(any()) }
    }

    @Test
    fun `when switching away from youtube, should trigger hide overlay service`() {
        val youtubeEvent = mockk<AccessibilityEvent>()
        every { youtubeEvent.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { youtubeEvent.packageName } returns "com.google.android.youtube"

        val otherEvent = mockk<AccessibilityEvent>()
        every { otherEvent.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { otherEvent.packageName } returns "com.other.app"

        service.onAccessibilityEvent(youtubeEvent)
        service.onAccessibilityEvent(otherEvent)

        // It should start once, and then trigger another intent to hide once
        verify(exactly = 2) { service.startForegroundService(any()) }
    }
}
