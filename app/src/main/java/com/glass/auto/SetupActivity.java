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
            + "On the next screen: find \"Glass Auto\" and enable it.",
            "Open Settings",
            listenerOk ? null : v -> PermissionHelper.requestNotificationListener(this)
        );

        // --- Step 3: Location ---
        boolean locationOk = PermissionHelper.hasLocation(this);
        addStep(
            locationOk,
            "3  Location (GPS speed)",
            "Used only to read GPS speed. No location data is stored or transmitted.",
            "Allow",
            locationOk ? null : v -> PermissionHelper.requestLocation(this, RC_LOCATION)
        );

        // --- Step 4: Freeform (informational only) ---
        boolean freeformOk = PermissionHelper.isFreeformEnabled(this);
        addFreeformStep(freeformOk);

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
        tv.setText((detected ? "✓  " : "⚠  ") + "4  Freeform window mode");
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
