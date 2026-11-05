package com.glass.auto;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Plain-text report on why split screen did or did not open. Shown from Settings so it can be read
 * (or photographed) on a head unit where adb is not available.
 */
final class SplitDiagnostics {

    private static final String TAG = "GlassAuto.Split";
    private static final int MAX_LINES = 40;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private SplitDiagnostics() { }

    /** Records one step of the split launch sequence. */
    static synchronized void log(String line) {
        Log.d(TAG, line);
        LOG.addLast(TIME.format(new Date()) + "  " + line);
        while (LOG.size() > MAX_LINES) LOG.removeFirst();
    }

    /** Framework flag from the firmware's own config, or "n/a" when this ROM does not define it. */
    private static String frameworkBool(String name) {
        try {
            int id = Resources.getSystem().getIdentifier(name, "bool", "android");
            return id == 0 ? "n/a" : String.valueOf(Resources.getSystem().getBoolean(id));
        } catch (Exception e) {
            return "n/a";
        }
    }

    static synchronized String report(Activity ctx, Config cfg) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = ctx.getPackageManager();
        PanelWatchService svc = PanelWatchService.instance;

        sb.append("DEVICE\n");
        sb.append("  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("  Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("  ").append(Build.DISPLAY).append('\n');

        sb.append("\nFIRMWARE SUPPORT\n");
        sb.append("  split screen allowed:  ").append(frameworkBool("config_supportsSplitScreenMultiWindow")).append('\n');
        sb.append("  multi-window allowed:  ").append(frameworkBool("config_supportsMultiWindow")).append('\n');
        sb.append("  freeform allowed:      ").append(frameworkBool("config_freeformWindowManagement")).append('\n');
        sb.append("  freeform feature:      ").append(pm.hasSystemFeature("android.software.freeform_window_management")).append('\n');
        sb.append("  automotive build:      ").append(pm.hasSystemFeature("android.hardware.type.automotive")).append('\n');

        sb.append("\nGLASS AUTO\n");
        sb.append("  layout mode: ").append(cfg.panelMode).append("   apps: ").append(cfg.panelCount).append('\n');
        sb.append("  map:   ").append(cfg.mapPackage.isEmpty() ? "(none)" : cfg.mapPackage).append('\n');
        sb.append("  media: ").append(cfg.mediaPackage.isEmpty() ? "(none)" : cfg.mediaPackage).append('\n');
        sb.append("  accessibility service running: ").append(svc != null).append('\n');
        sb.append("  accessibility service listed:  ").append(PermissionHelper.hasAccessibility(ctx)).append('\n');

        sb.append("\nSPLIT NOW\n");
        if (svc == null) {
            sb.append("  (needs the accessibility service)\n");
        } else {
            sb.append("  system divider present: ").append(svc.hasDivider()).append('\n');
            sb.append(svc.describeWindows());
        }

        sb.append("\nLAST LAUNCH\n");
        if (LOG.isEmpty()) sb.append("  (no split launch yet this run)\n");
        for (String l : LOG) sb.append("  ").append(l).append('\n');
        return sb.toString();
    }
}
