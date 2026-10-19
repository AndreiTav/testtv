package dev.salattv.launcher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.io.File;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String[] DEFAULT_DOCK = {"org.jellyfin.androidtv", "com.netflix.ninja",
            "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom"};
    private static final String[] DEFAULT_LABELS = {"Jellyfin", "Netflix", "YouTube", "Prime Video"};
    private static final Interpolator SPRING = t -> t == 1f ? 1f : (float)(1 - Math.exp(-7 * t) * Math.cos(10 * t));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> artwork = new LruCache<>(3 * 1024 * 1024) {
        @Override protected int sizeOf(@NonNull String key, @NonNull Bitmap value) { return value.getByteCount(); }
    };
    private final List<App> apps = new ArrayList<>(), grid = new ArrayList<>();
    private final List<Tile> dockTiles = new ArrayList<>();
    private final List<TextView> topButtons = new ArrayList<>();
    private SharedPreferences prefs;
    private Typeface font;
    private FrameLayout root;
    private ScrollView scroll;
    private Ambient ambient;
    private ImageView wallpaper;
    private Bitmap wallpaperBitmap;
    private Glass highlight;
    private TextView clock, date, dockTitle, wifiButton;
    private RecyclerView recycler;
    private String[] dockPackages;
    private int lastDock = 0, tileWidth, tileHeight;
    private View focused;
    private boolean resumed, changingUi;
    private PopupWindow popup;
    private String loadedWallpaper = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR")).format(now));
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private record App(String packageName, String label, ResolveInfo info) {}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        // Also permits a local wallpaper on TVs without a document picker.
        File external = getExternalFilesDir(null);
        if (external != null && !external.exists()) external.mkdirs();
        font = Typeface.createFromAsset(getAssets(), "fonts/Outfit.ttf");
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshApps();
        refreshWifi();
        loadWallpaper();
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        if (popup != null) popup.dismiss();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        images.shutdownNow();
        artwork.evictAll();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (focused != null) focused.post(() -> focused.requestFocus());
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(font);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void buildUi() {
        float width = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float height = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density;
        tileWidth = Math.max(110, (int)((width - 80 - 32 - 60) / 4));
        tileHeight = Math.round(tileWidth / 1.78f);
        int dockHeight = tileHeight + 52;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        setContentView(root);
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setAlpha(.35f);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        ambient = new Ambient();
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(40), dp(20), dp(40), dp(40));
        content.setClipChildren(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.TOP);
        content.addView(top, new LinearLayout.LayoutParams(-1, dp(100)));
        LinearLayout greeting = new LinearLayout(this);
        greeting.setOrientation(LinearLayout.VERTICAL);
        greeting.addView(text("Olá", 14, 0xFFABB3C0));
        clock = text("", 48, Color.WHITE);
        clock.setTypeface(Typeface.create(font, 200, false));
        greeting.addView(clock);
        date = text("", 12, 0xFFABB3C0);
        greeting.addView(date);
        top.addView(greeting, new LinearLayout.LayoutParams(0, -2, 1));

        TextView inputs = pill("Entradas", 0);
        top.addView(inputs);
        inputs.setOnClickListener(v -> showInputs(inputs));
        wifiButton = pill("Wi-Fi", 1);
        top.addView(wifiButton);
        wifiButton.setOnClickListener(v -> wifiOptions());
        TextView settings = pill("Configurações", 2);
        top.addView(settings);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        settings.setOnLongClickListener(v -> { homeOptions(); return true; });

        FrameLayout hero = new FrameLayout(this);
        content.addView(hero, new LinearLayout.LayoutParams(-1,
                dp(Math.max(90, height - 20 - 100 - dockHeight - 24))));
        dockTitle = text("Jellyfin", 48, 0xFFE7ECF5);
        dockTitle.setTypeface(Typeface.create(font, 300, false));
        dockTitle.setSingleLine(true);
        dockTitle.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams heroName = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        heroName.bottomMargin = dp(32);
        hero.addView(dockTitle, heroName);

        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(16), dp(12), dp(16), dp(12));
        dock.setBackground(rounded(0xD9171B24, 22));
        content.addView(dock, new LinearLayout.LayoutParams(-1, dp(dockHeight)));
        for (int i = 0; i < 4; i++) {
            Tile tile = new Tile();
            tile.setTag(new FocusNavigation.Position(FocusNavigation.DOCK, i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) lp.leftMargin = dp(20);
            dock.addView(tile, lp);
            dockTiles.add(tile);
        }

        TextView heading = text("Seus apps", 19, 0xFFAEB8C9);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(32);
        headingParams.bottomMargin = dp(18);
        content.addView(heading, headingParams);
        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new GridLayoutManager(this, 4));
        recycler.setNestedScrollingEnabled(false);
        recycler.setClipChildren(false);
        recycler.setClipToPadding(false);
        recycler.setItemAnimator(null);
        recycler.setAdapter(new AppAdapter());
        recycler.setFocusable(false);
        content.addView(recycler, new LinearLayout.LayoutParams(-1, -2));

        highlight = new Glass();
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(highlight, new FrameLayout.LayoutParams(-1, -1));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> highlight.follow(focused, false));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> highlight.follow(focused, false));
    }

    private TextView pill(String label, int index) {
        TextView view = text(label, 13, 0xFFE3E8F0);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setSingleLine(true);
        view.setMaxWidth(dp(205));
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setBackground(rounded(0xC1272D37, 30));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(12);
        lp.topMargin = dp(6);
        view.setLayoutParams(lp);
        view.setFocusable(true);
        view.setTag(new FocusNavigation.Position(FocusNavigation.TOP, index));
        view.setOnFocusChangeListener(this::focusChanged);
        topButtons.add(view);
        return view;
    }

    private void refreshApps() {
        String savedPackage = prefs.getString("focus", DEFAULT_DOCK[0]);
        apps.clear();
        Set<String> seen = new HashSet<>();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(intent, 0)) {
            String name = info.activityInfo.packageName;
            if (!name.equals(getPackageName()) && info.activityInfo.enabled
                    && info.activityInfo.applicationInfo.enabled && seen.add(name)) {
                apps.add(new App(name, info.loadLabel(getPackageManager()).toString(), info));
            }
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((a, b) -> collator.compare(a.label(), b.label()));
        dockPackages = prefs.getString("dock", String.join(",", DEFAULT_DOCK)).split(",");
        if (dockPackages.length != 4 || new HashSet<>(Arrays.asList(dockPackages)).size() != 4)
            dockPackages = DEFAULT_DOCK.clone();
        changingUi = true;
        for (int i = 0; i < 4; i++) {
            App app = findApp(dockPackages[i]);
            if (app == null) {
                String label = dockPackages[i];
                for (int j = 0; j < 4; j++) if (DEFAULT_DOCK[j].equals(label)) label = DEFAULT_LABELS[j];
                app = new App(dockPackages[i], label, null);
            }
            dockTiles.get(i).bind(app);
        }
        grid.clear();
        for (App app : apps) if (!Arrays.asList(dockPackages).contains(app.packageName())) grid.add(app);
        recycler.getAdapter().notifyDataSetChanged();
        changingUi = false;
        int dockIndex = Arrays.asList(dockPackages).indexOf(savedPackage);
        if (dockIndex >= 0) {
            lastDock = dockIndex;
            focus(new FocusNavigation.Position(FocusNavigation.DOCK, dockIndex));
        } else {
            int index = -1;
            for (int i = 0; i < grid.size(); i++) if (grid.get(i).packageName().equals(savedPackage)) index = i;
            focus(new FocusNavigation.Position(index >= 0 ? FocusNavigation.GRID : FocusNavigation.DOCK,
                    index >= 0 ? index : lastDock));
        }
    }

    private App findApp(String name) {
        for (App app : apps) if (app.packageName().equals(name)) return app;
        return null;
    }

    private class Tile extends LinearLayout {
        final ImageView image;
        final TextView label;
        App app;
        Tile() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setFocusable(true);
            setClipChildren(false);
            image = new ImageView(MainActivity.this);
            image.setBackground(rounded(0xFF202632, 13));
            image.setClipToOutline(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image, new LinearLayout.LayoutParams(-1, dp(tileHeight)));
            label = text("", 13, Color.WHITE);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setAlpha(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(22));
            lp.topMargin = dp(6);
            addView(label, lp);
            setOnFocusChangeListener(MainActivity.this::focusChanged);
            setOnClickListener(v -> openApp(app));
        }
        void bind(App value) {
            app = value;
            label.setText(app.label());
            setContentDescription(app.label() + (app.info() == null ? ", indisponível" : ""));
            image.setImageDrawable(null);
            image.setAlpha(app.info() == null ? .35f : 1f);
            Bitmap cached = artwork.get(app.packageName());
            if (cached != null) { image.setImageBitmap(cached); return; }
            if (app.info() == null) { image.setImageResource(dev.salattv.launcher.R.drawable.app_icon); return; }
            images.execute(() -> {
                Bitmap bitmap = renderArtwork(value);
                if (bitmap == null) return;
                artwork.put(value.packageName(), bitmap);
                handler.post(() -> {
                    if (!isDestroyed() && app == value) {
                        image.setImageBitmap(bitmap);
                        if (isFocused() && getTag() instanceof FocusNavigation.Position p
                                && p.region() == FocusNavigation.DOCK) ambient.setTint(sample(bitmap));
                    }
                });
            });
        }
    }

    private Bitmap renderArtwork(App app) {
        try {
            Drawable drawable = app.info().activityInfo.loadBanner(getPackageManager());
            if (drawable == null) drawable = app.info().activityInfo.applicationInfo.loadBanner(getPackageManager());
            boolean banner = drawable != null;
            if (drawable == null) drawable = app.info().loadIcon(getPackageManager());
            Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xFF202632);
            int w = Math.max(1, drawable.getIntrinsicWidth()), h = Math.max(1, drawable.getIntrinsicHeight());
            float scale = Math.min(320f / w, 180f / h) * (banner ? 1 : .68f);
            int dw = Math.round(w * scale), dh = Math.round(h * scale);
            drawable.setBounds((320-dw)/2, (180-dh)/2, (320+dw)/2, (180+dh)/2);
            drawable.draw(canvas);
            return bitmap;
        } catch (RuntimeException e) { return null; }
    }

    private int sample(Bitmap bitmap) {
        long red = 0, green = 0, blue = 0, weight = 0;
        for (int y = 12; y < bitmap.getHeight(); y += 16) {
            for (int x = 12; x < bitmap.getWidth(); x += 16) {
                int color = bitmap.getPixel(x, y);
                int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                int saturation = Math.max(r, Math.max(g,b)) - Math.min(r, Math.min(g,b));
                int w = Math.max(1, saturation);
                red += (long)r*w; green += (long)g*w; blue += (long)b*w; weight += w;
            }
        }
        return weight == 0 ? 0xFF4677AA : Color.rgb((int)(red/weight), (int)(green/weight), (int)(blue/weight));
    }

    private class AppAdapter extends RecyclerView.Adapter<AppHolder> {
        @NonNull @Override public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Tile tile = new Tile();
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(tileHeight + 52));
            lp.leftMargin = dp(8); lp.rightMargin = dp(8);
            tile.setLayoutParams(lp);
            return new AppHolder(tile);
        }
        @Override public void onBindViewHolder(@NonNull AppHolder holder, int position) {
            holder.tile.setTag(new FocusNavigation.Position(FocusNavigation.GRID, position));
            holder.tile.bind(grid.get(position));
        }
        @Override public int getItemCount() { return grid.size(); }
    }

    private static class AppHolder extends RecyclerView.ViewHolder {
        final Tile tile;
        AppHolder(Tile tile) { super(tile); this.tile = tile; }
    }

    private void focusChanged(View view, boolean hasFocus) {
        if (view instanceof Tile tile) {
            tile.image.animate().cancel();
            tile.image.animate().scaleX(hasFocus ? 1.035f : 1).scaleY(hasFocus ? 1.035f : 1)
                    .setDuration(260).setInterpolator(SPRING).start();
            tile.label.animate().alpha(hasFocus ? 1 : 0).setDuration(130).start();
        }
        if (!hasFocus || changingUi) return;
        focused = view;
        if (view instanceof Tile tile) {
            prefs.edit().putString("focus", tile.app.packageName()).apply();
            FocusNavigation.Position p = (FocusNavigation.Position)view.getTag();
            if (p.region() == FocusNavigation.DOCK) {
                lastDock = p.index();
                dockTitle.setText(tile.app.label());
                Bitmap bitmap = artwork.get(tile.app.packageName());
                ambient.setTint(bitmap == null ? 0xFF4677AA : sample(bitmap));
            }
        }
        highlight.follow(view, true);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        int direction = switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> FocusNavigation.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> FocusNavigation.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP -> FocusNavigation.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> FocusNavigation.DOWN;
            default -> -1;
        };
        if (direction >= 0 && hasWindowFocus() && getCurrentFocus() != null
                && getCurrentFocus().getTag() instanceof FocusNavigation.Position p) {
            if (event.getAction() == KeyEvent.ACTION_DOWN)
                focus(FocusNavigation.move(p, direction, grid.size(), lastDock));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void focus(FocusNavigation.Position position) {
        if (position.region() == FocusNavigation.TOP) {
            topButtons.get(position.index()).requestFocus();
            scroll.smoothScrollTo(0, 0);
        } else if (position.region() == FocusNavigation.DOCK) {
            dockTiles.get(position.index()).post(() -> {
                dockTiles.get(position.index()).requestFocus();
                scroll.smoothScrollTo(0, 0);
            });
        } else {
            recycler.post(() -> {
                RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position.index());
                if (holder != null) holder.itemView.requestFocus();
                else {
                    recycler.scrollToPosition(position.index());
                    recycler.post(() -> {
                        RecyclerView.ViewHolder next = recycler.findViewHolderForAdapterPosition(position.index());
                        if (next != null) next.itemView.requestFocus();
                    });
                }
            });
        }
    }

    @Override public void onBackPressed() {
        if (popup != null && popup.isShowing()) popup.dismiss();
        else focus(new FocusNavigation.Position(FocusNavigation.DOCK, lastDock));
    }

    private void openApp(App app) {
        if (app == null || app.info() == null) {
            toast("App indisponível. Ative ou instale o app primeiro.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(app.packageName(), app.info().activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        launch(intent);
    }

    private void launch(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) { toast("Esta opção não está disponível nesta TV."); }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void refreshWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager manager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null && manager.isWifiEnabled()) {
                WifiInfo info = manager.getConnectionInfo();
                String ssid = info == null ? null : info.getSSID();
                if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("0x"))
                    name = ssid.replaceAll("^\"|\"$", "");
            } else name = "Wi-Fi desligado";
        } catch (SecurityException ignored) { /* Name remains private until the user grants permission. */ }
        wifiButton.setText(name);
    }

    private void wifiOptions() {
        new AlertDialog.Builder(this).setTitle("Wi-Fi")
                .setItems(new String[]{"Configurar Wi-Fi", "Mostrar nome da rede"}, (d, which) -> {
                    if (which == 0) launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        refreshWifi();
                        toast("Se o nome não aparecer, verifique a localização nas configurações da TV.");
                    } else new AlertDialog.Builder(this).setTitle("Mostrar nome da rede")
                            .setMessage("O Android exige permissão de localização para revelar o nome do Wi-Fi. O Sala TV não consulta sua posição nem envia dados.")
                            .setPositiveButton("Continuar", (dialog, button) -> requestPermissions(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 22))
                            .setNegativeButton("Cancelar", null).show();
                }).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 22) refreshWifi();
    }

    private void showInputs(View anchor) {
        List<TvInputInfo> inputs = new ArrayList<>();
        try {
            TvInputManager manager = (TvInputManager)getSystemService(TV_INPUT_SERVICE);
            if (manager != null) for (TvInputInfo input : manager.getTvInputList()) {
                int type = input.getType();
                String pkg = input.getServiceInfo().packageName;
                if (type == TvInputInfo.TYPE_HDMI || type == TvInputInfo.TYPE_COMPOSITE
                        || type == TvInputInfo.TYPE_COMPONENT || (type == TvInputInfo.TYPE_TUNER
                        && (pkg.equals("com.tcl.tvinput") || pkg.equals("com.tcl.tv")))) inputs.add(input);
            }
        } catch (SecurityException e) { toast("Use o botão de entradas do controle nesta TV."); return; }
        if (inputs.isEmpty()) { toast("Entradas não expostas ao launcher. Use o botão de entradas do controle."); return; }
        inputs.sort((a,b) -> {
            int type = Integer.compare(a.getType(), b.getType());
            return type != 0 ? type : inputName(a).compareTo(inputName(b));
        });
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackground(rounded(0xF0222833, 18));
        List<TextView> rows = new ArrayList<>();
        for (TvInputInfo input : inputs) {
            TextView row = text(inputName(input), 15, Color.WHITE);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setFocusable(true);
            row.setOnFocusChangeListener((v, focused) -> v.setBackground(rounded(focused ? 0x704C5C73 : Color.TRANSPARENT, 10)));
            row.setOnClickListener(v -> {
                popup.dismiss();
                Uri uri = input.isPassthroughInput() ? TvContract.buildChannelUriForPassthroughInput(input.getId())
                        : TvContract.buildChannelsUriForInput(input.getId());
                launch(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            rows.add(row);
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < rows.size(); i++) {
            final int index = i;
            rows.get(i).setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN)
                        rows.get(Math.max(0, Math.min(rows.size()-1, index + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)))).requestFocus();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
        }
        ScrollView container = new ScrollView(this);
        container.addView(list);
        popup = new PopupWindow(container, dp(250), Math.min(dp(320), dp(inputs.size()*46+16)), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setOnDismissListener(() -> anchor.requestFocus());
        popup.showAsDropDown(anchor, 0, dp(10));
        rows.get(0).requestFocus();
    }

    private String inputName(TvInputInfo input) {
        CharSequence custom = input.loadCustomLabel(this);
        if (!TextUtils.isEmpty(custom)) return custom.toString();
        if (input.getType() == TvInputInfo.TYPE_TUNER) return "TV ao vivo";
        CharSequence label = input.loadLabel(this);
        return TextUtils.isEmpty(label) ? input.getId() : label.toString();
    }

    private void homeOptions() {
        new AlertDialog.Builder(this).setTitle("Sua tela inicial")
                .setItems(new String[]{"Alterar apps e ordem do dock", "Escolher papel de parede", "Usar imagem local", "Usar fundo preto"}, (d, which) -> {
                    if (which == 0) chooseDock(new ArrayList<>());
                    if (which == 1) launchForWallpaper();
                    if (which == 2) {
                        File directory = getExternalFilesDir(null);
                        File image = directory == null ? null : new File(directory, "wallpaper.jpg");
                        if (image == null || !image.isFile()) toast("Nenhuma imagem local disponível. Consulte o README para enviar uma imagem.");
                        else { prefs.edit().putString("wallpaper", Uri.fromFile(image).toString()).apply(); loadWallpaper(); }
                    }
                    if (which == 3) { prefs.edit().remove("wallpaper").apply(); loadWallpaper(); }
                }).show();
    }

    private void chooseDock(List<String> selected) {
        List<App> choices = new ArrayList<>();
        for (App app : apps) if (!selected.contains(app.packageName())) choices.add(app);
        if (choices.isEmpty()) { toast("Não há apps suficientes para preencher o dock."); return; }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label();
        new AlertDialog.Builder(this).setTitle("Dock: posição " + (selected.size()+1) + " de 4")
                .setItems(labels, (d, which) -> {
                    selected.add(choices.get(which).packageName());
                    if (selected.size() == 4) {
                        prefs.edit().putString("dock", String.join(",", selected)).putString("focus", selected.get(0)).apply();
                        refreshApps();
                    } else chooseDock(selected);
                }).setNegativeButton("Cancelar", null).show();
    }

    private void launchForWallpaper() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 23);
        } catch (ActivityNotFoundException e) { toast("Nenhum seletor de arquivos instalado. O fundo preto continua disponível."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 23 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                prefs.edit().putString("wallpaper", uri.toString()).apply();
                loadWallpaper();
            } catch (SecurityException e) { toast("O seletor não permitiu manter acesso à imagem."); }
        }
    }

    private void loadWallpaper() {
        String uriString = prefs.getString("wallpaper", "");
        if (uriString.equals(loadedWallpaper)) return;
        loadedWallpaper = uriString;
        if (uriString.isEmpty()) {
            wallpaper.setImageDrawable(null);
            wallpaperBitmap = null;
            return;
        }
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                Uri uri = Uri.parse(uriString);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream stream = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 1920 || options.outHeight / options.inSampleSize > 1080)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                try (InputStream stream = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
            } catch (Exception ignored) { /* Black remains usable if a document is no longer accessible. */ }
            Bitmap decoded = bitmap;
            handler.post(() -> {
                if (isDestroyed() || !uriString.equals(loadedWallpaper)) { if (decoded != null) decoded.recycle(); return; }
                wallpaperBitmap = decoded;
                wallpaper.setImageBitmap(decoded);
                if (decoded == null) toast("Não foi possível abrir o papel de parede.");
            });
        });
    }

    private class Ambient extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tint = 0xFF42688F;
        private ValueAnimator animator;
        Ambient() { super(MainActivity.this); }
        void setTint(int next) {
            if (animator != null) animator.cancel();
            int previous = tint;
            animator = ValueAnimator.ofArgb(previous, next);
            animator.setDuration(350);
            animator.addUpdateListener(a -> { tint = (int)a.getAnimatedValue(); updateGradient(); invalidate(); });
            animator.start();
        }
        private void updateGradient() {
            int color = Color.argb(75, Color.red(tint), Color.green(tint), Color.blue(tint));
            paint.setShader(new RadialGradient(getWidth()*.27f, getHeight()*.55f,
                    Math.max(1,getWidth()*.5f), new int[]{color, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            updateGradient();
        }
        @Override protected void onDraw(@NonNull Canvas canvas) {
            canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        }
    }

    private class Glass extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator animator;
        Glass() { super(MainActivity.this); setFocusable(false); setClickable(false); }
        void follow(View view, boolean animate) {
            if (view == null || !view.isAttachedToWindow()) return;
            View target = view instanceof Tile tile ? tile.image : view;
            if (target.getWidth() == 0) { target.post(() -> follow(view, false)); return; }
            int[] origin = new int[2], location = new int[2];
            getLocationInWindow(origin); target.getLocationInWindow(location);
            float extra = dp(view instanceof Tile ? 3 : 1);
            RectF end = new RectF(location[0]-origin[0]-extra, location[1]-origin[1]-extra,
                    location[0]-origin[0]+target.getWidth()+extra, location[1]-origin[1]+target.getHeight()+extra);
            if (animator != null) animator.cancel();
            if (!animate || rect.isEmpty()) { rect.set(end); invalidate(); return; }
            RectF start = new RectF(rect);
            animator = ValueAnimator.ofFloat(0,1);
            animator.setDuration(340); animator.setInterpolator(SPRING);
            animator.addUpdateListener(a -> {
                float t = (float)a.getAnimatedValue();
                rect.set(start.left+(end.left-start.left)*t, start.top+(end.top-start.top)*t,
                        start.right+(end.right-start.right)*t, start.bottom+(end.bottom-start.bottom)*t);
                invalidate();
            });
            animator.start();
        }
        @Override protected void onDraw(@NonNull Canvas canvas) {
            if (rect.isEmpty()) return;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0x16E5EEFF);
            canvas.drawRoundRect(rect, dp(15), dp(15), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f)); paint.setColor(0xA0E5EEFF);
            canvas.drawRoundRect(rect, dp(15), dp(15), paint);
        }
    }
}
