package com.monolocosaga;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-game quick menu (Back), laid out as G-Zero 64's. Left: pages grouped by section; right: the
 * page's rows; bottom: the focused row's explanation, Continue (green, left) and Exit (red, right),
 * which are touch only. The game holds still while it is open (GameBridge.nativeSetMenuOpen).
 */
final class QuickMenuDialog {
    /** A row: one label, n values, cycled with left/right or the arrows. */
    private interface Choice {
        String[] values();
        int get();
        void set(int value);
    }

    private interface Stepper {
        void step(int delta);
    }

    private static final int PAGE_GAME = 0, PAGE_SCREEN = 1, PAGE_FILTERS = 2, PAGE_SOUND = 3,
        PAGE_TOUCH = 4, PAGE_PAD = 5;
    private static final int[][] SECTIONS = {
        {R.string.quick_section_game, PAGE_GAME},
        {R.string.quick_section_picture, PAGE_SCREEN, PAGE_FILTERS},
        {R.string.quick_section_sound, PAGE_SOUND},
        {R.string.quick_section_controls, PAGE_TOUCH, PAGE_PAD},
    };
    private static final int[] PAGE_NAMES = {
        R.string.quick_page_game, R.string.quick_page_screen, R.string.quick_page_filters,
        R.string.quick_page_sound, R.string.quick_page_touch, R.string.quick_page_pad,
    };
    private static final int SLOTS = 3;

    // One thread for the app's lifetime: the menu opens and closes many times.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final GameActivity activity;
    private final Dialog dialog;
    private final View parking;          // holds the focus after a touch (see the constructor)
    private View lastFocused;            // where the controller was before
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout nav;
    private final LinearLayout rows;
    private final ScrollView rowScroll;
    private final TextView pageTitle;
    private final TextView footer;
    private int currentPage;
    private boolean busy;

