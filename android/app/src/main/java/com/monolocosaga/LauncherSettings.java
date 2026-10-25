package com.monolocosaga;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferences shared by the launcher, the quick menu and every game. */
final class LauncherSettings {
    // Picture: format 0 automatic, 1 original 4:3, 2 16:10, 3 16:9.
    static final String FORMAT = "format", SCALE = "scale", SQUARE_PIXELS = "square_pixels",
        FILTER = "filter", EFFECT = "effect", VOLUME = "volume", FPS = "fps", SLOT = "slot",
        FAST_CORES = "fast_cores", HD_LEVEL = "hd_level", RESTORE = "restore";
    /** Sharp bilinear, full screen and square pixels unless the player picked otherwise. */
    static final int DEFAULT_FILTER = 2, DEFAULT_SCALE = 2, DEFAULT_SQUARE_PIXELS = 1;
    /** Size: fit, integer or full screen (native kScale order). */
    static final int SCALE_FIT = 0, SCALE_FULL_SCREEN = 2;
    /** Format: automatic, original 4:3, 8:7 (the 256x224 picture with square pixels), 16:10, 16:9. */
    static final int FORMAT_8_7 = 2;
    /** Effects only apply to the plain filters: nearest, linear and sharp bilinear. */
    static final int LAST_PLAIN_FILTER = 2, FILTER_HD = 3, FILTER_FSR = 5, EFFECT_CRT_HYLLIAN = 3;
    private static final String VERSION = "settings_version";

    private LauncherSettings() { }

    /**
     * One file for the three games. Each game runs in the :game process, so the
     * launcher process must reread it or its stale copy would overwrite changes.
     */
    @SuppressWarnings("deprecation")
    static SharedPreferences prefs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("game", Context.MODE_MULTI_PROCESS);
        if (prefs.getInt(VERSION, 1) < 4) migrate(prefs);
        return prefs;
    }

    /**
     * Version 2 dropped the CRT Lottes filter (5) and made CRT Hyllian (6) an effect. Version 3
     * dropped Anime4K GAN (8) and swapped Anime4K M (6, now Full) and 3D (7, now Light). Version 4
     * put the 8:7 format (2) after the original 4:3 one.
     */
    private static void migrate(SharedPreferences prefs) {
        int version = prefs.getInt(VERSION, 1);
        int filter = prefs.getInt(FILTER, DEFAULT_FILTER);
        SharedPreferences.Editor editor = prefs.edit().putInt(VERSION, 4);
        if (version < 2) {
            if (filter == 5 || filter == 6) filter = DEFAULT_FILTER;
            if (prefs.getInt(FILTER, DEFAULT_FILTER) == 6) editor.putInt(EFFECT, EFFECT_CRT_HYLLIAN);
            if (filter > 6) filter -= 2;
        }
        if (version < 3) {
            if (filter == 6) filter = 7;
            else if (filter == 7) filter = 6;
            else if (filter == 8) filter = 7;
            if (prefs.contains(FILTER)) editor.putInt(FILTER, filter);
        }
        int format = prefs.getInt(FORMAT, 0);
        if (format >= FORMAT_8_7) editor.putInt(FORMAT, format + 1);
        editor.apply();
    }

    static boolean autoStart(Context context) { return prefs(context).getBoolean("auto_start", false); }

    static void setAutoStart(Context context, boolean value) {
        prefs(context).edit().putBoolean("auto_start", value).apply();
    }

    static int get(Context context, String key, int fallback) { return prefs(context).getInt(key, fallback); }

    static void set(Context context, String key, int value) { prefs(context).edit().putInt(key, value).apply(); }

    /** The core's aspect (0 4:3, 1 16:10, 2 16:9) for the format setting on this screen. */
    static int aspect(android.app.Activity activity) {
        int format = get(activity, FORMAT, 0);
        if (format == 1 || format == FORMAT_8_7) return 0;
        if (format == 3) return 1;
        if (format == 4) return 2;
        android.graphics.Rect bounds = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        float ratio = (float) Math.max(bounds.width(), bounds.height())
            / Math.max(1, Math.min(bounds.width(), bounds.height()));
        if (ratio >= 1.7f) return 2;
        if (ratio >= 1.55f) return 1;
        return 0;
    }

    /** Sends every picture and sound setting to the game. */
    static void apply(android.app.Activity activity) {
        try {
            int aspect = aspect(activity);
            int scale = get(activity, SCALE, DEFAULT_SCALE);
            // Full screen stretches the widescreen pictures to the edges; a 4:3 one keeps its shape.
            if (scale == SCALE_FULL_SCREEN && aspect == 0) scale = SCALE_FIT;
            // 8:7 is the console's own 256x224 picture: square pixels whatever the setting.
            boolean square = get(activity, FORMAT, 0) == FORMAT_8_7
                || get(activity, SQUARE_PIXELS, DEFAULT_SQUARE_PIXELS) == 1;
            GameBridge.nativeSetVideo(get(activity, FILTER, DEFAULT_FILTER), get(activity, EFFECT, 0),
                scale, !square);
            GameBridge.nativeSetAspect(aspect);
            GameBridge.nativeSetVolume(get(activity, VOLUME, 100));
            GameBridge.nativeSetHdLevel(get(activity, HD_LEVEL, 1));
            GameBridge.nativeSetRestore(get(activity, RESTORE, 0) == 1);
            GameBridge.nativeSetFastCores(get(activity, FAST_CORES, 1) == 1);
        } catch (UnsatisfiedLinkError error) {
            android.util.Log.w("Monoloco", "Game library not loaded", error);
        }
    }
}
