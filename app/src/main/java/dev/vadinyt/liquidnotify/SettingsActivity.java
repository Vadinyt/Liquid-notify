package dev.vadinyt.liquidnotify;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native on-device controls. All values are numeric and clamped; no untrusted
 * input is passed to the root command. Settings.Global is shared with SystemUI.
 */
public final class SettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView status;
    private int opacity = 112;
    private int blurPx = 40;
    private int liquidStrength = 80;
    private int radius = 36;
    private int palette = 0;
    private boolean enabled = true;
    private volatile boolean loading = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF171C29);
        getWindow().setNavigationBarColor(0xFF171C29);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(0xFF171C29);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = label("LiquidNotify", 28, 0xFFFFFFFF);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        root.addView(label("One UI 9 • Liquid Glass notification surfaces", 13, 0xFFA7B5CA));
        spacer(20);

        Switch toggle = new Switch(this);
        toggle.setText("Enable LiquidNotify");
        toggle.setTextColor(Color.WHITE);
        toggle.setChecked(enabled);
        root.addView(toggle);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            enabled = checked;
            write("liquidnotify_enabled", checked ? 1 : 0);
        });

        spacer(24);
        root.addView(label("Glass opacity", 18, Color.WHITE));
        TextView alphaValue = label("", 13, 0xFFA7B5CA);
        root.addView(alphaValue);
        SeekBar alpha = new SeekBar(this);
        alpha.setMax(210);
        alpha.setProgress(opacity - 35);
        alphaValue.setText(opacity + " / 255 (lower = more transparent)");
        root.addView(alpha);
        alpha.setOnSeekBarChangeListener(seekListener(value -> {
            opacity = 35 + value;
            alphaValue.setText(opacity + " / 255 (lower = more transparent)");
        }, () -> write("liquidnotify_opacity", opacity)));

        spacer(20);
        root.addView(label("Backdrop blur (secondary)", 18, Color.WHITE));
        TextView blurValue = label(blurPx + " px", 13, 0xFFA7B5CA);
        root.addView(blurValue);
        SeekBar blurBar = new SeekBar(this);
        blurBar.setMax(80);
        blurBar.setProgress(blurPx);
        root.addView(blurBar);
        blurBar.setOnSeekBarChangeListener(seekListener(value -> {
            blurPx = value;
            blurValue.setText(blurPx + " px (0 = off)");
        }, () -> write("liquidnotify_blur_px", blurPx)));

        spacer(20);
        root.addView(label("Liquid reflections", 18, Color.WHITE));
        TextView liquidValue = label(liquidStrength + " / 100", 13, 0xFFA7B5CA);
        root.addView(liquidValue);
        SeekBar liquidBar = new SeekBar(this);
        liquidBar.setMax(100);
        liquidBar.setProgress(liquidStrength);
        root.addView(liquidBar);
        liquidBar.setOnSeekBarChangeListener(seekListener(value -> {
            liquidStrength = value;
            liquidValue.setText(liquidStrength + " / 100 (0 = no highlights)");
        }, () -> write("liquidnotify_liquid_strength", liquidStrength)));

        spacer(20);
        root.addView(label("Corner radius", 18, Color.WHITE));
        TextView radiusValue = label(radius + " dp", 13, 0xFFA7B5CA);
        root.addView(radiusValue);
        SeekBar corners = new SeekBar(this);
        corners.setMax(30);
        corners.setProgress(radius - 18);
        root.addView(corners);
        corners.setOnSeekBarChangeListener(seekListener(value -> {
            radius = 18 + value;
            radiusValue.setText(radius + " dp");
        }, () -> write("liquidnotify_radius_dp", radius)));

        spacer(20);
        root.addView(label("Color palette", 18, Color.WHITE));
        RadioGroup group = new RadioGroup(this);
        String[] names = {"Icy blue", "Graphite", "Violet"};
        for (int i = 0; i < names.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(100 + i);
            radio.setText(names[i]);
            radio.setTextColor(Color.WHITE);
            group.addView(radio);
        }
        group.check(100 + palette);
        root.addView(group);
        group.setOnCheckedChangeListener((g, id) -> {
            palette = Math.max(0, Math.min(2, id - 100));
            write("liquidnotify_palette", palette);
        });

        spacer(18);
        root.addView(label("Liquid Glass: reflections 80, opacity 85, blur 40.",
                13, 0xFFA7B5CA));
        spacer(12);
        status = label("Changes apply when notifications redraw.", 13, 0xFFA7B5CA);
        root.addView(status);
        spacer(12);
        Button frosted = new Button(this);
        frosted.setText("APPLY LIQUID GLASS");
        root.addView(frosted);
        frosted.setOnClickListener(v -> {
            if (loading) return;
            status.setText("Applying Liquid Glass...");
            worker.execute(() -> {
                boolean ok = hasRoot()
                        && runRoot("settings put global liquidnotify_opacity 85")
                        && runRoot("settings put global liquidnotify_blur_px 40")
                        && runRoot("settings put global liquidnotify_liquid_strength 80")
                        && runRoot("settings put global liquidnotify_radius_dp 44")
                        && readRoot("liquidnotify_opacity", -1) == 85
                        && readRoot("liquidnotify_blur_px", -1) == 40
                        && readRoot("liquidnotify_liquid_strength", -1) == 80
                        && readRoot("liquidnotify_radius_dp", -1) == 44;
                runOnUiThread(() -> {
                    if (ok) {
                        load(toggle, alpha, alphaValue, blurBar, blurValue,
                                liquidBar, liquidValue, corners, radiusValue, group);
                    } else {
                        status.setText("Could not apply preset. Check KernelSU permission.");
                    }
                });
            });
        });
        spacer(8);
        Button grant = new Button(this);
        grant.setText("CHECK ROOT ACCESS");
        root.addView(grant);
        grant.setOnClickListener(v -> worker.execute(() -> {
            boolean ok = hasRoot();
            runOnUiThread(() -> status.setText(ok
                    ? "Root access confirmed (uid 0). Try saving a setting."
                    : "Root unavailable or denied. Allow LiquidNotify in KernelSU."));
        }));
        spacer(8);
        Button refresh = new Button(this);
        refresh.setText("Reload saved values");
        root.addView(refresh);
        refresh.setOnClickListener(v -> load(toggle, alpha, alphaValue, blurBar, blurValue, liquidBar, liquidValue, corners, radiusValue, group));
        load(toggle, alpha, alphaValue, blurBar, blurValue, liquidBar, liquidValue, corners, radiusValue, group);
    }

    private interface ValueUpdate { void update(int value); }
    private SeekBar.OnSeekBarChangeListener seekListener(ValueUpdate update, Runnable commit) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) update.update(progress);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) { commit.run(); }
        };
    }

    private void write(String key, int value) {
        // Only allow known Settings.Global keys.
        if (!key.equals("liquidnotify_enabled")
                && !key.equals("liquidnotify_opacity")
                && !key.equals("liquidnotify_blur_px")
                && !key.equals("liquidnotify_liquid_strength")
                && !key.equals("liquidnotify_radius_dp")
                && !key.equals("liquidnotify_palette")) return;
        if (loading) return;
        worker.execute(() -> {
            boolean ok = hasRoot() && runRoot("settings put global " + key + " " + value)
                    && readRoot(key, Integer.MIN_VALUE) == value;
            runOnUiThread(() -> status.setText(ok
                    ? "Saved. Reopen the notification shade to preview."
                    : "Could not save. Check KernelSU permission for LiquidNotify."));
        });
    }

    private void load(Switch toggle, SeekBar alpha, TextView alphaValue,
                      SeekBar blurBar, TextView blurValue,
                      SeekBar liquidBar, TextView liquidValue,
                      SeekBar corners, TextView radiusValue, RadioGroup group) {
        loading = true;
        worker.execute(() -> {
            boolean rootGranted = hasRoot();
            int[] values = new int[] {
                readRoot("liquidnotify_enabled", 1),
                readRoot("liquidnotify_opacity", 85),
                readRoot("liquidnotify_blur_px", 40),
                readRoot("liquidnotify_liquid_strength", 80),
                readRoot("liquidnotify_radius_dp", 44),
                readRoot("liquidnotify_palette", 0)
            };
            runOnUiThread(() -> {
                enabled = values[0] == 1;
                opacity = Math.max(35, Math.min(245, values[1]));
                blurPx = Math.max(0, Math.min(80, values[2]));
                liquidStrength = Math.max(0, Math.min(100, values[3]));
                radius = Math.max(18, Math.min(48, values[4]));
                palette = Math.max(0, Math.min(2, values[5]));
                // Avoid issuing writes when initializing UI.
                toggle.setOnCheckedChangeListener(null);
                group.setOnCheckedChangeListener(null);
                toggle.setChecked(enabled);
                alpha.setProgress(opacity - 35);
                alphaValue.setText(opacity + " / 255 (lower = more transparent)");
                blurBar.setProgress(blurPx);
                blurValue.setText(blurPx + " px (0 = off)");
                liquidBar.setProgress(liquidStrength);
                liquidValue.setText(liquidStrength + " / 100 (0 = no highlights)");
                corners.setProgress(radius - 18);
                radiusValue.setText(radius + " dp");
                group.check(100 + palette);
                loading = false;
                status.setText(rootGranted ? "Settings loaded. Adjust a control to save." : "Root unavailable. Grant LiquidNotify access in KernelSU.");
                toggle.setOnCheckedChangeListener((button, checked) -> {
                    enabled = checked;
                    write("liquidnotify_enabled", checked ? 1 : 0);
                });
                group.setOnCheckedChangeListener((g, id) -> {
                    palette = Math.max(0, Math.min(2, id - 100));
                    write("liquidnotify_palette", palette);
                });
            });
        });
    }

    private int readRoot(String key, int fallback) {
        try {
            Process p = new ProcessBuilder("su", "-c", "settings get global " + key).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            if (p.waitFor() != 0 || line == null) return fallback;
            return Integer.parseInt(line.trim());
        } catch (Exception e) { return fallback; }
    }

    private boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String uid = reader.readLine();
            return p.waitFor() == 0 && "0".equals(uid == null ? "" : uid.trim());
        } catch (Exception e) { return false; }
    }

    private boolean runRoot(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command).start();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }
    private void spacer(int h) {
        View v = new View(this);
        root.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }
    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
