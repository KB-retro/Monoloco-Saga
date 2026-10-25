package com.monolocosaga;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The three games: where their ROM, saves and core live. Game n (1..3) keeps everything in
 * {@code <app folder>/game<n>/}; the core is the APK's own or one imported into the private files.
 * Each ROM hack of a game is a version with its own folder, {@code game<n>/hacks/<id>/}: its
 * patched ROM, name, battery save and quick saves, so versions never mix their saves.
 */
final class Games {
    static final int COUNT = 3;
    static final long ROM_SIZE = 4L * 1024 * 1024;
    /** The headerless USA ROMs each upstream project was generated from. */
    static final String[] SHA256 = {
        "fa8cacf5bbfc39ee6bbaa557adf89133d60d42f6cf9e1db30d5a36a469f74d15",
        "35421a9af9dd011b40b91f792192af9f99c93201d8d394026bdfb42cbf2d8633",
        "2277a2d8dddb01fe5cb0ae9a0fa225d42b3a11adccaeafa18e3c339b3794a32b",
    };
    static final int[] TITLES = {R.string.game1_title, R.string.game2_title, R.string.game3_title};
    static final int[] SUBTITLES = {R.string.game1_subtitle, R.string.game2_subtitle, R.string.game3_subtitle};
    static final int[] LOGOS = {R.drawable.game1_logo, R.drawable.game2_logo, R.drawable.game3_logo};

    private Games() { }

    /** The folder the app treats as its data directory (Android/data/com.monolocosaga/files). */
    static File appDir(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    static File dir(Context context, int game) { return new File(appDir(context), "game" + game); }

    /** The verified retail ROM. */
    static File rom(Context context, int game) { return new File(dir(context, game), "rom.sfc"); }

    /** A version's folder: the game's own for the original (id ""), its hack's otherwise. */
    static File versionDir(Context context, int game, String id) {
        return id.isEmpty() ? dir(context, game) : new File(new File(dir(context, game), "hacks"), id);
    }

    /** The ROM the selected version plays. */
    static File playRom(Context context, int game) {
        return new File(versionDir(context, game, version(context, game)), "rom.sfc");
    }

    /** Where the selected version keeps its battery save (the core works there). */
    static File saveDir(Context context, int game) { return versionDir(context, game, version(context, game)); }

    static File states(Context context, int game) { return new File(saveDir(context, game), "states"); }

    static File lastError(Context context, int game) { return new File(saveDir(context, game), "last_error.txt"); }

    /** One installed ROM hack. */
    static final class Hack {
        final String id, name;

        Hack(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** The hacks installed for a game, by name. */
    static List<Hack> hacks(Context context, int game) {
        List<Hack> hacks = new ArrayList<>();
        File[] folders = new File(dir(context, game), "hacks").listFiles();
        if (folders != null) {
            for (File folder : folders) {
                if (!new File(folder, "rom.sfc").isFile()) continue;
                hacks.add(new Hack(folder.getName(), hackName(folder)));
            }
        }
        Collections.sort(hacks, new Comparator<Hack>() {
            @Override public int compare(Hack a, Hack b) { return a.name.compareToIgnoreCase(b.name); }
        });
        return hacks;
    }

    private static String hackName(File folder) {
        File file = new File(folder, "name.txt");
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 200)];
            int count = input.read(data);
            String name = count > 0 ? new String(data, 0, count, StandardCharsets.UTF_8).trim() : "";
            if (!name.isEmpty()) return name;
        } catch (IOException ignored) {
        }
        return folder.getName();
    }

    /** The selected version's id: "" for the original, or an installed hack's. */
    static String version(Context context, int game) {
        String id = LauncherSettings.prefs(context).getString("version" + game, "");
        if (id.isEmpty() || !new File(versionDir(context, game, id), "rom.sfc").isFile()) return "";
        return id;
    }

    static void setVersion(Context context, int game, String id) {
        LauncherSettings.prefs(context).edit().putString("version" + game, id).apply();
    }

    /** The selected version's name for the player. */
    static String versionName(Context context, int game) {
        String id = version(context, game);
        return id.isEmpty() ? context.getString(R.string.launcher_version_original)
            : hackName(versionDir(context, game, id));
    }

    static String coreName(int game) { return "libmonoloco_game" + game + ".so"; }

    /** Imported cores must be in private storage: shared storage cannot hold executable code. */
    static File importedCore(Context context, int game) {
        return new File(new File(context.getFilesDir(), "cores"), coreName(game));
    }

    /** The APK's own core when it has one, otherwise an imported one; null when neither. */
    static File core(Context context, int game) {
        File bundled = new File(context.getApplicationInfo().nativeLibraryDir, coreName(game));
        if (bundled.isFile()) return bundled;
        File imported = importedCore(context, game);
        return imported.isFile() ? imported : null;
    }

    static boolean hasRom(Context context, int game) { return rom(context, game).isFile(); }

    static boolean ready(Context context, int game) {
        return hasRom(context, game) && playRom(context, game).isFile() && core(context, game) != null;
    }

    static int selected(Context context) {
        int game = LauncherSettings.prefs(context).getInt("selected_game", 1);
        return game >= 1 && game <= COUNT ? game : 1;
    }

    static void select(Context context, int game) {
        LauncherSettings.prefs(context).edit().putInt("selected_game", game).apply();
    }
}
