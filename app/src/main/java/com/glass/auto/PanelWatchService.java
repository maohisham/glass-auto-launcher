package com.glass.auto;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches window changes and puts the configured map/media/panel-3 apps back into their
 * panel when something else (taskbar, recents, another launcher) opens them fullscreen or
 * anywhere that overlaps the status bar or dock. Only window bounds and package names are read.
 */
public class PanelWatchService extends AccessibilityService {

    private static final int  TOLERANCE_PX = 24;
    private static final long COOLDOWN_MS  = 8000;
    private static final int  MAX_TRIES    = 3;
    private static final long AFTER_OWN_LAUNCH_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check  = this::checkWindows;
    private final Map<String, Long> lastFix = new HashMap<>();
    private final Map<String, Integer> fixTries = new HashMap<>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        handler.removeCallbacks(check);
        handler.postDelayed(check, 700);
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(check);
        super.onDestroy();
    }

    private void checkWindows() {
        CarActivity car = CarActivity.instance;
        if (car == null || car.isSettingsOpen()) return;
        long now = SystemClock.uptimeMillis();
        if (now - car.lastOwnLaunchMs < AFTER_OWN_LAUNCH_MS) return;
        Set<String> watched = car.watchedPackages();
        if (watched.isEmpty()) return;

        List<AccessibilityWindowInfo> windows = getWindows();
        Set<String> seenInPlace = new HashSet<>();
        boolean sawWindow = false, allInPlace = true;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkgCs = root.getPackageName();
            if (pkgCs == null) continue;
            String pkg = pkgCs.toString();
            if (!watched.contains(pkg)) continue;
            Rect expected = car.panelBoundsFor(pkg);
            if (expected == null) continue;

            w.getBoundsInScreen(r);
            boolean misplaced = Math.abs(r.left - expected.left) > TOLERANCE_PX
                    || Math.abs(r.top - expected.top) > TOLERANCE_PX
                    || Math.abs(r.right - expected.right) > TOLERANCE_PX
                    || Math.abs(r.bottom - expected.bottom) > TOLERANCE_PX;
            sawWindow = true;
            if (!misplaced) { seenInPlace.add(pkg); continue; }
            allInPlace = false;

            // After a layout change the open apps keep their old bounds until restarted;
            // only fix apps that are outside the panel area (e.g. reopened fullscreen).
            if (car.layoutPending && !car.isOutsideContent(r)) continue;

            Long last = lastFix.get(pkg);
            if (last != null && now - last < COOLDOWN_MS) continue;
            int tries = fixTries.containsKey(pkg) ? fixTries.get(pkg) : 0;
            if (tries >= MAX_TRIES) continue; // the system will not honour the bounds; stop restarting the app
            lastFix.put(pkg, now);
            fixTries.put(pkg, tries + 1);
            car.enforcePanelBounds(pkg);
        }
        for (String p : seenInPlace) fixTries.remove(p);
        if (sawWindow && allInPlace) car.layoutPending = false;
    }
}
