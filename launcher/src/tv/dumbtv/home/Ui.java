package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Inter (SIL OFL 1.1, rsms/inter 4.1) for the Cinema 2 look; loaded on first use. */
    private static Typeface interDisplay, interMedium;

    private Ui() {}

    /** Inter Display Light, for large titles; Roboto Light if it cannot be loaded. */
    static Typeface display(Context c) {
        if (interDisplay == null) interDisplay = font(c, "inter_display_light", LIGHT);
        return interDisplay;
    }

    /** Inter Medium, for labels; Roboto Medium if it cannot be loaded. */
    static Typeface label(Context c) {
        if (interMedium == null) interMedium = font(c, "inter_medium", MEDIUM);
        return interMedium;
    }

    /**
     * A font from res/raw. Android 7.1 has no API to make a Typeface from a raw resource, so it is
     * copied to the cache once and read from there. Looked up by name, not R, so this class still
     * compiles in the host tests, which have no generated R.
     */
    private static Typeface font(Context c, String name, Typeface fallback) {
        int id = c.getResources().getIdentifier(name, "raw", c.getPackageName());
        if (id == 0) return fallback;
        File f = new File(c.getCacheDir(), name + ".ttf");
        try {
            if (!f.isFile() || f.length() == 0) {
                try (InputStream in = c.getResources().openRawResource(id); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            return Typeface.createFromFile(f);
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        bg.addState(new int[0], round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ink(accent), 0xD9FFFFFF}));
    }
}
