package com.eyecare.app

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Counts vertical scrolls inside short-video apps as "one reel/short".
 * It reads NO text or screen content (canRetrieveWindowContent = false).
 * Note: this is an approximation – one swipe = one reel.
 */
class ReelAccessibilityService : AccessibilityService() {

    private var lastCounted = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Live.a11yOn = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Live.a11yOn = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in Apps.SHORT) return
        // ignore pure horizontal scrolls (stories rails, carousels)
        if (Build.VERSION.SDK_INT >= 28 && e.scrollDeltaY == 0 && e.scrollDeltaX != 0) return

        val now = System.currentTimeMillis()
        Live.fgPkg = pkg
        Live.lastScrollAt = now
        if (now - lastCounted > 900) {      // one swipe fires many events; count once
            lastCounted = now
            Store.addReel(this, now)
        }
    }

    override fun onInterrupt() {}
}
