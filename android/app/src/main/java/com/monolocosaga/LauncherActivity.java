package com.monolocosaga;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The three games on the left, Play / Settings / Change folder on the right. The folder holds the
 * player's ROMs (and optionally cores built on a PC); they are copied into the app behind an
 * "Installing..." screen. Choosing a folder never starts a game; auto-start is optional and any
 * touch, key or controller button cancels it.
 */
public final class LauncherActivity extends Activity {
    /** Set by the quick menu's restart: start the same game again as soon as it checks out. */
    static final String EXTRA_RESTART = "restart";
    private static final int PICK_FOLDER = 1;
    private static final long RESTART_DELAY_MS = 500;
    private static final long AUTO_START_DELAY_MS = 1500;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStart = new Runnable() {
        @Override public void run() { startGame(); }
    };
    private final View[] rows = new View[Games.COUNT + 1];
    private final TextView[] states = new TextView[Games.COUNT + 1];
    /** "‹ Version: … ›" under a game with ROM hacks; left/right or a tap change it. */
    private final TextView[] versions = new TextView[Games.COUNT + 1];
    private boolean rescanned;
    private TextView status;
    private Button play;
    private Button chooseButton;
    private int game;
    private boolean restartRequested;
    private boolean autoStartAllowed;
    private String importMessage = "";
    private InstallScreen installScreen;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restartRequested = savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_RESTART, false);
        autoStartAllowed = savedInstanceState == null;
        game = Games.selected(this);
        setContentView(R.layout.activity_launcher);
        View panel = findViewById(R.id.launcher_panel);
        int width = Math.min(getResources().getConfiguration().screenWidthDp, 980);
        panel.getLayoutParams().width = (int) (width * getResources().getDisplayMetrics().density);
        TextView credits = findViewById(R.id.launcher_credits);
        credits.setText(Html.fromHtml(getString(R.string.launcher_credits,
            BuildConfig.VERSION_NAME, BuildConfig.BUILD_COMMIT_FULL), Html.FROM_HTML_MODE_LEGACY));
        credits.setMovementMethod(LinkMovementMethod.getInstance());
        status = findViewById(R.id.launcher_status);
        play = findViewById(R.id.play_game);
        chooseButton = findViewById(R.id.choose_folder);
        buildGameList();
        chooseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                handler.removeCallbacks(autoStart);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_FOLDER);
            }
        });
        findViewById(R.id.open_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                handler.removeCallbacks(autoStart);
                startActivity(new Intent(LauncherActivity.this, SettingsActivity.class));
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startGame(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // One folder holds the three games: a ROM added to it later comes in on its own.
        Uri tree = savedFolder();
        if (!rescanned && tree != null && missingRom()) {
            rescanned = true;
            importFolder(tree, false);
            return;
        }
        showGame(game, autoStartAllowed);
        autoStartAllowed = false;
    }

    private Uri savedFolder() {
        String tree = LauncherSettings.prefs(this).getString("tree", null);
        return tree != null ? Uri.parse(tree) : null;
    }

    /** A game this APK can run (it has the core) still waits for its ROM. */
    private boolean missingRom() {
        for (int g = 1; g <= Games.COUNT; g++) {
            if (Games.core(this, g) != null && !Games.hasRom(this, g)) return true;
        }
        return false;
    }

    /** Under each game: ready, waiting for its ROM, or not in this version of the app. */
    private void updateStates() {
        for (int g = 1; g <= Games.COUNT; g++) {
            boolean core = Games.core(this, g) != null, rom = Games.hasRom(this, g);
            states[g].setText(!core ? R.string.launcher_state_no_core
                : rom ? R.string.launcher_state_ready : R.string.launcher_state_no_rom);
            states[g].setTextColor(!core ? 0xFF8E9A94 : rom ? 0xFF6FE3A4 : 0xFFF3C28C);
            boolean hacks = rom && !Games.hacks(this, g).isEmpty();
            versions[g].setVisibility(hacks ? View.VISIBLE : View.GONE);
            if (hacks) versions[g].setText(getString(R.string.launcher_version, Games.versionName(this, g)));
        }
    }

    /** The next or previous version of a game: the original, then its hacks by name. */
    private void stepVersion(int which, int delta) {
        List<Games.Hack> hacks = Games.hacks(this, which);
        if (hacks.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        ids.add("");
        for (Games.Hack hack : hacks) ids.add(hack.id);
        int index = Math.max(0, ids.indexOf(Games.version(this, which)));
        index = ((index + delta) % ids.size() + ids.size()) % ids.size();
        Games.setVersion(this, which, ids.get(index));
        importMessage = "";
        if (which != game) Games.select(this, which);
        showGame(which, false);
    }

    private void buildGameList() {
        LinearLayout list = findViewById(R.id.game_list);
        float density = getResources().getDisplayMetrics().density;
        for (int g = 1; g <= Games.COUNT; g++) {
            final int which = g;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.game_row);
            row.setPadding((int) (10 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            row.setFocusable(true);
            row.setClickable(true);
            ImageView logo = new ImageView(this);
            logo.setImageResource(Games.LOGOS[g - 1]);
            logo.setAdjustViewBounds(true);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(logo, new LinearLayout.LayoutParams((int) (88 * density), (int) (50 * density)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding((int) (12 * density), 0, 0, 0);
            TextView title = new TextView(this);
            title.setText(Games.TITLES[g - 1]);
            title.setTextColor(0xFFF3F6F7);
            title.setTextSize(16);
            title.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC));
            TextView subtitle = new TextView(this);
            subtitle.setText(Games.SUBTITLES[g - 1]);
            subtitle.setTextColor(0xFFA9C8B8);
            subtitle.setTextSize(12);
            TextView state = new TextView(this);
            state.setTextSize(12);
            state.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            final TextView version = new TextView(this);
            version.setTextSize(12);
            version.setTextColor(0xFFFFD98A);
            version.setVisibility(View.GONE);
            version.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { stepVersion(which, 1); }
            });
            text.addView(title);
            text.addView(subtitle);
            text.addView(state);
            text.addView(version);
            states[g] = state;
            versions[g] = version;
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (which == game && Games.ready(LauncherActivity.this, which)) startGame();
                    else selectGame(which);
                }
            });
            // Controller: left/right on a game change its version.
            row.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View view, int keyCode, android.view.KeyEvent event) {
                    if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                    if (Games.hacks(LauncherActivity.this, which).isEmpty()) return false;
                    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        stepVersion(which, keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    return true;
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean hasFocus) {
                    if (hasFocus && which != game) selectGame(which);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = (int) (6 * density);
            list.addView(row, params);
            rows[g] = row;
        }
    }

    private void selectGame(int which) {
        handler.removeCallbacks(autoStart);
        importMessage = "";
        Games.select(this, which);
        showGame(which, false);
    }

    /** Heading, explanation and buttons for the selected game. */
    private void showGame(int which, boolean allowAutoStart) {
        handler.removeCallbacks(autoStart);
        game = which;
        for (int g = 1; g <= Games.COUNT; g++) rows[g].setSelected(g == which);
        updateStates();
        TextView heading = findViewById(R.id.launcher_heading);
        TextView description = findViewById(R.id.launcher_description);
        heading.setText(Games.TITLES[which - 1]);
        boolean hasCore = Games.core(this, which) != null;
        boolean hasRom = Games.hasRom(this, which);
        String crash = takeLastError(which);
        if (!hasCore) {
            description.setText(R.string.launcher_no_core);
        } else if (!hasRom) {
            description.setText(R.string.launcher_no_rom);
        } else if (!Games.version(this, which).isEmpty()) {
            description.setText(getString(R.string.launcher_hack_description, Games.versionName(this, which)));
        } else {
            description.setText(R.string.launcher_ready_description);
        }
        boolean ready = Games.ready(this, which);
        play.setVisibility(ready ? View.VISIBLE : View.GONE);
        boolean anyRom = false;
        for (int g = 1; g <= Games.COUNT; g++) anyRom |= Games.hasRom(this, g);
        chooseButton.setText(anyRom ? R.string.launcher_change_folder : R.string.launcher_choose);
        showFolderName();
        String message = importMessage;
        if (crash != null) message = getString(R.string.launcher_crashed, crash);
        if (ready) {
            if (restartRequested) {
                restartRequested = false;
                message = getString(R.string.launcher_restarting);
                handler.postDelayed(autoStart, RESTART_DELAY_MS);
            } else if (allowAutoStart && crash == null && LauncherSettings.autoStart(this)) {
                message = getString(R.string.launcher_auto_starting);
                handler.postDelayed(autoStart, AUTO_START_DELAY_MS);
            } else if (message.isEmpty()) {
                message = getString(R.string.launcher_ready_status);
            }
        }
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        View focus = ready ? play : chooseButton;
        if (getCurrentFocus() == null || getCurrentFocus() == play || getCurrentFocus() == chooseButton) {
            focus.requestFocus();
        }
    }

    /** Under the folder button: which folder the three games come from. */
    private void showFolderName() {
        TextView name = findViewById(R.id.folder_name);
        Uri tree = savedFolder();
        String label = null;
        if (tree != null) {
            try {
                String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
                label = id.substring(id.indexOf(':') + 1);
                if (label.isEmpty()) label = id;
            } catch (IllegalArgumentException ignored) {
            }
        }
        name.setText(label != null ? getString(R.string.launcher_folder_name, label) : "");
        name.setVisibility(label != null ? View.VISIBLE : View.GONE);
    }

    /** The reason the last session of this game stopped, once. */
    private String takeLastError(int which) {
        File file = Games.lastError(this, which);
        if (!file.isFile()) return null;
        String text = null;
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 300)];
            int count = input.read(data);
            text = count > 0 ? new String(data, 0, count, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException ignored) {
        }
        file.delete();
        return text;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        // Kept, so the launcher can look in the same folder again later.
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            LauncherSettings.prefs(this).edit().putString("tree", tree.toString()).apply();
        } catch (SecurityException exception) {
            Log.w("Monoloco", "Folder permission not kept", exception);
        }
        rescanned = true;
        importFolder(tree, true);
    }

    /** chosen: the player just picked the folder (a later look in the saved one reports only news). */
    private void importFolder(final Uri tree, final boolean chosen) {
        installScreen = InstallScreen.show(this, (android.view.ViewGroup) findViewById(R.id.launcher_root));
        final boolean[] had = new boolean[Games.COUNT + 1];
        for (int g = 1; g <= Games.COUNT; g++) had[g] = Games.hasRom(this, g);
        worker.execute(new Runnable() {
            @Override public void run() {
                String message;
                int found = 0;
                List<GameFiles.PendingIps> pendingIps = new ArrayList<>();
                try {
                    GameFiles.Scan scan = GameFiles.importFrom(LauncherActivity.this, tree, new GameFiles.Progress() {
                        @Override public void update(final float fraction) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() { if (installScreen != null) installScreen.setProgress(fraction); }
                            });
                        }
                    });
                    if (!chosen) {
                        // A later look in the saved folder: only the games it brought in.
                        for (int g = 1; g <= Games.COUNT; g++) {
                            scan.roms[g] &= !had[g];
                            scan.cores[g] = false;
                        }
                        scan.unknownRoms = scan.badCores = scan.badPatches = 0;
                        scan.hacks.clear();
                        scan.revisions.clear();
                    }
                    pendingIps = scan.pendingIps;
                    message = chosen || scan.any() ? describe(scan) : "";
                    for (int g = 1; g <= Games.COUNT; g++) if (scan.roms[g] || scan.cores[g]) { found = g; break; }
                } catch (IOException | RuntimeException exception) {
                    Log.w("Monoloco", "Folder import failed", exception);
                    message = chosen ? getString(R.string.launcher_unreadable) : "";
                    // The saved folder is gone or no longer readable: ask for it again next time.
                    if (!chosen) LauncherSettings.prefs(LauncherActivity.this).edit().remove("tree").apply();
                }
                final String result = message;
                final int first = found;
                final List<GameFiles.PendingIps> pending = pendingIps;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (installScreen != null) installScreen.dismiss();
                        installScreen = null;
                        importMessage = result;
                        // Never start on its own after choosing a folder; Play does.
                        int show = !Games.hasRom(LauncherActivity.this, game) && first > 0 ? first : game;
                        Games.select(LauncherActivity.this, show);
                        if (!pending.isEmpty()) {
                            autoStartAllowed = false;
                            showGame(show, false);
                            askIps(pending, 0);
                            return;
                        }
                        showGame(show, !chosen && autoStartAllowed);
                        autoStartAllowed = false;
                    }
                });
            }
        });
    }

    private String describe(GameFiles.Scan scan) {
        List<String> lines = new ArrayList<>();
        for (int g = 1; g <= Games.COUNT; g++) {
            if (scan.roms[g]) lines.add(getString(R.string.launcher_found_rom, getString(Games.TITLES[g - 1])));
            if (scan.cores[g]) lines.add(getString(R.string.launcher_found_core, getString(Games.TITLES[g - 1])));
        }
        for (String revision : scan.revisions) {
            String[] parts = revision.split("\t", 2);
            lines.add(getString(R.string.launcher_found_revision, getString(Games.TITLES[Integer.parseInt(parts[0]) - 1]), parts[1]));
        }
        for (String hack : scan.hacks) lines.add(describeHack(hack));
        if (scan.badPatches > 0) lines.add(getString(R.string.launcher_bad_patches, scan.badPatches));
        if (scan.unknownRoms > 0) lines.add(getString(R.string.launcher_unknown_roms, scan.unknownRoms));
        if (scan.badCores > 0) lines.add(getString(R.string.launcher_bad_cores, scan.badCores));
        if (lines.isEmpty()) return getString(R.string.launcher_nothing_found);
        return String.join("\n", lines);
    }

    /** IPS patches do not say which ROM they are for: one question each, with the likeliest game marked. */
    private void askIps(final List<GameFiles.PendingIps> pending, final int index) {
        if (isFinishing() || isDestroyed()) return;
        if (index >= pending.size()) {
            showGame(game, false);
            return;
        }
        final GameFiles.PendingIps patch = pending.get(index);
        String[] choices = new String[Games.COUNT + 1];
        for (int g = 1; g <= Games.COUNT; g++) choices[g - 1] = getString(Games.TITLES[g - 1]);
        choices[Games.COUNT] = getString(R.string.launcher_ips_none);
        final int[] chosen = {patch.suggestedGame - 1};
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.launcher_ips_title, patch.name))
            .setSingleChoiceItems(choices, chosen[0], (dialog, which) -> chosen[0] = which)
            .setPositiveButton(R.string.launcher_ips_ok, (dialog, which) -> {
                final int target = chosen[0] < Games.COUNT ? chosen[0] + 1 : 0;
                worker.execute(new Runnable() {
                    @Override public void run() {
                        String note;
                        try {
                            note = GameFiles.assignIps(LauncherActivity.this, patch, target);
                        } catch (IOException | RuntimeException exception) {
                            Log.w("Monoloco", "IPS patch " + patch.name, exception);
                            note = null;
                        }
                        final String installed = note;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (target > 0) {
                                    String line = installed != null
                                        ? describeHack(target + "\t" + patch.name + "\t" + installed)
                                        : getString(R.string.launcher_ips_failed, patch.name);
                                    importMessage = importMessage.isEmpty() ? line : importMessage + "\n" + line;
                                }
                                askIps(pending, index + 1);
                            }
                        });
                    }
                });
            })
            .setCancelable(false)
            .show();
    }

    /** One line for a hack installed: "game\tname\tnote" (see GameFiles.Scan.hacks). */
    private String describeHack(String hack) {
        String[] parts = hack.split("\t", 3);
        String title = getString(Games.TITLES[Integer.parseInt(parts[0]) - 1]);
        if (parts.length < 3 || parts[2].isEmpty()) return getString(R.string.launcher_found_hack, title, parts[1]);
        String[] moved = parts[2].split(":", 2);
        return getString(R.string.launcher_found_hack_moved, title, parts[1], moved[0], Integer.parseInt(moved[1]));
    }

    private void startGame() {
        handler.removeCallbacks(autoStart);
        if (isFinishing() || isDestroyed() || !Games.ready(this, game)) return;
        Intent intent = new Intent(this, GameActivity.class).putExtra(GameActivity.EXTRA_GAME, game);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    /** A touch, key or controller button during the auto-start countdown only cancels it. */
    private boolean cancelAutoStart() {
        if (!handler.hasCallbacks(autoStart)) return false;
        handler.removeCallbacks(autoStart);
        if (status != null) status.setText(R.string.launcher_ready_status);
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (installScreen != null) return true;
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && cancelAutoStart()) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (installScreen != null) return true;
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && cancelAutoStart()) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        // Sticks and triggers: only a clear push, not stick drift.
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
                && (Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_X)) > 0.5f
                    || Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_Y)) > 0.5f
                    || Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)) > 0.5f
                    || Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)) > 0.5f)
                && cancelAutoStart()) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        // Leaving the launcher (settings, another app) cancels a pending auto-start.
        cancelAutoStart();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoStart);
        worker.shutdown();
        super.onDestroy();
    }
}
