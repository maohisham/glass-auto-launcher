package com.glass.auto;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-launch setup screen. Walks the user through each permission one at a time.
 * Shown automatically by CarActivity when any permission is missing.
 * Dismissed (finishes) when all grantable permissions are satisfied.
 */
public class SetupActivity extends Activity {

    private static final int RC_OVERLAY  = 101;
    private static final int RC_LOCATION = 102;

    private LinearLayout stepsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF080808);

        stepsContainer = new LinearLayout(this);
        stepsContainer.setOrientation(LinearLayout.VERTICAL);
        stepsContainer.setPadding(48, 64, 48, 64);

        scroll.addView(stepsContainer);
        setContentView(scroll);

        buildUI();
        boolean listenerMissing = !PermissionHelper.hasNotificationListener(this);
        if ((listenerMissing || PermissionHelper.needsAccessibility(this))
                && PermissionHelper.isRestrictedSettings(this)) {
            showRestrictedDialog(listenerMissing ? "Notification access" : "Accessibility",
                    listenerMissing ? () -> PermissionHelper.requestNotificationListener(this)
                                    : () -> PermissionHelper.requestAccessibility(this));
        }
    }

    /** Android 13+ greys out sensitive switches for sideloaded apps until "Allow restricted settings" is on. */
    private void showRestrictedDialog(String what, Runnable proceed) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Allow restricted settings")
            .setMessage("Android blocks " + what + " for apps installed from outside an app store, "
                + "so the switch will look greyed out. Allow it once:\n\n"
                + "1. Tap \"Open App info\".\n"
                + "2. Tap the ⋮ menu (top right) → \"Allow restricted settings\", then confirm.\n"
                + "3. Come back here and tap the step's button again.\n\n"
                + "No ⋮ menu? Tap \"Continue\" and try to switch Glass Auto on first. "
                + "Android then shows a restricted-setting notice, and the menu appears in App info.")
            .setPositiveButton("Open App info", (d, w) -> PermissionHelper.openAppInfo(this))
            .setNeutralButton("Continue", (d, w) -> proceed.run())
            .setNegativeButton("Later", null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh after returning from a settings page
        if (PermissionHelper.allGranted(this)) {
            done();
            return;
        }
        buildUI();
    }

    private void buildUI() {
        stepsContainer.removeAllViews();

        addTitle("Glass Auto Setup");
        addSubtitle("Tap each step to grant access. The app handles everything it can automatically.");

        // --- Step 1: Overlay ---
        boolean overlayOk = PermissionHelper.hasOverlay(this);
        addStep(
            overlayOk,
            "1  Draw over other apps",
            "Lets Glass Auto draw rounded corners and the dock over the map window.",
            "Grant",
            overlayOk ? null : v -> PermissionHelper.requestOverlay(this, RC_OVERLAY)
        );

        // --- Step 2: Notification listener ---
        boolean listenerOk = PermissionHelper.hasNotificationListener(this);
        addStep(
            listenerOk,
            "2  Notification access",
            "Required to read the currently playing track from any media app (Spotify, YouTube Music, etc.).\n\n"
            + "On the next screen: find \"Glass Auto\" and enable it."
            + (!listenerOk && PermissionHelper.isRestrictedSettings(this)
                ? "\n\n⚠ Android is blocking this for sideloaded apps. Tap below for the fix." : ""),
            "Open Settings",
            listenerOk ? null : v -> {
                Runnable open = () -> PermissionHelper.requestNotificationListener(this);
                if (PermissionHelper.isRestrictedSettings(this)) showRestrictedDialog("Notification access", open);
                else open.run();
            }
        );

        // --- Step 3: Accessibility (split screen only; adb can switch it on, but not every unit allows adb) ---
        boolean split = "split".equals(Config.load(this).panelMode);
        if (split) {
            boolean accOk = PermissionHelper.hasAccessibility(this) || PermissionHelper.accessibilitySkipped(this);
            boolean accRunning = PermissionHelper.hasAccessibility(this);
            addStep(
                accRunning,
                "3  Accessibility (split screen)",
                "Lets Glass Auto open the two apps side by side and move the divider. "
                + "It only reads window positions.\n\n"
                + "On the next screen: find \"Glass Auto\" and switch it on."
                + (!accRunning && PermissionHelper.isRestrictedSettings(this)
                    ? "\n\n⚠ Android is blocking this for sideloaded apps. Tap below for the fix." : ""),
                "Open Accessibility",
                accRunning ? null : v -> {
                    Runnable open = () -> PermissionHelper.requestAccessibility(this);
                    if (PermissionHelper.isRestrictedSettings(this)) showRestrictedDialog("Accessibility", open);
                    else open.run();
                }
            );
            if (!accOk) {
                Button skip = new Button(this);
                skip.setText("Skip — only one app will open");
                skip.setOnClickListener(v -> { PermissionHelper.skipAccessibility(this); buildUI(); });
                stepsContainer.addView(skip);
            }
        }

        // --- Step 4: Location ---
        boolean locationOk = PermissionHelper.hasLocation(this);
        addStep(
            locationOk,
            "4  Location (GPS speed)",
            "Used only to read GPS speed. No location data is stored or transmitted.",
            "Allow",
            locationOk ? null : v -> PermissionHelper.requestLocation(this, RC_LOCATION)
        );

        // --- Step 5: Freeform (informational only; split screen does not need it) ---
        if (!split) addFreeformStep(PermissionHelper.isFreeformEnabled(this));

        // --- Done button ---
        if (PermissionHelper.allGranted(this)) {
            Button doneBtn = new Button(this);
            doneBtn.setText("All set — Open Glass Auto");
            doneBtn.setOnClickListener(v -> done());
            stepsContainer.addView(doneBtn);
        }
    }

    private void addStep(boolean done, String title, String desc,
                         String btnLabel, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, 32);

        TextView tv = new TextView(this);
        tv.setText((done ? "✓  " : "○  ") + title);
        tv.setTextColor(done ? 0xFF4CAF50 : Color.WHITE);
        tv.setTextSize(16f);
        row.addView(tv);

        TextView dv = new TextView(this);
        dv.setText(desc);
        dv.setTextColor(0xAAFFFFFF);
        dv.setTextSize(13f);
        dv.setPadding(28, 6, 0, 10);
        row.addView(dv);

        if (!done && action != null) {
            Button btn = new Button(this);
            btn.setText(btnLabel);
            btn.setOnClickListener(action);
            row.addView(btn);
        }

        stepsContainer.addView(row);
        addDivider();
    }

    private void addFreeformStep(boolean detected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, 32);

        TextView tv = new TextView(this);
        tv.setText((detected ? "✓  " : "⚠  ") + "5  Freeform window mode");
        tv.setTextColor(detected ? 0xFF4CAF50 : 0xFFFF9800);
        tv.setTextSize(16f);
        row.addView(tv);

        TextView dv = new TextView(this);
        if (detected) {
            dv.setText("Already enabled on your device. The map panel will work correctly.");
        } else {
            dv.setText("Not detected — this setting requires a one-time adb command.\n"
                + "Most car boxes already have it on; try launching the app first.\n\n"
                + "If the map panel stays blank, run once from your computer:\n\n"
                + "  adb shell settings put global enable_freeform_support 1\n"
                + "  adb shell settings put global force_resizable_activities 1\n"
                + "  adb shell am restart");
        }
        dv.setTextColor(0xAAFFFFFF);
        dv.setTextSize(13f);
        dv.setPadding(28, 6, 0, 10);
        row.addView(dv);

        stepsContainer.addView(row);
        addDivider();
    }

    private void addDivider() {
        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.bottomMargin = 32;
        line.setLayoutParams(lp);
        stepsContainer.addView(line);
    }

    private void addTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24f);
        tv.setPadding(0, 0, 0, 8);
        stepsContainer.addView(tv);
    }

    private void addSubtitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xAAFFFFFF);
        tv.setTextSize(13f);
        tv.setPadding(0, 0, 0, 40);
        stepsContainer.addView(tv);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // onResume handles the refresh after returning
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        buildUI();
    }

    private void done() {
        startActivity(new Intent(this, CarActivity.class));
        finish();
    }
}
