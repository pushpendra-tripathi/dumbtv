package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.animation.ObjectAnimator;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Home extends Activity {
    /** How long Menu is held to open Options while the child lock is on. */
    static final long UNLOCK_MS = 3000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String APPSTORE = "com.amazon.venezia";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService colorIo = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed, unlockOpened;
    private String lastFocusedKind;
    private String lastAppId;
    private String lastInputId;
    private FrameLayout root;
    private ImageView wall;
    private View shade;
    private View tint;
    private LinearLayout head;
    private TextView clock, date;
    private Meter meter;
    private Row apps, inputs;
    private Map<String, Long> recent = new HashMap<>();
    private Tile moving;
    private String wallKey;
    private String backgroundMode;
    private ValueAnimator colorAnimation;
    private int displayedColor, targetColor;
    private boolean tintWallpaper;
    /** Cinema look: the focused app's art across the top, and its name. */
    private ImageView hero;
    private TextView heroTitle;
    private Drawable heroShown;
    private String heroId;
    private final Map<String, Bitmap> heroCache = new HashMap<>();
    private LinearLayout heroBox;
    private TextView heroSub;
    private TextView heroKicker;
    // ---- Cinema 2 ----
    private static final int NEAR_BLACK = 0xFF0B0B0E, RACE_RED = 0xFFFF5A5F;
    private static final long AMBIENT_MS = 2 * 60 * 1000;
    private LinearLayout whenBox, status;
    private View headSpacer, scrim;
    private ScrollView page;
    private TextView statusTemp, statusClock, optionsChip, settingsChip, optionsIcon, settingsIcon;
    private TextView wifiButton;
    private WifiDrawable statusWifiIcon;
    private int statusSkyKind = -1, heroBandH;
    private String lastStyle;
    /** Ambient mode: the controls are faded out and every app's new releases play full screen. */
    private boolean ambient, swallowKeyUp;
    /** Ambient mode's caption: the slide's service and title, so OK can open it. */
    private LinearLayout ambientCaption;
    private TextView ambientKicker, ambientTitle;
    private final Runnable goAmbient = this::enterAmbient;
    /** Live feeds: weather and the next race under the clock, new releases in the hero. */
    private Feeds feeds;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private boolean feedsRunning;
    private long feedsAt;
    private TextView info;
    private TextView wifiChip;
    private WifiDrawable wifiIcon;
    /** The new-release slides of the focused app, while the hero shows them. */
    private List<Feeds.Slide> heroSlides;
    private String slidesFor;
    private int slideIndex;
    private final Map<String, Bitmap> slideCache = new LinkedHashMap<>();
    private final Runnable nextSlide = () -> {
        slideIndex++;
        showSlide();
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final Grid grid;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            int edge = dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(20), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(bleed, dp(12), bleed, dp(8));
            scroll = new HorizontalScrollView(Home.this) {
                /** Keeps the focused tile centered, except near either end of the row. */
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                    if (getChildCount() == 0) return 0;
                    int range = Math.max(0, getChildAt(0).getWidth() - (getWidth() - getPaddingLeft() - getPaddingRight()));
                    int target = Math.max(0, Math.min(range, r.centerX() - getWidth() / 2));
                    return target - getScrollX();
                }
            };
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            grid = new Grid(Home.this);
            grid.setPadding(edge, dp(12), edge, dp(8));
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /** Whichever container currently holds the tiles, following Options › Layout. */
        ViewGroup tiles() {
            return "grid".equals(cfg.get(Config.LAYOUT)) ? grid : strip;
        }

        void setVisible(boolean on) {
            boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
            boolean quiet = cfg.cinema() && Catalog.APPS.equals(kind);
            title.setVisibility(on && !quiet ? View.VISIBLE : View.GONE);
            scroll.setVisibility(on && !gridMode ? View.VISIBLE : View.GONE);
            grid.setVisibility(on && gridMode ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        feeds = new Feeds(cfg.dir);
        Future<Map<String, Long>> times = recentFor(Catalog.APPS) == null ? null
                : io.submit(() -> Recent.times(this));
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        loadInputs();
    }

    /**
     * Waits for the app list and loads the art of the tiles that fit on screen, so the first
     * frame is complete. After a cold start that frame is what people see first: loading in the
     * background drew the Inputs row alone, focused HDMI 1 for ~0.3 s, then popped the apps and
     * their banners in.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        try {
            apps.all = catalog.get();
            if (times != null) recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all, recentFor(Catalog.APPS))) {
            if (fit-- <= 0) break;
            Catalog.art(getPackageManager(), it, "afterglow".equals(cfg.get(Config.TILE_STYLE)));
            it.artRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        refreshRecent();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        refreshFeeds(false);
        if (cfg.cinema()) hero(focusedItem());
        scheduleAmbient();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopColorAnimation();
        targetColor = 0;
        unregisterReceiver(time);
        ui.removeCallbacks(goAmbient);
        leaveAmbient(false);
        stopSlides();
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        colorIo.shutdownNow();
        net.shutdownNow();
        ui.removeCallbacks(nextSlide);
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        release();
    }

    /**
     * An app covers this screen. The Guard accessibility service keeps this process at
     * bound-service importance, and Android then never sends TRIM_MEMORY_UI_HIDDEN, so the
     * release runs from onStop too, which always comes.
     */
    @Override
    protected void onStop() {
        super.onStop();
        release();
    }

    /**
     * Drops every picture this screen holds (wallpaper about 8 MB, hero art, slides, circuit map)
     * and the GPU caches behind them (about 30 MB on a Fire TV Stick 3rd gen). On a 1 GB stick a
     * video app needs that memory: its video decoder driver has panicked the kernel when memory
     * ran out mid-playback. onResume draws everything again: background() reloads a cleared
     * wallpaper, hero() the art.
     */
    private void release() {
        heroCache.clear();
        slideCache.clear();
        hero.setImageDrawable(null);
        heroShown = null;
        heroId = null;
        wall.setImageDrawable(null);
        wallKey = null;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
        // The bitmaps' pixels are native memory, freed only when their Java objects are
        // collected; ask for that now rather than when the video app is already short.
        System.gc();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        leaveAmbient(true);
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (ambient && e.getAction() == KeyEvent.ACTION_DOWN) {
            swallowKeyUp = true;
            int code = e.getKeyCode();
            boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER || isPlayKey(code);
            Feeds.Slide shown = select ? currentSlide() : null;
            boolean opens = shown != null && findApp(shown.pkg) != null;
            leaveAmbient(!opens);
            // OK or Play on a slide opens that title as far as its app allows (see openSlide).
            if (opens) openSlide(shown);
            return true;
        }
        if (swallowKeyUp && e.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = false;
            return true;
        }
        scheduleAmbient();
        // Play on the home screen, while the hero shows a release slide: open that title.
        if (moving == null && e.getAction() == KeyEvent.ACTION_DOWN && isPlayKey(e.getKeyCode())
                && cfg.cinema() && heroSlides != null && currentSlide() != null) {
            swallowKeyUp = true;
            openSlide(currentSlide());
            return true;
        }
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // In a grid, up and down move the tile a whole line; a strip has no lines.
                    if (moving.getParent() instanceof Grid) {
                        int cols = ((Grid) moving.getParent()).columns();
                        shift(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -cols : cols);
                    }
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && locked()) {
            // Repeats keep arriving while the button is held; the first one past the hold opens it.
            if (e.getRepeatCount() == 0) {
                unlockOpened = false;
            } else if (!unlockOpened && e.getEventTime() - e.getDownTime() >= UNLOCK_MS) {
                unlockOpened = true;
                startActivity(new Intent(this, Options.class));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /**
     * The scroll position for a focused rect {@code r}: the earliest of the top, the apps row or
     * the inputs row that still shows the row holding {@code r} whole.
     */
    private int rowTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        List<int[]> rows = rowBlocks(content);
        int[] at = null;
        for (int[] b : rows) {
            if (r.centerY() >= b[0]) at = b;
        }
        if (at == null) return 0;
        int range = Math.max(0, content.getHeight() - page.getHeight());
        return Math.max(0, Math.min(range, stopFor(at, rows, page.getHeight())));
    }

    /**
     * Title top and strip bottom of each visible row, in {@code content}'s coordinates. In grid
     * mode every line of tiles is its own block (the first one starting at the row title), so
     * a grid taller than the screen still scrolls a whole line at a time.
     */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : new Row[] {apps, inputs}) {
            if (row.scroll.getVisibility() == View.VISIBLE) {
                rows.add(new int[] {top(row, row.scroll, content), topIn(row.scroll, content) + row.scroll.getHeight()});
            } else if (row.grid.getVisibility() == View.VISIBLE) {
                int gridTop = topIn(row.grid, content), lastTop = Integer.MIN_VALUE;
                for (int i = 0; i < row.grid.getChildCount(); i++) {
                    View t = row.grid.getChildAt(i);
                    if (t.getTop() == lastTop) continue;
                    int start = lastTop == Integer.MIN_VALUE ? top(row, row.grid, content) : gridTop + t.getTop() - dp(12);
                    lastTop = t.getTop();
                    rows.add(new int[] {start, gridTop + t.getBottom() + dp(8)});
                }
            }
        }
        return rows;
    }

    /** Where a row starts: its title, or its tiles when the title is hidden. */
    private static int top(Row row, View tiles, View content) {
        return topIn(row.title.getVisibility() == View.VISIBLE ? row.title : tiles, content);
    }

    /** The earliest of the top or a row title that shows the whole of {@code at}. */
    private static int stopFor(int[] at, List<int[]> rows, int height) {
        if (height >= at[1]) return 0;
        for (int[] b : rows) {
            if (b[0] <= at[0] && b[0] + height >= at[1]) return b[0];
        }
        return at[0];
    }

    /**
     * When the page is taller than the screen, pads its bottom just enough that every row's stop
     * is reachable. Otherwise the last stop is clamped and the clock above it shows half cut.
     */
    private void fitBottom(ScrollView page) {
        View content = page.getChildAt(0);
        if (content == null || page.getHeight() == 0) return;
        int base = dp(32), height = page.getHeight();
        int natural = content.getHeight() - content.getPaddingBottom() + base;
        int want = base;
        if (natural > height) {
            int last = 0;
            List<int[]> rows = rowBlocks(content);
            for (int[] b : rows) last = Math.max(last, stopFor(b, rows, height));
            want = base + Math.max(0, last + height - natural);
        }
        if (content.getPaddingBottom() != want) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), want);
        }
    }

    /** {@code v}'s top edge in {@code content}'s coordinates. */
    private static int topIn(View v, View content) {
        int y = 0;
        while (v != null && v != content) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    private View build() {
        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        tint = new View(this);
        root.addView(tint, new FrameLayout.LayoutParams(MATCH, MATCH));
        hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.FIT_XY);
        hero.setVisibility(View.GONE);
        heroBandH = Math.round(getResources().getDisplayMetrics().heightPixels * Hero.HEIGHT);
        root.addView(hero, new FrameLayout.LayoutParams(MATCH, heroBandH, Gravity.TOP));
        // Cinema 2: the full-bleed art darkens at the top for the status corner and to near
        // black at the bottom, where the tiles sit.
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
                0x99000000, 0x00000000, 0x00000000, 0xE60B0B0E, NEAR_BLACK}));
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        ambientCaption = new LinearLayout(this);
        ambientCaption.setOrientation(LinearLayout.VERTICAL);
        ambientCaption.setPadding(dp(56), 0, dp(56), dp(48));
        ambientCaption.setAlpha(0f);
        ambientCaption.setVisibility(View.GONE);
        ambientKicker = Ui.text(this, 12, 0xCCFFFFFF, Ui.MEDIUM);
        ambientKicker.setLetterSpacing(0.18f);
        ambientKicker.setShadowLayer(dp(6), 0, 0, 0x99000000);
        ambientKicker.setPadding(dp(2), 0, 0, dp(8));
        ambientTitle = Ui.text(this, 26, Ui.TEXT, Ui.LIGHT);
        ambientTitle.setShadowLayer(dp(10), 0, 0, 0xB3000000);
        ambientTitle.setSingleLine();
        ambientTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ambientCaption.addView(ambientKicker);
        ambientCaption.addView(ambientTitle);
        FrameLayout.LayoutParams cap = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM | Gravity.START);
        root.addView(ambientCaption, cap);

        ScrollView scroll = new ScrollView(this) {
            /** Scrolls in whole rows: the focused row shows complete, and a row starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                // Cinema 2 fits on one screen; if it does not, scroll only as far as needed, never
                // a row to the top, which would slide the status and hero text away.
                if (cfg.cinema2()) return super.computeScrollDeltaToGetChildRectOnScreen(r);
                return rowTarget(this, r) - getScrollY();
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                fitBottom(this);
            }

            /**
             * Up and down only move the focus; scrolling follows it. Left to ScrollView, a press
             * with nothing focusable that way scrolls half a screen and leaves rows cut.
             */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        page = scroll;
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_DOWN && chips.contains(focused)) {
                    View remembered = rememberedTile(lastFocusedKind);
                    if (remembered != null) return remembered;
                    View below = nearest(apps.tiles(), focused);
                    if (below != null) return below;
                }
                if (focused instanceof Tile
                        && focused.getParent() instanceof ViewGroup) {
                    Tile tile = (Tile) focused;
                    ViewGroup parent = (ViewGroup) tile.getParent();
                    int idx = parent.indexOfChild(tile);
                    int count = parent.getChildCount();
                    boolean grid = parent instanceof Grid;
                    int cols = grid ? ((Grid) parent).columns() : 1;

                    // Left and right stop at the edge of a row instead of jumping away
                    // from it: no tile of another row takes the focus.
                    if (direction == View.FOCUS_LEFT) {
                        boolean edge = grid ? idx % cols == 0 : idx == 0;
                        if (edge) return focused;
                    }
                    if (direction == View.FOCUS_RIGHT) {
                        boolean edge = grid
                                ? (idx + 1) % cols == 0 || idx == count - 1
                                : idx == count - 1;
                        if (edge) return focused;
                    }

                    // Down goes to the tile below; only the last line can be shorter, and a
                    // tile with nothing under it takes the nearest tile of that line.
                    // Down from the last line of the apps row lands on the input that was
                    // focused last, not on whichever one happens to be closest.
                    if (direction == View.FOCUS_DOWN
                            && Catalog.APPS.equals(tile.kind)) {
                        int target = grid ? belowInGrid(parent, tile) : -1;
                        if (target < 0) {
                            View remembered = rememberedTile(Catalog.INPUTS);
                            if (remembered != null) return remembered;
                            View below = nearest(inputs.tiles(), focused);
                            if (below != null) return below;
                        } else {
                            return parent.getChildAt(target);
                        }
                    }
                    // Up in the apps grid goes to the tile straight above, and from the top line
                    // to the nearest button. Left to FocusFinder, up from row 2 could land on a
                    // far tile of row 1, and up from row 1 sideways.
                    if (direction == View.FOCUS_UP && grid && Catalog.APPS.equals(tile.kind)) {
                        if (idx - cols >= 0) return parent.getChildAt(idx - cols);
                        View chip = nearestChip(focused);
                        if (chip != null) return chip;
                    }
                    // Up from the first line of the inputs row goes back to the app that was
                    // focused last.
                    if (direction == View.FOCUS_UP
                            && Catalog.INPUTS.equals(tile.kind)) {
                        boolean hasAbove = grid && aboveInGrid(parent, tile);
                        if (!hasAbove) {
                            View remembered = rememberedTile(Catalog.APPS);
                            if (remembered != null) return remembered;
                            View above = nearest(apps.tiles(), focused);
                            if (above != null) return above;
                        }
                    }
                }
                View next = super.focusSearch(focused, direction);
                if (next == null && direction == View.FOCUS_UP && !chips.isEmpty()) {
                    int[] at = new int[2];
                    focused.getLocationOnScreen(at);
                    int x = at[0] + focused.getWidth() / 2;
                    TextView best = null;
                    int bestGap = Integer.MAX_VALUE;
                    for (TextView c : chips) {
                        if (c.getVisibility() != View.VISIBLE) continue;
                        c.getLocationOnScreen(at);
                        int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
                        if (gap < bestGap) {
                            bestGap = gap;
                            best = c;
                        }
                    }
                    return best;
                }
                return next;
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(40), 0, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        head = new LinearLayout(this);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(56), 0, dp(56), dp(12));
        LinearLayout when = new LinearLayout(this);
        whenBox = when;
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 52, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(8), 0, 0);
        when.addView(clock);
        when.addView(date);
        info = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        info.setPadding(dp(2), dp(8), 0, 0);
        info.setVisibility(View.GONE);
        when.addView(info);
        meter = new Meter(this);
        meter.setVisibility(View.GONE);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(WRAP, WRAP);
        under.topMargin = dp(12);
        under.leftMargin = dp(2);
        when.addView(meter, under);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        headSpacer = new View(this);
        headSpacer.setVisibility(View.GONE);
        head.addView(headSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(buildStatus());
        wifiChip = chip("Wi-Fi", v -> openWifi());
        wifiIcon = new WifiDrawable(dp(16), dp(13));
        wifiIcon.setBounds(0, 0, dp(16), dp(13));
        wifiChip.setCompoundDrawables(wifiIcon, null, null, null);
        wifiChip.setCompoundDrawablePadding(dp(8));
        head.addView(wifiChip);
        optionsChip = chip("Options", v -> startActivity(new Intent(this, Options.class)));
        settingsChip = chip("Settings", v -> openSettings());
        head.addView(optionsChip);
        head.addView(settingsChip);
        wifiButton = iconChip(statusWifiIcon, "Wi-Fi", v -> openWifi());
        head.addView(wifiButton);
        optionsIcon = iconChip(Glyph.SLIDERS, "Options", v -> startActivity(new Intent(this, Options.class)));
        settingsIcon = iconChip(Glyph.GEAR, "Settings", v -> openSettings());
        head.addView(optionsIcon);
        head.addView(settingsIcon);
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));
        heroTitle = Ui.text(this, 38, Ui.TEXT, Ui.LIGHT);
        heroTitle.setGravity(Gravity.BOTTOM | Gravity.START);
        heroTitle.setSingleLine();
        heroSub = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        heroSub.setSingleLine();
        heroSub.setPadding(dp(2), dp(10), 0, 0);
        heroSub.setVisibility(View.GONE);
        heroBox = new LinearLayout(this);
        heroBox.setOrientation(LinearLayout.VERTICAL);
        heroBox.setGravity(Gravity.BOTTOM | Gravity.START);
        heroBox.setPadding(dp(56), 0, dp(56), dp(4));
        heroBox.setVisibility(View.GONE);
        heroKicker = Ui.text(this, 13, 0xFFFF5A5F, Ui.MEDIUM);
        heroKicker.setLetterSpacing(0.18f);
        heroKicker.setPadding(dp(3), 0, 0, dp(10));
        heroKicker.setVisibility(View.GONE);
        heroBox.addView(heroKicker);
        heroBox.addView(heroTitle);
        heroBox.addView(heroSub);
        column.addView(heroBox, new LinearLayout.LayoutParams(MATCH, dp(150)));

        apps = new Row(Catalog.APPS, "Apps", column);
        inputs = new Row(Catalog.INPUTS, "Inputs", column);
        return root;
    }

    /**
     * The index of the tile below {@code tile}, or -1 when it is in the last line. Only the
     * last line of a grid can be shorter than the others: a tile with no tile directly under
     * it takes the last tile of the line below, the nearest one in that line.
     */
    private static int belowInGrid(ViewGroup parent, Tile tile) {
        int cols = ((Grid) parent).columns();
        int index = parent.indexOfChild(tile), count = parent.getChildCount();
        if (index / cols >= (count - 1) / cols) return -1;
        return Math.min(index + cols, count - 1);
    }

    /** True when the grid has another line above {@code tile}. */
    private static boolean aboveInGrid(ViewGroup parent, Tile tile) {
        int cols = ((Grid) parent).columns();
        return parent.indexOfChild(tile) - cols >= 0;
    }

    /**
     * The child of {@code strip} horizontally closest to {@code from}, restricted to its top
     * visual row so a wrapped grid does not match a tile several rows further down.
     */
    private static View nearest(ViewGroup strip, View from) {
        int n = strip.getChildCount();
        if (n == 0) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        int[] ys = new int[n];
        int topY = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            strip.getChildAt(i).getLocationOnScreen(at);
            ys[i] = at[1];
            topY = Math.min(topY, at[1]);
        }
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (ys[i] != topY) continue;
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private boolean locked() {
        return "on".equals(cfg.get(Config.LOCK));
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(9), dp(18), dp(9));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start();
            if (has) {
                focusBackground(null);
                dimRows(null);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) {
            Ui.focusStyle(c, accent, 0x14FFFFFF, dp(18));
            c.setVisibility(locked() ? View.GONE : View.VISIBLE);
        }
        wifiChip.setVisibility(!locked() && on(Config.WIFI) ? View.VISIBLE : View.GONE);
        wifiIcon.setColors(wifiChip.getTextColors());
        boolean cinema = cfg.cinema();
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, cinema ? 24 : 52);
        date.setVisibility(cinema ? View.GONE : View.VISIBLE);
        heroBox.setVisibility(cinema ? View.VISIBLE : View.GONE);
        String style = cfg.get(Config.STYLE);
        if (!style.equals(lastStyle)) {
            // Hero art is drawn for one look's band shape; a switch draws it again.
            lastStyle = style;
            heroCache.clear();
            slideCache.clear();
            hero.setImageDrawable(null);
            heroShown = null;
            heroId = null;
        }
        applyCinema2(cfg.cinema2());
        if (!cinema || !on(Config.RELEASES)) stopSlides();
        hero.setVisibility(cinema ? View.VISIBLE : View.GONE);
        if (!cinema) {
            hero.setImageDrawable(null);
            heroShown = null;
            heroId = null;
            heroCache.clear();
        }
        meter.setVisibility("on".equals(cfg.get(Config.MEMORY)) ? View.VISIBLE : View.GONE);
        meter.setAccent(accent);
        render(apps);
        render(inputs);
        background();
        tick();
        refreshFeeds(true);
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                            fresh.backgroundColor = old.backgroundColor;
                        }
                    }
                }
                apps.all = list;
                render(apps);
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    /** The last-used times to sort {@code kind} by, or null when it keeps the user's order. */
    private Map<String, Long> recentFor(String kind) {
        return Catalog.APPS.equals(kind) && "recent".equals(cfg.get(Config.ORDER)) ? recent : null;
    }

    /** With App order › Recently used, re-sorts the apps row on the way back home. */
    private void refreshRecent() {
        if (recentFor(Catalog.APPS) == null) return;
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed() || recentFor(Catalog.APPS) == null) return;
                List<Item> before = Catalog.visible(cfg, Catalog.APPS, apps.all, recent);
                recent = times;
                if (!before.equals(Catalog.visible(cfg, Catalog.APPS, apps.all, recent))) render(apps);
            });
        });
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
        ViewGroup target = gridMode ? row.grid : row.strip;
        row.strip.removeAllViews();
        row.grid.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all, recentFor(row.kind))
                : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Catalog.APPS.equals(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    Tile tile = (Tile) v;
                    lastFocusedKind = tile.kind;
                    if (Catalog.APPS.equals(tile.kind)) {
                        lastAppId = tile.item.id;
                    } else {
                        lastInputId = tile.item.id;
                    }
                    focusBackground(tile.item);
                    hero(tile.item);
                    dimRows(tile);
                }
            });
            t.setOnLongClickListener(v -> {
                if (!locked()) menu((Tile) v);
                return true;
            });
            if (gridMode) {
                target.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = dp(18);
                target.addView(t, lp);
            }
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && (!it.artRequested
                    || "afterglow".equals(cfg.get(Config.TILE_STYLE)) && it.icon == null)) loadArt(it);
        }
        row.setVisible(!shown.isEmpty());
        adjustAppRowPosition();
        if (shown.isEmpty()) {
            // Nothing is left to come back to: forget the tile that used to be here.
            if (Catalog.APPS.equals(row.kind)) lastAppId = null;
            else lastInputId = null;
        }
        if (restore >= 0) {
            target.getChildAt(restore).requestFocus();
        } else if (keep != null && target.getChildCount() > 0) {
            target.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private boolean isInputsHidden() {
        if (inputs == null || !"on".equals(cfg.get(Config.INPUTS))) return true;
        return inputs.title.getVisibility() != View.VISIBLE;
    }

    private int appRowShift() {
        int titleH = apps.title.getHeight();
        if (titleH <= 0) {
            apps.title.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            titleH = apps.title.getMeasuredHeight();
        }
        if (titleH <= 0) titleH = dp(38);
        int scrollH = cardSize()[1] + dp(46);
        int desired = Math.round((titleH + scrollH) * 0.70f);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int headH = head != null && head.getHeight() > 0 ? head.getHeight() : 0;
        if (headH <= 0 && head != null) {
            head.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            headH = head.getMeasuredHeight();
        }
        if (headH <= 0) headH = dp(96);
        int maxShift = Math.max(0, screenH - dp(40) - headH - titleH - scrollH - dp(32));
        return Math.min(desired, maxShift);
    }

    private void adjustAppRowPosition() {
        if (apps == null || apps.title == null) return;
        ViewGroup.LayoutParams params = apps.title.getLayoutParams();
        if (!(params instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
        int targetMargin = (!cfg.cinema() && !"grid".equals(cfg.get(Config.LAYOUT)) && isInputsHidden())
                ? appRowShift() : 0;
        if (lp.topMargin != targetMargin) {
            lp.topMargin = targetMargin;
            apps.title.setLayoutParams(lp);
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        boolean cornerIcon = "afterglow".equals(cfg.get(Config.TILE_STYLE));
        io.execute(() -> {
            Catalog.art(getPackageManager(), it, cornerIcon);
            ui.post(() -> {
                ViewGroup tiles = apps.tiles();
                for (int i = 0; i < tiles.getChildCount(); i++) tiles.getChildAt(i).invalidate();
                if (focusedItem() == it) {
                    focusBackground(it);
                    hero(it);
                }
            });
        });
    }

    private Item focusedItem() {
        View focused = getCurrentFocus();
        return focused instanceof Tile ? ((Tile) focused).item : null;
    }

    /** Cinema look: shows {@code it}'s name and its art, blurred, across the top. */
    private void hero(Item it) {
        if (!cfg.cinema() || it == null || ambient) return;
        // 1. New releases on this app, as a slideshow.
        List<Feeds.Slide> mine = on(Config.RELEASES) ? feeds.slidesFor(it.id) : null;
        if (mine != null && !mine.isEmpty()) {
            if (!it.id.equals(slidesFor) || heroSlides != mine) {
                slidesFor = it.id;
                heroSlides = mine;
                slideIndex = 0;
                showSlide();
            }
            return;
        }
        stopSlides();
        // 2. Race week: the weekend's sessions over the app's art. 3. Otherwise the app's name.
        Feeds.Race race = feeds.race;
        if (race != null && Info.showsF1(it.id)) {
            heroText(race.name, sessions(race));
            if (cfg.cinema2()) kicker(Info.raceKicker(race.round, race.place, System.currentTimeMillis(), race.at), RACE_RED);
            else kicker(Info.raceLine(race.round, race.place));
            if (race.trackId != null) {
                trackHero(race);
                return;
            }
        } else {
            // Cinema 2 leaves an app without content to its tile's own name: no title twice.
            heroText(cfg.cinema2() ? null : Catalog.label(cfg, it), null);
            kicker(null);
        }
        if (it.id.equals(heroId)) return;
        Bitmap cached = heroCache.get(it.id);
        if (cached != null) {
            showHero(it.id, cached);
            return;
        }
        Drawable art = it.banner != null ? it.banner : it.icon;
        if (art == null) {
            // No art yet: clear the band, but leave heroId unset so the art shows when it loads.
            showHero(null, null);
            return;
        }
        float aspect = getResources().getDisplayMetrics().widthPixels
                / (getResources().getDisplayMetrics().heightPixels * Hero.HEIGHT);
        colorIo.execute(() -> {
            Bitmap b = Hero.from(art, aspect);
            ui.post(() -> {
                if (isDestroyed()) return;
                if (b != null) heroCache.put(it.id, b);
                if (focusedItem() == it) showHero(b == null ? null : it.id, b);
            });
        });
    }

    private void heroText(CharSequence title, String sub) {
        String t = title == null ? "" : title.toString();
        if (!t.contentEquals(heroTitle.getText())) {
            if (cfg.cinema2()) fitTitle(t);
            heroTitle.setText(t);
            heroBox.setAlpha(0f);
            heroBox.animate().alpha(1f).setDuration(220).start();
        }
        heroSub.setText(sub == null ? "" : sub);
        heroSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
    }

    private void kicker(String text) {
        kicker(text, RACE_RED);
    }

    private void kicker(String text, int color) {
        heroKicker.setTextColor(color);
        heroKicker.setText(text == null ? "" : text);
        heroKicker.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The race card's art: the next circuit's track outline, drawn once and kept. */
    private void trackHero(Feeds.Race race) {
        String key = "track:" + race.trackId;
        if (key.equals(heroId)) return;
        Bitmap cached = heroCache.get(key);
        if (cached != null) {
            showHero(key, cached);
            return;
        }
        float aspect = heroAspect();
        colorIo.execute(() -> {
            Bitmap b = cfg.cinema2() ? Hero.track(race.trackLon, race.trackLat, aspect, 0.13f, 0.47f)
                    : Hero.track(race.trackLon, race.trackLat, aspect);
            ui.post(() -> {
                if (isDestroyed()) return;
                if (b != null) heroCache.put(key, b);
                // Show it only if the race card is still what the hero is showing.
                Item f = focusedItem();
                Feeds.Race r = feeds.race;
                if (f == null || r == null || !race.trackId.equals(r.trackId) || heroSlides != null) return;
                if (!Info.showsF1(f.id)) return;
                showHero(b == null ? null : key, b);
            });
        });
    }

    /** The current slide of {@link #heroSlides}; the next one follows in 8 seconds. */
    private void showSlide() {
        ui.removeCallbacks(nextSlide);
        List<Feeds.Slide> list = heroSlides;
        if (list == null || list.isEmpty()) return;
        int index = slideIndex % list.size();
        Feeds.Slide s = list.get(index);
        if (cfg.cinema2()) {
            kicker(("New on " + s.service + "  ·  " + (s.series ? "Series" : "Film")).toUpperCase(Locale.ROOT), Ui.MUTED);
            heroText(s.title, null);
        } else {
            kicker(null);
            heroText(s.title, "New on " + s.service + (s.series ? "  ·  Series" : "  ·  Film"));
        }
        boolean full = ambient;
        if (ambient) {
            ambientKicker.setText(("New on " + s.service + "  ·  " + (s.series ? "Series" : "Film")).toUpperCase(Locale.ROOT)
                    + "   ·   PLAY TO OPEN");
            ambientTitle.setText(s.title);
            ambientCaption.setAlpha(0f);
            ambientCaption.animate().alpha(1f).setStartDelay(300).setDuration(500).start();
        }
        String key = (full ? "slide:full:" : "slide:") + s.image.getPath();
        Bitmap cached = slideCache.get(key);
        if (cached != null) {
            showHero(key, cached);
        } else {
            float aspect = heroAspect();
            colorIo.execute(() -> {
                Bitmap b = Hero.backdrop(s.image, aspect, !full);
                ui.post(() -> {
                    if (b == null || isDestroyed()) return;
                    slideCache.put(key, b);
                    // Six backdrops are about 12 MB; older ones are decoded again when needed.
                    Iterator<String> oldest = slideCache.keySet().iterator();
                    while (slideCache.size() > 6 && oldest.hasNext()) {
                        oldest.next();
                        oldest.remove();
                    }
                    if (heroSlides == list && slideIndex % list.size() == index) showHero(key, b);
                });
            });
        }
        if (list.size() > 1) ui.postDelayed(nextSlide, 8000);
    }

    private void stopSlides() {
        ui.removeCallbacks(nextSlide);
        if (slidesFor == null) return;
        slidesFor = null;
        heroSlides = null;
        heroId = null;
    }

    private float heroAspect() {
        if (cfg.cinema2()) {
            return getResources().getDisplayMetrics().widthPixels / (float) getResources().getDisplayMetrics().heightPixels;
        }
        return getResources().getDisplayMetrics().widthPixels
                / (getResources().getDisplayMetrics().heightPixels * Hero.HEIGHT);
    }

    /** The next three sessions of the weekend in this TV's time: "Quali Sat 11:30 PM  ·  Race Sun 10:30 PM". */
    private String sessions(Feeds.Race race) {
        long now = System.currentTimeMillis();
        if (now >= race.at) return "Race on now";
        boolean h24 = DateFormat.is24HourFormat(this) || "24h".equals(cfg.get(Config.CLOCK));
        if ("12h".equals(cfg.get(Config.CLOCK))) h24 = false;
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Feeds.Session s : race.sessions) {
            if (s.at + Info.HOUR < now || shown == 3) continue;
            if (out.length() > 0) out.append("  ·  ");
            // A weekday is only unambiguous within the coming week; further out, the date.
            boolean soon = s.at - now < 6 * Info.DAY;
            String day = soon ? "EEE" : "d MMM";
            out.append(s.name).append(' ').append(DateFormat.format(day + (h24 ? " HH:mm" : " h:mm a"), new Date(s.at)));
            shown++;
        }
        return out.toString();
    }

    private boolean on(Config.Choice c) {
        return "on".equals(cfg.get(c));
    }

    /** Weather and the next race, under the clock. */
    private void info() {
        StringBuilder s = new StringBuilder();
        String weather = feeds.weather;
        if (weather != null) s.append(weather);
        Feeds.Race race = feeds.race;
        if (race != null) {
            long now = System.currentTimeMillis();
            if (s.length() > 0) s.append("     ");
            s.append(race.name.replace("Grand Prix", "GP"))
                    .append(now >= race.at ? "  ·  racing now" : " in " + Info.countdown(now, race.at));
        }
        info.setText(s);
        info.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
        double temp = feeds.weatherTemp;
        int code = feeds.weatherCode;
        if (feeds.weather == null || Double.isNaN(temp)) {
            statusTemp.setVisibility(View.GONE);
            return;
        }
        statusTemp.setVisibility(View.VISIBLE);
        statusTemp.setText(Math.round(temp) + "°");
        int kind = Info.skyKind(code);
        if (kind != statusSkyKind) {
            statusSkyKind = kind;
            Glyph g = new Glyph(kind, dp(20));
            g.setBounds(0, 0, dp(20), dp(20));
            statusTemp.setCompoundDrawables(g, null, null, null);
        }
    }

    /** Wi-Fi bars, or Ethernet, or Offline, on the chip next to Options. */
    private void wifi() {
        if (wifiChip.getVisibility() != View.VISIBLE && wifiButton.getVisibility() != View.VISIBLE) return;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo w = wm == null ? null : wm.getConnectionInfo();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        // The DNS filter's VPN is the active network, so Wi-Fi is asked directly first.
        if (w != null && w.getNetworkId() != -1) {
            wifiChip.setText("Wi-Fi");
            bars(Math.max(1, WifiManager.calculateSignalLevel(w.getRssi(), 5)));
        } else if (n != null && n.isConnected()) {
            wifiChip.setText(n.getType() == ConnectivityManager.TYPE_ETHERNET ? "Ethernet" : "Online");
            bars(4);
        } else {
            wifiChip.setText("Offline");
            bars(0);
        }
    }

    private void openWifi() {
        Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
        if (i.resolveActivity(getPackageManager()) != null) launch(i, null);
        else openSettings();
    }

    /**
     * Starts a background refresh of the feeds that are on, at most every 10 minutes unless
     * {@code force} (a setting changed). With every feed off, nothing asks the network.
     */
    private void refreshFeeds(boolean force) {
        boolean weather = on(Config.WEATHER), f1 = on(Config.F1), releases = on(Config.RELEASES);
        if (!weather && !f1 && !releases) {
            feeds.weather = null;
            feeds.race = null;
            feeds.slides = java.util.Collections.emptyMap();
            info();
            return;
        }
        long now = System.currentTimeMillis();
        if (feedsRunning) {
            // A setting changed mid-fetch: let the next minute's tick fetch again.
            if (force) feedsAt = 0;
            return;
        }
        if (!force && now - feedsAt < 10 * Info.MINUTE) return;
        feedsRunning = true;
        feedsAt = now;
        List<String> installed = new ArrayList<>();
        for (Item it : apps.all) installed.add(it.id);
        Feeds.Want want = new Feeds.Want(weather, cfg.get(Config.CITY, "Pune"), f1, releases,
                cfg.get(Config.TMDB_KEY, "").trim(), installed);
        net.execute(() -> {
            boolean changed = feeds.refresh(want);
            ui.post(() -> {
                feedsRunning = false;
                if (isDestroyed()) return;
                // A setting changed while this ran (feedsAt was cleared): fetch for it now.
                if (feedsAt == 0) refreshFeeds(true);
                if (!changed) return;
                info();
                if (resumed && cfg.cinema()) {
                    slidesFor = null;
                    hero(focusedItem());
                }
            });
        });
    }

    /** The visible button horizontally nearest {@code from}, or null. */
    private View nearestChip(View from) {
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (TextView c : chips) {
            if (c.getVisibility() != View.VISIBLE) continue;
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private void bars(int n) {
        wifiIcon.setBars(n);
        statusWifiIcon.setBars(n);
    }

    /** Cinema 2's top-right corner: weather, time, Wi-Fi. Glyphs and small type, no buttons. */
    private View buildStatus() {
        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setVisibility(View.GONE);
        statusTemp = Ui.text(this, 17, 0xD9FFFFFF, Ui.MEDIUM);
        statusTemp.setCompoundDrawablePadding(dp(8));
        statusTemp.setVisibility(View.GONE);
        statusClock = Ui.text(this, 17, 0xD9FFFFFF, Ui.MEDIUM);
        statusWifiIcon = new WifiDrawable(dp(18), dp(15));
        statusWifiIcon.setBounds(0, 0, dp(18), dp(15));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(WRAP, WRAP);
        gap.rightMargin = dp(22);
        status.addView(statusTemp, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(WRAP, WRAP);
        gap2.rightMargin = dp(22);
        status.addView(statusClock, gap2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, dp(44));
        lp.topMargin = dp(6);
        status.setLayoutParams(lp);
        return status;
    }

    /** A round button carrying only a glyph: Options (sliders) and Settings (gear) in Cinema 2. */
    private TextView iconChip(int glyph, String name, View.OnClickListener click) {
        Glyph g = new Glyph(glyph, dp(20));
        g.setBounds(0, 0, dp(20), dp(20));
        return iconChip(g, name, click);
    }

    private TextView iconChip(Drawable icon, String name, View.OnClickListener click) {
        TextView v = chip("", click);
        v.setCompoundDrawables(icon, null, null, null);
        v.setCompoundDrawablePadding(0);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setGravity(Gravity.CENTER);
        // An equal circle: fixed 44dp square (applyCinema2 gives it a 22dp radius), 12dp apart.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(12);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        v.setContentDescription(name);
        v.setVisibility(View.GONE);
        return v;
    }

    /** Lays the screen out for Cinema 2, or back for the other looks. */
    private void applyCinema2(boolean on) {
        boolean lock = locked();
        whenBox.setVisibility(on ? View.GONE : View.VISIBLE);
        headSpacer.setVisibility(on ? View.VISIBLE : View.GONE);
        status.setVisibility(on ? View.VISIBLE : View.GONE);
        wifiButton.setVisibility(on && !lock && on(Config.WIFI) ? View.VISIBLE : View.GONE);
        optionsIcon.setVisibility(on && !lock ? View.VISIBLE : View.GONE);
        settingsIcon.setVisibility(on && !lock ? View.VISIBLE : View.GONE);
        optionsChip.setVisibility(!on && !lock ? View.VISIBLE : View.GONE);
        settingsChip.setVisibility(!on && !lock ? View.VISIBLE : View.GONE);
        if (on) wifiChip.setVisibility(View.GONE);
        for (TextView c : new TextView[] {wifiButton, optionsIcon, settingsIcon}) {
            Ui.focusStyle(c, cfg.accent(), 0x1FFFFFFF, dp(22));
            Drawable d = c.getCompoundDrawables()[0];
            if (d instanceof Glyph) ((Glyph) d).setColors(c.getTextColors());
            if (d instanceof WifiDrawable) ((WifiDrawable) d).setColors(c.getTextColors());
        }
        // Cinema 2 fits status, hero text and both rows on one screen: less padding above.
        View column = page.getChildAt(0);
        column.setPadding(column.getPaddingLeft(), dp(on ? 22 : 40), column.getPaddingRight(), column.getPaddingBottom());
        scrim.setVisibility(on ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams hp = hero.getLayoutParams();
        int heroHeight = on ? MATCH : heroBandH;
        if (hp.height != heroHeight) {
            hp.height = heroHeight;
            hero.setLayoutParams(hp);
        }
        ViewGroup.LayoutParams bp = heroBox.getLayoutParams();
        int boxHeight = dp(on ? 178 : 150);
        if (bp.height != boxHeight) {
            bp.height = boxHeight;
            heroBox.setLayoutParams(bp);
        }
        LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) heroTitle.getLayoutParams();
        int titleWidth = on ? Math.round(getResources().getDisplayMetrics().widthPixels * 0.55f) : MATCH;
        if (tp.width != titleWidth) {
            tp.width = titleWidth;
            heroTitle.setLayoutParams(tp);
        }
        if (on) {
            heroTitle.setSingleLine(false);
            heroTitle.setMaxLines(2);
            heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heroTitle.setLineSpacing(0, 0.95f);
        } else {
            heroTitle.setSingleLine();
            heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        }
        heroTitle.setTypeface(on ? Ui.display(this) : Ui.LIGHT);
        heroKicker.setTypeface(on ? Ui.label(this) : Ui.MEDIUM);
        heroSub.setTypeface(on ? Ui.label(this) : Ui.MEDIUM);
        statusTemp.setTypeface(Ui.label(this));
        ambientTitle.setTypeface(on ? Ui.display(this) : Ui.LIGHT);
        ambientKicker.setTypeface(on ? Ui.label(this) : Ui.MEDIUM);
        statusClock.setTypeface(Ui.label(this));
        if (!on) {
            leaveAmbient(false);
            ui.removeCallbacks(goAmbient);
        }
    }

    /** The largest title size, 40sp down to 28sp, that fits {@code t} in two lines. */
    private void fitTitle(String t) {
        int width = heroTitle.getLayoutParams().width;
        if (width <= 0 || t.isEmpty()) {
            heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
            return;
        }
        android.text.TextPaint p = new android.text.TextPaint(heroTitle.getPaint());
        float sp = 40;
        for (; sp > 28; sp -= 2) {
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
            android.text.StaticLayout l = new android.text.StaticLayout(t, p, width,
                    android.text.Layout.Alignment.ALIGN_NORMAL, 0.95f, 0f, false);
            if (l.getLineCount() <= 2) break;
        }
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    /** Cinema 2: only the row with focus is at full brightness; the other rows rest at 55%. */
    private void dimRows(Tile focused) {
        if (!cfg.cinema2() || apps == null) return;
        ViewGroup g = apps.tiles();
        if (!(g instanceof Grid)) return;
        int cols = ((Grid) g).columns();
        int row = focused != null && focused.getParent() == g ? g.indexOfChild(focused) / cols : 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View t = g.getChildAt(i);
            float a = i / cols == row ? 1f : 0.55f;
            if (t.getAlpha() != a) ObjectAnimator.ofFloat(t, View.ALPHA, a).setDuration(220).start();
        }
    }

    /** Keeps the race card's countdown current while FanCode has focus. */
    private void refreshRaceKicker() {
        if (!cfg.cinema2() || ambient) return;
        Item f = focusedItem();
        Feeds.Race r = feeds.race;
        if (f != null && r != null && Info.showsF1(f.id) && heroSlides == null) {
            kicker(Info.raceKicker(r.round, r.place, System.currentTimeMillis(), r.at), RACE_RED);
        }
    }

    /** The slide on screen, or null. */
    private Feeds.Slide currentSlide() {
        List<Feeds.Slide> list = heroSlides;
        return list == null || list.isEmpty() ? null : list.get(slideIndex % list.size());
    }

    private static boolean isPlayKey(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY;
    }

    /**
     * Opens a release slide's title: Netflix at the title's page when its Netflix id is known,
     * Prime Video at its search for the title, JioHotstar through Android's search request;
     * otherwise, or if the app refuses, the app itself. The app's own screens are cleared first,
     * or an app already running brings back where it was and drops the link.
     */
    private void openSlide(Feeds.Slide s) {
        Item app = findApp(s.pkg);
        if (app == null) return;
        String[] plan = Info.openPlan(s.pkg, s.title, s.serviceId);
        Intent i = null;
        if (plan != null && "view".equals(plan[0])) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(plan[1])).setPackage(s.pkg);
        } else if (plan != null && "search".equals(plan[0])) {
            i = new Intent(Intent.ACTION_SEARCH).setPackage(s.pkg).putExtra(android.app.SearchManager.QUERY, plan[1]);
        }
        if (i != null && "com.netflix.ninja".equals(s.pkg)) {
            // Netflix drops a title link it was not launched for by a partner launcher: it restarts
            // itself on its home. With source 30 (partner launch) and kept out of recents, it opens
            // the title's page (checked on a Fire TV Stick, Fire OS 7.7).
            i.putExtra("source", "30").addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }
        if (i != null && i.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                Recent.opened(this, s.pkg);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // falls through to the app itself
            }
        }
        launch(appIntent(app), app.id);
    }

    /** The installed app with this package, or null. */
    private Item findApp(String pkg) {
        for (Item it : apps.all) if (it.id.equals(pkg)) return it;
        return null;
    }

    private void scheduleAmbient() {
        ui.removeCallbacks(goAmbient);
        if (resumed && cfg.cinema2() && on(Config.AMBIENT)) ui.postDelayed(goAmbient, AMBIENT_MS);
    }

    /** Two idle minutes on the home screen: fade the controls, play every app's new releases. */
    private void enterAmbient() {
        if (!resumed || ambient || moving != null || !cfg.cinema2()) return;
        List<Feeds.Slide> all = new ArrayList<>();
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all, recentFor(Catalog.APPS))) {
            all.addAll(feeds.slidesFor(it.id));
        }
        ambient = true;
        ambientCaption.setVisibility(View.VISIBLE);
        page.animate().alpha(0f).setDuration(700).start();
        scrim.animate().alpha(0.45f).setDuration(700).start();
        if (!all.isEmpty()) {
            heroSlides = all;
            slidesFor = "ambient";
            slideIndex = 0;
            showSlide();
        }
    }

    /** Back from ambient mode: controls back, the hero shows the focused app again. */
    private void leaveAmbient(boolean animate) {
        if (!ambient) return;
        ambient = false;
        page.animate().cancel();
        scrim.animate().cancel();
        ambientCaption.animate().cancel();
        ambientCaption.setAlpha(0f);
        ambientCaption.setVisibility(View.GONE);
        if (animate) {
            page.animate().alpha(1f).setDuration(250).start();
            scrim.animate().alpha(1f).setDuration(250).start();
        } else {
            page.setAlpha(1f);
            scrim.setAlpha(1f);
        }
        stopSlides();
        if (animate) {
            hero(focusedItem());
            scheduleAmbient();
        }
    }

    private void showHero(String id, Bitmap b) {
        heroId = id;
        Drawable next = b == null ? new ColorDrawable(Color.TRANSPARENT) : new BitmapDrawable(getResources(), b);
        Drawable from = heroShown == null ? new ColorDrawable(Color.TRANSPARENT) : heroShown;
        TransitionDrawable fade = new TransitionDrawable(new Drawable[] {from, next});
        fade.setCrossFadeEnabled(true);
        hero.setImageDrawable(fade);
        boolean c2 = cfg.cinema2();
        fade.startTransition(c2 ? 420 : 320);
        heroShown = next;
        hero.animate().cancel();
        hero.setScaleX(1f);
        hero.setScaleY(1f);
        if (c2 && b != null && id != null && id.startsWith("slide:")
                && Settings.Global.getFloat(getContentResolver(), "animator_duration_scale", 1f) != 0f) {
            // A slow push-in over the slide's eight seconds.
            hero.setPivotX(hero.getWidth() / 2f);
            hero.setPivotY(hero.getHeight() * 0.35f);
            hero.animate().scaleX(1.06f).scaleY(1.06f).setDuration(8400)
                    .setInterpolator(new LinearInterpolator()).start();
        }
    }

    private void focusBackground(Item it) {
        if (cfg.cinema2()) return;
        String mode = cfg.get(Config.BACKGROUND);
        if (!resumed || !"adaptive".equals(mode) && !"adaptive-tint".equals(mode)) return;
        if ("adaptive-tint".equals(mode) && !tintWallpaper) return;
        if (it != null && it.info != null && it.backgroundColor == 0 && !it.colorRequested
                && (it.banner != null || it.icon != null)) {
            it.colorRequested = true;
            colorIo.execute(() -> {
                int color = FocusColor.from(it.banner != null ? it.banner : it.icon);
                ui.post(() -> {
                    it.backgroundColor = color;
                    if (focusedItem() == it) focusBackground(it);
                });
            });
        }
        int color = it == null || it.backgroundColor == 0 ? FocusColor.FALLBACK : it.backgroundColor;
        if ("adaptive-tint".equals(mode)) color = FocusColor.tint(color);
        if (color == targetColor) return;
        targetColor = color;
        stopColorAnimation();
        if (Settings.Global.getFloat(getContentResolver(), "animator_duration_scale", 1f) == 0f) {
            setAdaptiveColor(color);
            return;
        }
        colorAnimation = ValueAnimator.ofArgb(displayedColor, color);
        colorAnimation.setDuration(250);
        colorAnimation.addUpdateListener(a -> setAdaptiveColor((Integer) a.getAnimatedValue()));
        colorAnimation.start();
    }

    private void setAdaptiveColor(int color) {
        displayedColor = color;
        if ("adaptive-tint".equals(backgroundMode)) tint.setBackgroundColor(color);
        else root.setBackgroundColor(color);
    }

    private void stopColorAnimation() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(144), dp(81)};
            case "large":
                return new int[] {dp(224), dp(126)};
            default:
                return new int[] {dp(180), dp(101)};
        }
    }

    /**
     * The tile of {@code kind} that was focused last, or null when there is none left.
     */
    private View rememberedTile(String kind) {
        if (kind == null) return null;
        String id = Catalog.APPS.equals(kind) ? lastAppId : lastInputId;
        if (id == null) return null;
        View v = findTile(kind, id);
        if (v == null) {
            // The tile is gone: the row was rebuilt without it.
            if (Catalog.APPS.equals(kind)) lastAppId = null;
            else lastInputId = null;
        }
        return v;
    }

    /** The tile with this id in the row of {@code kind}, or null when there is none. */
    private View findTile(String kind, String id) {
        if (kind == null || id == null) return null;
        Row row = Catalog.APPS.equals(kind) ? apps : inputs;
        ViewGroup tiles = row.tiles();
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View v = tiles.getChildAt(i);
            if (v instanceof Tile
                    && ((Tile) v).kind.equals(kind)
                    && ((Tile) v).item.id.equals(id)) {
                return v;
            }
        }
        return null;
    }

    private void focusFirst() {
        apps.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        View first = apps.tiles().getChildAt(0);
        if (first == null) first = inputs.tiles().getChildAt(0);
        landed = apps.tiles().getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        statusClock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm a", now));
        refreshRaceKicker();
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        if (meter.getVisibility() == View.VISIBLE) {
            meter.update((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        }
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
        info();
        wifi();
        if (resumed) refreshFeeds(false);
    }

    private void background() {
        if (cfg.cinema2()) {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = "cinema2";
            tint.setBackgroundColor(Color.TRANSPARENT);
            shade.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(NEAR_BLACK);
            if (wallKey != null) {
                wall.setImageDrawable(null);
                wallKey = null;
            }
            return;
        }
        String mode = cfg.get(Config.BACKGROUND);
        boolean image = "image".equals(mode) || "adaptive-tint".equals(mode);
        File file = image ? cfg.wallpaper() : null;
        tintWallpaper = "adaptive-tint".equals(mode) && file != null;
        if ("adaptive".equals(mode)) {
            if (!mode.equals(backgroundMode)) {
                stopColorAnimation();
                targetColor = 0;
                root.setBackgroundColor(FocusColor.FALLBACK);
                displayedColor = FocusColor.FALLBACK;
                tint.setBackgroundColor(Color.TRANSPARENT);
            }
            backgroundMode = mode;
            focusBackground(focusedItem());
        } else if ("adaptive-tint".equals(mode)) {
            root.setBackgroundColor(file == null ? cfg.background() : Color.BLACK);
            if (!mode.equals(backgroundMode) || file == null) {
                stopColorAnimation();
                targetColor = 0;
                tint.setBackgroundColor(Color.TRANSPARENT);
                displayedColor = Color.TRANSPARENT;
            }
            backgroundMode = mode;
            if (tintWallpaper) focusBackground(focusedItem());
        } else {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = mode;
            tint.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(cfg.background());
        }
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            wall.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(() -> {
                if (key.equals(wallKey)) wall.setImageBitmap(b);
            });
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen,
     * except on Fire OS 6, where that page draws off the top of the screen: see FireSettings.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (FireSettings.applies(getPackageManager(), tvSettings)) {
            settingsList();
            return;
        }
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void settingsList() {
        List<Item> sections = FireSettings.sections(this);
        CharSequence[] labels = new CharSequence[sections.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = sections.get(i).label;
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Settings")
                .setItems(labels, (d, which) -> {
                    Item s = sections.get(which);
                    if (Guard.AMAZON.equals(s.id)) Guard.pause();
                    launch(new Intent(s.intent), null);
                })
                .show();
    }

    /**
     * On Fire OS 8 the Appstore's launcher entry opens Amazon's home with its apps grid, which
     * Guard then replaces with ours, so the tile seemed to do nothing (#133). Its store screen is
     * opened instead. On Fire OS 7 the entry opens the store itself.
     */
    private Intent appIntent(Item it) {
        if (Build.VERSION.SDK_INT >= 30 && APPSTORE.equals(it.id)) {
            Intent store = new Intent(Intent.ACTION_MAIN)
                    .setClassName(APPSTORE, APPSTORE + ".ade.ADEHomeActivity");
            if (store.resolveActivity(getPackageManager()) != null) return store;
        }
        return new Intent(it.intent);
    }

    private void open(Tile t) {
        if (Catalog.APPS.equals(t.kind)) {
            launch(appIntent(t.item), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            if (app && recentFor(t.kind) != null) {
                                Toast.makeText(this, "App order is Recently used. Pick Your order "
                                        + "in Options to move tiles.", Toast.LENGTH_LONG).show();
                                break;
                            }
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        ViewGroup strip = (ViewGroup) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        ViewGroup strip = (ViewGroup) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
