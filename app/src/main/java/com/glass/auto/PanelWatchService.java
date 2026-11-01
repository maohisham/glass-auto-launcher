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
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void checkWindows() {
        CarActivity car = CarActivity.instance;
        if (car == null || car.isSettingsOpen() || stopping) return;
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

    // ---- Force-stop panel apps so they reopen with new bounds ----

    static volatile PanelWatchService instance;
    private static final long STEP_TIMEOUT_MS = 5000;
    private static final long POLL_MS = 300;

    private boolean stopping = false;
    private final java.util.ArrayDeque<String> stopQueue = new java.util.ArrayDeque<>();
    private Runnable stopDone;
    private String stopPkg;
    private long stopStepStart, lastForceClick;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    boolean isStopping() { return stopping; }

    /**
     * Force-stops each package through its App info screen, then runs {@code done}.
     * Android only applies launch bounds to a freshly created task, so this is what lets a
     * layout change take effect on apps that are already open.
     */
    void forceStopAll(java.util.Collection<String> pkgs, Runnable done) {
        if (stopping) return;
        stopping = true;
        stopQueue.clear();
        stopQueue.addAll(pkgs);
        stopDone = done;
        nextForceStop();
    }

    private void nextForceStop() {
        stopPkg = stopQueue.poll();
        if (stopPkg == null) {
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                stopping = false;
                Runnable d = stopDone;
                stopDone = null;
                if (d != null) handler.postDelayed(d, 400);
            }, 500);
            return;
        }
        stopStepStart = SystemClock.uptimeMillis();
        lastForceClick = 0;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", stopPkg, null));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Exception e) {
            nextForceStop();
            return;
        }
        handler.postDelayed(this::stepForceStop, 700);
    }

    private void stepForceStop() {
        long now = SystemClock.uptimeMillis();
        if (now - stopStepStart > STEP_TIMEOUT_MS) { nextForceStop(); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            // Confirmation dialog ("Force stop?" → OK)
            List<AccessibilityNodeInfo> ok = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            if (ok != null && !ok.isEmpty() && lastForceClick != 0) {
                clickNode(ok.get(0));
                handler.postDelayed(this::nextForceStop, 600);
                return;
            }
            if (lastForceClick == 0) {
                List<AccessibilityNodeInfo> fs = root.findAccessibilityNodeInfosByText("Force stop");
                if (fs != null) {
                    for (AccessibilityNodeInfo n : fs) {
                        if (!n.isEnabled() && !hasEnabledClickableParent(n)) continue;
                        if (clickNode(n)) { lastForceClick = now; break; }
                    }
                }
                // Button present but greyed out = the app is not running; nothing to do.
                if (lastForceClick == 0 && fs != null && !fs.isEmpty()
                        && now - stopStepStart > 2000) { nextForceStop(); return; }
            }
        }
        handler.postDelayed(this::stepForceStop, POLL_MS);
    }

    private static boolean hasEnabledClickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        while (p != null) {
            if (p.isClickable() && p.isEnabled()) return true;
            p = p.getParent();
        }
        return false;
    }

    private static boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        while (p != null) {
            if (p.isClickable() && p.isEnabled()) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            p = p.getParent();
        }
        return false;
    }
}