    private QuickMenuDialog(GameActivity activity) {
        this.activity = activity;
        dialog = new Dialog(activity) {
            // A touch puts Android in touch mode and clears the focus; the next D-pad press would
            // then jump to the first item. Park the focus on an invisible view meanwhile, so that
            // press only brings it back to where the controller left it (see the key listener).
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                boolean handled = super.dispatchTouchEvent(event);
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    ui.post(new Runnable() {
                        @Override public void run() { if (dialog.isShowing()) parking.requestFocus(); }
                    });
                }
                return handled;
            }
        };
        parking = new View(activity);
        parking.setFocusable(true);
        parking.setFocusableInTouchMode(true);
        nav = new LinearLayout(activity);
        rows = new LinearLayout(activity);
        rowScroll = new ScrollView(activity);
        pageTitle = new TextView(activity);
        footer = new TextView(activity);
    }

    static Dialog show(GameActivity activity) {
        QuickMenuDialog menu = new QuickMenuDialog(activity);
        try {
            GameBridge.nativeSetMenuOpen(true);
        } catch (UnsatisfiedLinkError error) {
            Log.w("Monoloco", "Game library not loaded", error);
        }
        menu.build();
        menu.showPage(PAGE_GAME, 0);
        return menu.dialog;
    }

    /** Called by GameActivity's dismiss listener: the game runs again. */
    static void closed() {
        try {
            GameBridge.nativeSetMenuOpen(false);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────────

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    private void build() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF50C1A14);
        card.setStroke(dp(1), 0xFF3F6A58);
        card.setCornerRadius(dp(4));
        panel.setBackground(card);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.quick_title) + "  ·  "
            + activity.getString(Games.TITLES[activity.game() - 1]));
        title.setTextSize(18);
        title.setTextColor(0xFFF3F6F7);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        panel.addView(title);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(6), 0, dp(6));
        nav.setOrientation(LinearLayout.VERTICAL);
        ScrollView navScroll = new ScrollView(activity);
        navScroll.addView(nav);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int navWidth = Math.max(dp(150), Math.min(dp(230), metrics.widthPixels * 28 / 100));
        body.addView(navScroll, new LinearLayout.LayoutParams(navWidth, LinearLayout.LayoutParams.MATCH_PARENT));
        View divider = new View(activity);
        divider.setBackgroundColor(0xFF3F6A58);
        body.addView(divider, new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), 0, 0, 0);
        pageTitle.setTextSize(16);
        pageTitle.setTextColor(0xFFFFC452);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pageTitle.setPadding(dp(8), 0, 0, dp(4));
        right.addView(pageTitle);
        rows.setOrientation(LinearLayout.VERTICAL);
        rowScroll.addView(rows);
        right.addView(rowScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        panel.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        footer.setTextSize(12);
        footer.setTextColor(0xFFA9C8B8);
        footer.setMaxLines(3);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        footer.setMinLines(2);
        footer.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.addView(footer);

        // Continue bottom left (green), Exit bottom right (red).
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        Button resume = small(R.string.quick_resume, 0xFF2E7D32, 0xFF43A047);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });
        resume.setFocusable(false); // Continue and Exit are touch only: Back closes the menu
        Button exit = small(R.string.quick_exit, 0xFFB71C1C, 0xFFE53935);
        exit.setFocusable(false);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmExit(); }
        });
        TextView hint = new TextView(activity);
        hint.setText(R.string.quick_pages_hint);
        hint.setTextSize(11);
        hint.setTextColor(0xFF7FA393);
        hint.setGravity(Gravity.CENTER);
        buttons.addView(resume, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        buttons.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(exit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        panel.addView(buttons);

        panel.addView(parking, new LinearLayout.LayoutParams(1, 1));
        panel.getViewTreeObserver().addOnGlobalFocusChangeListener(new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                if (newFocus != null && newFocus != parking) lastFocused = newFocus;
            }
        });
        for (int[] section : SECTIONS) {
            addNavHeader(activity.getString(section[0]).toUpperCase(Locale.ROOT));
            for (int i = 1; i < section.length; i++) addNavItem(section[i]);
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                // First key after a touch: back to where the controller was, nothing else.
                if (dialog.getCurrentFocus() == parking && keyCode != KeyEvent.KEYCODE_BACK) {
                    if (lastFocused != null && lastFocused.isAttachedToWindow() && lastFocused.isShown()) {
                        lastFocused.requestFocus();
                    } else {
                        focusNav();
                    }
                    return true;
                }
                // L1 / L2: to the page list (left). R1 / R2: to the options of the page (right).
                if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                    focusNav();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                    focusRows();
                    return true;
                }
                // Up / down stay on their side: the next or previous item of the same list.
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    moveWithin(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    return true;
                }
                // Left / right on a button among the options does not wander off to the page list.
                View focused = dialog.getCurrentFocus();
                if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        && focused instanceof Button && focused.getParent() == rows) {
                    return true;
                }
                return false;
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        dialog.show();
        if (window != null) {
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            }
            // Sized from the screen, so it fits 16:9 and 4:3 alike.
            window.setLayout(metrics.widthPixels * 96 / 100, metrics.heightPixels * 94 / 100);
        }
    }

    private void addNavHeader(String text) {
        TextView header = new TextView(activity);
        header.setText(text);
        header.setTextSize(11);
        header.setLetterSpacing(0.12f);
        header.setTextColor(0xFFFFC452);
        header.setPadding(dp(8), dp(10), dp(4), dp(2));
        nav.addView(header);
    }

    private void addNavItem(final int page) {
        TextView item = new TextView(activity);
        item.setText(PAGE_NAMES[page]);
        item.setTextSize(14);
        item.setTextColor(Color.WHITE);
        item.setPadding(dp(14), dp(8), dp(6), dp(8));
        item.setFocusable(true);
        item.setClickable(true);
        item.setTag(page);
        item.setBackground(highlight());
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { if (!busy) showPage(page, 0); }
        });
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus && !busy && page != currentPage) showPage(page, -1);
            }
        });
        // Only the shoulder buttons move between the page list and the options (dialog key listener).
        item.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int keyCode, KeyEvent event) {
                return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
            }
        });
        nav.addView(item);
    }

    /** Moves the focus to the next (+1) or previous (-1) item of the list it is in. */
    private void moveWithin(int direction) {
        View focused = dialog.getCurrentFocus();
        LinearLayout list = null;
        View item = focused;
        while (item != null && item.getParent() instanceof View) {
            if (item.getParent() == rows) { list = rows; break; }
            if (item.getParent() == nav) { list = nav; break; }
            item = (View) item.getParent();
        }
        if (list == null) {
            focusNav();
            return;
        }
        int at = list.indexOfChild(item);
        for (int i = at + direction; i >= 0 && i < list.getChildCount(); i += direction) {
            View next = list.getChildAt(i);
            if (next.isFocusable() && next.getVisibility() == View.VISIBLE) {
                next.requestFocus();
                if (list == rows) {
                    // Keep it on screen, with a little of the neighbours.
                    int top = next.getTop() - dp(24), bottom = next.getBottom() + dp(24);
                    if (top < rowScroll.getScrollY()) rowScroll.smoothScrollTo(0, Math.max(0, top));
                    else if (bottom > rowScroll.getScrollY() + rowScroll.getHeight())
                        rowScroll.smoothScrollTo(0, bottom - rowScroll.getHeight());
                }
                return;
            }
        }
    }

    /** The page list, on the current page. */
    private void focusNav() {
        for (int i = 0; i < nav.getChildCount(); i++) {
            View child = nav.getChildAt(i);
            if (child.getTag() instanceof Integer && (Integer) child.getTag() == currentPage) {
                child.requestFocus();
                return;
            }
        }
    }

    /** The page's options, on the first one. */
    private void focusRows() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.isFocusable()) {
                child.requestFocus();
                rowScroll.smoothScrollTo(0, Math.max(0, child.getTop() - dp(8)));
                return;
            }
        }
    }

    private StateListDrawable highlight() {
        StateListDrawable highlight = new StateListDrawable();
        highlight.addState(new int[] {android.R.attr.state_selected}, new ColorDrawable(0x55FFC452));
        highlight.addState(new int[] {android.R.attr.state_focused}, new ColorDrawable(0x40FFC452));
        highlight.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(0x30FFC452));
        highlight.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return highlight;
    }

    /** focusRow: -1 keeps the focus where it is (navigating the page list). */
    private void showPage(int page, int focusRow) {
        currentPage = page;
        for (int i = 0; i < nav.getChildCount(); i++) {
            View child = nav.getChildAt(i);
            if (child.getTag() instanceof Integer) child.setSelected((Integer) child.getTag() == page);
        }
        pageTitle.setText(PAGE_NAMES[page]);
        rows.removeAllViews();
        footer.setText("");
        switch (page) {
            case PAGE_GAME: addGameRows(); break;
            case PAGE_SCREEN: addScreenRows(); break;
            case PAGE_FILTERS: addFilterRows(); break;
            case PAGE_SOUND: addSoundRows(); break;
            case PAGE_TOUCH: addTouchRows(); break;
            case PAGE_PAD: addPadRows(); break;
            default: break;
        }
        if (focusRow >= 0) {
            int focusable = 0;
            for (int i = 0; i < rows.getChildCount(); i++) {
                View child = rows.getChildAt(i);
                if (!child.isFocusable()) continue;
                if (focusable++ == focusRow || i == rows.getChildCount() - 1) {
                    child.requestFocus();
                    break;
                }
            }
        }
    }

    private String[] array(int res) {
        return activity.getResources().getStringArray(res);
    }

    // ── Game: quick save and load ───────────────────────────────────────────────────────────────

    private int slot() {
        return Math.max(1, Math.min(SLOTS, LauncherSettings.get(activity, LauncherSettings.SLOT + activity.game(), 1)));
    }

    private File stateFile(int slot) {
        return new File(Games.states(activity, activity.game()), "slot" + slot + ".state");
    }

    private String slotDescription(int slot) {
        File file = stateFile(slot);
        if (!file.isFile()) return activity.getString(R.string.quick_slot_empty);
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(file.lastModified()));
    }

    private void addGameRows() {
        addChoice(R.string.quick_slot, 0, new Choice() {
            @Override public String[] values() {
                String[] values = new String[SLOTS];
                for (int i = 0; i < SLOTS; i++) values[i] = (i + 1) + " · " + slotDescription(i + 1);
                return values;
            }
            @Override public int get() { return slot() - 1; }
            @Override public void set(int value) {
                LauncherSettings.set(activity, LauncherSettings.SLOT + activity.game(), value + 1);
            }
        });
        addButton(R.string.quick_save_state, R.string.quick_save_state_tip, new Runnable() {
            @Override public void run() { saveState(); }
        });
        addButton(R.string.quick_load_state, R.string.quick_load_state_tip, new Runnable() {
            @Override public void run() {
                if (!stateFile(slot()).isFile()) {
                    footer.setText(R.string.quick_slot_empty_message);
                    return;
                }
                loadState();
            }
        });
        addButton(R.string.quick_restart, R.string.quick_restart_tip, new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(activity)
                        .setMessage(R.string.quick_restart_confirm)
                        .setPositiveButton(R.string.quick_restart, (confirmation, which) -> {
                            dialog.dismiss();
                            activity.backToLauncher(true);
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });
    }

    private void saveState() {
        if (busy) return;
        busy = true;
        final File file = stateFile(slot());
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory()) folder.mkdirs();
        footer.setText(R.string.quick_working);
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    ok = GameBridge.nativeSaveState(file.getAbsolutePath());
                } catch (UnsatisfiedLinkError error) {
                    ok = false;
                }
                final boolean saved = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (!dialog.isShowing()) return;
                        showPage(PAGE_GAME, 1);
                        footer.setText(saved ? activity.getString(R.string.quick_saved, slot())
                                             : activity.getString(R.string.quick_save_failed));
                    }
                });
            }
        });
    }

    private void loadState() {
        if (busy) return;
        busy = true;
        final File file = stateFile(slot());
        footer.setText(R.string.quick_working);
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    ok = GameBridge.nativeLoadState(file.getAbsolutePath());
                } catch (UnsatisfiedLinkError error) {
                    ok = false;
                }
                final boolean loaded = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (!dialog.isShowing()) return;
                        // Back to the game straight after a load.
                        if (loaded) dialog.dismiss();
                        else footer.setText(R.string.quick_load_failed);
                    }
                });
            }
        });
    }

    // ── Picture ─────────────────────────────────────────────────────────────────────────────────

    private void applyVideo() {
        LauncherSettings.apply(activity);
    }

    private Choice setting(final String key, final int fallback, final int valuesRes) {
        return new Choice() {
            @Override public String[] values() { return array(valuesRes); }
            @Override public int get() { return LauncherSettings.get(activity, key, fallback); }
            @Override public void set(int value) {
                LauncherSettings.set(activity, key, value);
                applyVideo();
            }
        };
    }

    private void addScreenRows() {
        addChoice(R.string.quick_format, R.string.quick_format_tip,
            setting(LauncherSettings.FORMAT, 0, R.array.quick_formats));
        addChoice(R.string.quick_scale, R.string.quick_scale_tip,
            setting(LauncherSettings.SCALE, LauncherSettings.DEFAULT_SCALE, R.array.quick_scales));
        addChoice(R.string.quick_pixels, R.string.quick_pixels_tip,
            setting(LauncherSettings.SQUARE_PIXELS, LauncherSettings.DEFAULT_SQUARE_PIXELS, R.array.quick_pixel_shapes));
        addChoice(R.string.quick_fps, 0, new Choice() {
            @Override public String[] values() { return array(R.array.quick_off_on); }
            @Override public int get() { return activity.fpsOverlayVisible() ? 1 : 0; }
            @Override public void set(int value) { activity.setFpsOverlayVisible(value == 1); }
        });
        addChoice(R.string.quick_fast_cores, R.string.quick_fast_cores_tip,
            setting(LauncherSettings.FAST_CORES, 1, R.array.quick_off_on));
    }

    private void addFilterRows() {
        final Choice filter = setting(LauncherSettings.FILTER, LauncherSettings.DEFAULT_FILTER, R.array.quick_filters);
        final View[] dependent = new View[3];
        final Runnable update = new Runnable() {
            @Override public void run() {
                // The HD level only serves HD, cleaning FSR and Anime4K, effects the plain filters.
                setRowEnabled(dependent[0], filter.get() == LauncherSettings.FILTER_HD);
                setRowEnabled(dependent[1], filter.get() >= LauncherSettings.FILTER_FSR);
                setRowEnabled(dependent[2], filter.get() <= LauncherSettings.LAST_PLAIN_FILTER);
            }
        };
        addChoice(R.string.quick_filter, R.string.quick_filter_tip, new Choice() {
            @Override public String[] values() { return filter.values(); }
            @Override public int get() { return filter.get(); }
            @Override public void set(int value) {
                filter.set(value);
                update.run();
            }
        });
        dependent[0] = addChoice(R.string.quick_hd_level, R.string.quick_hd_level_tip,
            setting(LauncherSettings.HD_LEVEL, 1, R.array.quick_hd_levels));
        dependent[1] = addChoice(R.string.quick_restore, R.string.quick_restore_tip,
            setting(LauncherSettings.RESTORE, 0, R.array.quick_off_on));
        dependent[2] = addChoice(R.string.quick_effect, R.string.quick_effect_tip,
            setting(LauncherSettings.EFFECT, 0, R.array.quick_effects));
        update.run();
    }

    /** Greys a row out; a disabled row takes no focus, touch or controller input. */
    private static void setRowEnabled(View row, boolean enabled) {
        row.setAlpha(enabled ? 1f : 0.35f);
        row.setEnabled(enabled);
        row.setFocusable(enabled);
        if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(enabled);
        }
    }

    // ── Sound ───────────────────────────────────────────────────────────────────────────────────

    private void addSoundRows() {
        addChoice(R.string.quick_volume, 0, new Choice() {
            @Override public String[] values() {
                String[] values = new String[11];
                for (int i = 0; i <= 10; i++) values[i] = (i * 10) + " %";
                return values;
            }
            @Override public int get() { return LauncherSettings.get(activity, LauncherSettings.VOLUME, 100) / 10; }
            @Override public void set(int value) {
                LauncherSettings.set(activity, LauncherSettings.VOLUME, value * 10);
                applyVideo();
            }
        });
    }

    // ── Controls ────────────────────────────────────────────────────────────────────────────────

    private void addTouchRows() {
        final TouchControlsView controls = activity.touchControls();
        if (controls == null) return;
        addChoice(R.string.quick_touch_mode, R.string.quick_touch_mode_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_touch_modes); }
            @Override public int get() { return controls.getMode(); }
            @Override public void set(int value) { controls.setMode(value); }
        });
        addChoice(R.string.quick_touch_opacity, 0, new Choice() {
            @Override public String[] values() { return array(R.array.quick_touch_opacities); }
            @Override public int get() { return controls.getOpacity(); }
            @Override public void set(int value) { controls.setOpacity(value); }
        });
        addButton(R.string.quick_touch_editor, R.string.quick_touch_editor_tip, new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                activity.openTouchEditor();
            }
        });
    }

    private void addPadRows() {
        for (String line : array(R.array.quick_pad_lines)) {
            TextView text = new TextView(activity);
            text.setText(line);
            text.setTextSize(14);
            text.setTextColor(0xFFDCE8E2);
            text.setPadding(dp(8), dp(6), dp(8), dp(6));
            rows.addView(text);
        }
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────

    private void addButton(int label, final int tip, final Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnFocusChangeListener(footerOnFocus(tip != 0 ? activity.getString(tip) : ""));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        rows.addView(button);
    }

    private View addChoice(int label, final int tip, final Choice choice) {
        final TextView value = new TextView(activity);
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                String[] values = choice.values();
                value.setText(values[Math.max(0, Math.min(values.length - 1, choice.get()))]);
            }
        };
        refresh.run();
        return addValueRow(activity.getString(label), tip != 0 ? activity.getString(tip) : "", value, new Stepper() {
            @Override public void step(int delta) {
                int n = choice.values().length;
                choice.set(((choice.get() + delta) % n + n) % n);
                refresh.run();
            }
        });
    }

    private View.OnFocusChangeListener footerOnFocus(final String text) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) footer.setText(text);
            }
        };
    }

    private View addValueRow(String label, String describe, final TextView value, final Stepper stepper) {
        final LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(44));
        line.setPadding(dp(8), 0, dp(4), 0);
        line.setFocusable(true);
        line.setClickable(true);
        line.setBackground(highlight());
        line.setOnFocusChangeListener(footerOnFocus(describe));

        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        value.setTextColor(0xFFFFD98A);
        value.setTextSize(15);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        Button left = arrow("‹"), right = arrow("›");
        left.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { line.requestFocus(); stepper.step(-1); }
        });
        right.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { line.requestFocus(); stepper.step(1); }
        });
        line.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stepper.step(1); }
        });
        // Controller: up/down move between rows, left/right change the value.
        line.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    stepper.step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
                return true;
            }
        });
        line.addView(left, new LinearLayout.LayoutParams(dp(40), dp(40)));
        line.addView(value, new LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT));
        line.addView(right, new LinearLayout.LayoutParams(dp(40), dp(40)));
        rows.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private Button arrow(String symbol) {
        Button button = new Button(activity);
        button.setText(symbol);
        button.setTextSize(20);
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false); // the row keeps the controller focus
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextColor(0xFFF3F6F7);
        return button;
    }

    /** Compact coloured button; lighter with controller focus or when pressed. */
    private Button small(int res, int color, int focused) {
        Button button = new Button(activity);
        button.setText(res);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setMinWidth(dp(120));
        button.setMinimumWidth(dp(120));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, shape(focused, Color.WHITE));
        background.addState(new int[] {android.R.attr.state_pressed}, shape(focused, focused));
        background.addState(new int[0], shape(color, color));
        button.setBackground(background);
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(2), stroke);
        shape.setCornerRadius(dp(4));
        return shape;
    }

    private void confirmExit() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.quick_exit)
                .setMessage(R.string.quick_exit_confirm)
                .setPositiveButton(R.string.quick_exit, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface confirmation, int which) {
                        dialog.dismiss();
                        activity.backToLauncher(false);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
