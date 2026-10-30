package com.jannek.jarvis;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Der ganze Home-Screen in einer einzigen View: Hintergrund, HUD-Ringe, Uhr, Holo-Panels. */
public class HudView extends View {

    private static final int GOLD = 0xFFD9A441;
    private static final int RED = 0xFFE0283C;
    private static final int WARM = 0xFFFFEFD2;
    private static final int DARK = 0xFF060103;

    private static final String[] FAVS = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus"};
    private static final String[] HIDDEN = {"com.sony.dtv.tvx"};

    // Maße in "Einheiten" bei 1920 Pixel Breite, skaliert mit u.
    private static final float PW = 300f, PH = 170f, GAP = 36f, ROW_Y = 720f, LEFT = 120f;

    private static class Item {
        String label = "";
        String upper = "";
        ComponentName cn;
        Intent intent;
        Drawable art;
        boolean banner;
        boolean special;
    }

    private volatile List<Item> items = new ArrayList<Item>();
    private int sel = 0;
    private float focusPos = 0f;
    private float scroll = 0f;
    private boolean running = true;
    private long last = 0;
    private float u = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final float[] brk = new float[32];
    private Shader glow;
    private float[] grid = new float[0];
    private float[] ticks = new float[0];

    private final Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface mono = Typeface.MONOSPACE;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.GERMAN);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN);
    private long clockSec = -1;
    private String cTime = "";
    private String cDate = "";
    private String cGreet = "";

    private String stats = "";
    private long statsAt = -100000;

    private int idxSel = -1;
    private int idxN = -1;
    private String idxText = "";

    public HudView(Context context) {
        super(context);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        line.setStyle(Paint.Style.STROKE);
        text.setStyle(Paint.Style.FILL);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ---------------------------------------------------------------- Apps laden

    public void reload() {
        final Context ctx = getContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> list = load(ctx);
                post(new Runnable() {
                    @Override
                    public void run() {
                        items = list;
                        if (sel >= list.size()) sel = Math.max(0, list.size() - 1);
                        invalidate();
                    }
                });
            }
        }).start();
    }

    private List<Item> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Item> byPkg = new LinkedHashMap<String, Item>();
        addApps(ctx, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, false, byPkg);
        addApps(ctx, pm, Intent.CATEGORY_LAUNCHER, true, byPkg);

        List<Item> out = new ArrayList<Item>();
        for (String f : FAVS) {
            Item it = byPkg.remove(f);
            if (it != null) out.add(it);
        }
        List<Item> rest = new ArrayList<Item>(byPkg.values());
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
        out.addAll(rest);

        Item s = new Item();
        s.label = "Einstellungen";
        s.upper = "EINSTELLUNGEN";
        s.special = true;
        s.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(s);
        return out;
    }

    private void addApps(Context ctx, PackageManager pm, String category, boolean onlyUserApps,
                         Map<String, Item> byPkg) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(ctx.getPackageName()) || isHidden(pkg) || byPkg.containsKey(pkg)) continue;
            if (onlyUserApps && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

            Item it = new Item();
            it.label = String.valueOf(ri.loadLabel(pm));
            it.upper = it.label.toUpperCase(Locale.GERMAN);
            it.cn = new ComponentName(pkg, ai.name);

            Drawable d = null;
            try {
                d = ai.loadBanner(pm);
            } catch (Throwable ignored) {
            }
            if (d == null) {
                try {
                    d = ai.applicationInfo.loadBanner(pm);
                } catch (Throwable ignored) {
                }
            }
            it.banner = d != null;
            if (d == null) {
                try {
                    d = ai.loadIcon(pm);
                } catch (Throwable ignored) {
                }
            }
            it.art = d;
            it.intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(it.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            byPkg.put(pkg, it);
        }
    }

    private static boolean isHidden(String pkg) {
        for (String h : HIDDEN) {
            if (h.equals(pkg)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Steuerung

    public void setRunning(boolean r) {
        running = r;
        if (r) {
            last = 0;
            invalidate();
        }
    }

    public void resetFocus() {
        sel = 0;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int n = items.size();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel > 0) sel--;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel < n - 1) sel++;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) launch();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void launch() {
        List<Item> list = items;
        if (sel < 0 || sel >= list.size()) return;
        try {
            getContext().startActivity(list.get(sel).intent);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- Layout-Vorbereitung

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        u = w / 1920f;

        glow = new RadialGradient(w * 0.74f, h * 0.30f, w * 0.75f,
                new int[]{0x55A3101F, 0x2A3A0710, 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        float step = 80f * u;
        int vx = (int) (w / step) + 1;
        int hy = (int) (h / step) + 1;
        grid = new float[(vx + hy) * 4];
        int k = 0;
        for (int i = 0; i < vx; i++) {
            float x = i * step;
            grid[k++] = x;
            grid[k++] = 0;
            grid[k++] = x;
            grid[k++] = h;
        }
        for (int j = 0; j < hy; j++) {
            float y = j * step;
            grid[k++] = 0;
            grid[k++] = y;
            grid[k++] = w;
            grid[k++] = y;
        }

        ticks = new float[72 * 4];
        for (int i = 0; i < 72; i++) {
            double ang = Math.toRadians(i * 5.0);
            float r1 = 268f * u;
            float r2 = r1 + ((i % 6 == 0) ? 18f * u : 8f * u);
            ticks[i * 4] = (float) Math.cos(ang) * r1;
            ticks[i * 4 + 1] = (float) Math.sin(ang) * r1;
            ticks[i * 4 + 2] = (float) Math.cos(ang) * r2;
            ticks[i * 4 + 3] = (float) Math.sin(ang) * r2;
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float t = now / 1000f;
        float k = 1f - (float) Math.exp(-dt * 11f);

        List<Item> list = items;
        int n = list.size();
        float pitch = (PW + GAP) * u;
        float maxScroll = Math.max(0f, (n - 5) * pitch);
        float target = Math.max(0f, Math.min(maxScroll, (sel - 2) * pitch));
        focusPos += (sel - focusPos) * k;
        scroll += (target - scroll) * k;

        refreshClock(System.currentTimeMillis());

        c.drawColor(DARK);
        fill.setShader(glow);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        line.setColor(a(GOLD, 14));
        line.setStrokeWidth(1f);
        c.drawLines(grid, line);

        drawRings(c, t);
        drawHeader(c);
        drawFocusTitle(c, list, n);
        drawRow(c, list, n, t, pitch);
        drawStatus(c, now, w);

        if (running) postInvalidateDelayed(33);
    }

    private void drawRings(Canvas c, float t) {
        float cx = getWidth() * 0.74f;
        float cy = getHeight() * 0.30f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2.2f);

        c.save();
        c.translate(cx, cy);

        c.save();
        c.rotate(t * 4f);
        line.setColor(a(GOLD, 110));
        line.setStrokeWidth(2f * u);
        c.drawLines(ticks, line);
        c.restore();

        arc(c, 250f * u, 0, 360, 2f * u, a(RED, 120));

        for (int i = 0; i < 3; i++) {
            arc(c, 228f * u, t * 14f + i * 120f, 70, 5f * u, a(GOLD, 200));
        }
        for (int i = 0; i < 12; i++) {
            arc(c, 196f * u, -t * 20f + i * 30f, 16, 4f * u, a(RED, 190));
        }
        for (int i = 0; i < 2; i++) {
            arc(c, 150f * u, t * 32f + i * 180f, 80, 10f * u, a(RED, 230));
        }
        arc(c, 105f * u, 0, 360, 2f * u, a(GOLD, 120));
        for (int i = 0; i < 3; i++) {
            arc(c, 82f * u, -t * 55f + i * 120f, 40, 8f * u, a(GOLD, 220));
        }

        fill.setColor(a(RED, (int) (70 + 90 * pulse)));
        c.drawCircle(0, 0, 56f * u, fill);
        fill.setColor(a(WARM, (int) (150 + 80 * pulse)));
        c.drawCircle(0, 0, 22f * u, fill);
        arc(c, 40f * u, 0, 360, 3f * u, a(GOLD, 200));

        c.restore();
    }

    private void arc(Canvas c, float r, float start, float sweep, float width, int color) {
        rf.set(-r, -r, r, r);
        stroke.setStrokeWidth(width);
        stroke.setColor(color);
        c.drawArc(rf, start, sweep, false, stroke);
    }

    private void drawHeader(Canvas c) {
        txt(c, cGreet + ", SIR", LEFT * u, 128f * u, mono, 26f * u, 0.35f, GOLD, Paint.Align.LEFT);
        txt(c, cTime, (LEFT - 8f) * u, 290f * u, thin, 170f * u, 0.02f, WARM, Paint.Align.LEFT);
        txt(c, cDate, (LEFT + 4f) * u, 350f * u, mono, 28f * u, 0.25f, a(GOLD, 210), Paint.Align.LEFT);

        line.setColor(a(GOLD, 80));
        line.setStrokeWidth(2f * u);
        c.drawLine(LEFT * u, 382f * u, 700f * u, 382f * u, line);
        line.setColor(RED);
        line.setStrokeWidth(4f * u);
        c.drawLine(LEFT * u, 382f * u, 220f * u, 382f * u, line);
    }

    private void drawFocusTitle(Canvas c, List<Item> list, int n) {
        if (n == 0) return;
        int s = Math.min(sel, n - 1);
        if (idxSel != s || idxN != n) {
            idxSel = s;
            idxN = n;
            idxText = String.format(Locale.GERMAN, "[ %02d / %02d ]", s + 1, n);
        }
        txt(c, idxText, LEFT * u, 600f * u, mono, 22f * u, 0.4f, a(GOLD, 200), Paint.Align.LEFT);
        txt(c, list.get(s).upper, (LEFT - 2f) * u, 668f * u, light, 64f * u, 0.12f, WARM, Paint.Align.LEFT);
    }

    private void drawRow(Canvas c, List<Item> list, int n, float t, float pitch) {
        int w = getWidth();
        for (int i = 0; i < n; i++) {
            float x = LEFT * u + i * pitch - scroll;
            if (x > w || x + PW * u < 0) continue;
            float f = Math.max(0f, 1f - Math.abs(i - focusPos));
            drawTile(c, list.get(i), x, ROW_Y * u, f, t);
        }
    }

    private void drawTile(Canvas c, Item it, float x, float y, float f, float t) {
        float w = PW * u;
        float h = PH * u;
        float cx = x + w / 2f;
        float cy = y + h / 2f;

        c.save();
        float s = 1f + 0.10f * f;
        c.scale(s, s, cx, cy);

        chamfer(path, x, y, w, h, 18f * u);
        fill.setColor(0xCC14040A);
        c.drawPath(path, fill);

        c.save();
        c.clipPath(path);

        if (it.art != null) {
            if (it.banner) {
                it.art.setBounds((int) x, (int) y, (int) (x + w), (int) (y + h));
            } else {
                int sz = (int) (104f * u);
                it.art.setBounds((int) (cx - sz / 2f), (int) (cy - sz / 2f),
                        (int) (cx + sz / 2f), (int) (cy + sz / 2f));
            }
            it.art.draw(c);
        } else {
            arc(c, 0, 0, 0, 0, 0); // no-op, setzt nur Paint zurück
            glyph(c, cx, cy - 12f * u);
            txt(c, it.upper, cx, cy + 56f * u, mono, 20f * u, 0.2f, a(GOLD, 230), Paint.Align.CENTER);
        }

        int dim = (int) (150f * (1f - f));
        if (dim > 0) {
            fill.setColor(a(0x000000, dim));
            c.drawRect(x, y, x + w, y + h, fill);
        }

        if (f > 0.05f) {
            float sy = y + ((t * 0.7f) % 1f) * h;
            fill.setColor(a(RED, (int) (60f * f)));
            c.drawRect(x, sy - 12f * u, x + w, sy + 12f * u, fill);
            line.setColor(a(RED, (int) (200f * f)));
            line.setStrokeWidth(2f * u);
            c.drawLine(x, sy, x + w, sy, line);
        }
        c.restore();

        stroke.setColor(a(GOLD, (int) (70f + 150f * f)));
        stroke.setStrokeWidth((2f + f) * u);
        c.drawPath(path, stroke);

        if (f > 0.05f) {
            float pulse = 6f * u * (0.5f + 0.5f * (float) Math.sin(t * 4f));
            brackets(c, x, y, w, h, 12f * u + pulse, 36f * u, a(GOLD, (int) (255f * f)), 4f * u);
        }
        c.restore();
    }

    /** Kleines HUD-Symbol für Kacheln ohne App-Bild (Einstellungen). */
    private void glyph(Canvas c, float cx, float cy) {
        c.save();
        c.translate(cx, cy);
        arc(c, 30f * u, 0, 360, 3f * u, a(GOLD, 230));
        arc(c, 18f * u, 0, 360, 3f * u, a(RED, 230));
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f);
            line.setColor(a(GOLD, 230));
            line.setStrokeWidth(3f * u);
            c.drawLine(0, -34f * u, 0, -42f * u, line);
            c.restore();
        }
        c.restore();
    }

    private void drawStatus(Canvas c, long now, int w) {
        if (now - statsAt > 5000) updateStats(now);
        line.setColor(a(GOLD, 50));
        line.setStrokeWidth(1.5f * u);
        c.drawLine(LEFT * u, 1020f * u, w - LEFT * u, 1020f * u, line);
        txt(c, stats, LEFT * u, 1052f * u, mono, 19f * u, 0.2f, a(GOLD, 180), Paint.Align.LEFT);
        txt(c, "JARVIS HOME 0.1", w - LEFT * u, 1052f * u, mono, 19f * u, 0.2f, a(RED, 230), Paint.Align.RIGHT);
    }

    private void updateStats(long now) {
        statsAt = now;
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long ramMb = mi.availMem / 1048576L;
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long diskMb = sf.getAvailableBytes() / 1048576L;
            int apps = Math.max(0, items.size() - 1);
            stats = "RAM " + ramMb + " MB FREI   //   SPEICHER " + diskMb + " MB FREI   //   APPS " + apps;
        } catch (Exception e) {
            stats = "SYSTEM ONLINE";
        }
    }

    private void refreshClock(long wallMs) {
        long sec = wallMs / 1000L;
        if (sec == clockSec) return;
        clockSec = sec;
        Date d = new Date(wallMs);
        cTime = timeFmt.format(d);
        cDate = dateFmt.format(d).toUpperCase(Locale.GERMAN);
        Calendar cal = Calendar.getInstance();
        cal.setTime(d);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour < 5) cGreet = "GUTE NACHT";
        else if (hour < 11) cGreet = "GUTEN MORGEN";
        else if (hour < 18) cGreet = "GUTEN TAG";
        else cGreet = "GUTEN ABEND";
    }

    // ---------------------------------------------------------------- Helfer

    private void txt(Canvas c, String s, float x, float y, Typeface tf, float size, float spacing,
                     int color, Paint.Align align) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setLetterSpacing(spacing);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
    }

    private static void chamfer(Path p, float x, float y, float w, float h, float cut) {
        p.reset();
        p.moveTo(x + cut, y);
        p.lineTo(x + w, y);
        p.lineTo(x + w, y + h - cut);
        p.lineTo(x + w - cut, y + h);
        p.lineTo(x, y + h);
        p.lineTo(x, y + cut);
        p.close();
    }

    private void brackets(Canvas c, float x, float y, float w, float h, float o, float len,
                          int color, float width) {
        line.setColor(color);
        line.setStrokeWidth(width);
        float x0 = x - o;
        float y0 = y - o;
        float x1 = x + w + o;
        float y1 = y + h + o;
        float[] b = brk;
        int i = 0;
        b[i++] = x0; b[i++] = y0; b[i++] = x0 + len; b[i++] = y0;
        b[i++] = x0; b[i++] = y0; b[i++] = x0; b[i++] = y0 + len;
        b[i++] = x1; b[i++] = y0; b[i++] = x1 - len; b[i++] = y0;
        b[i++] = x1; b[i++] = y0; b[i++] = x1; b[i++] = y0 + len;
        b[i++] = x0; b[i++] = y1; b[i++] = x0 + len; b[i++] = y1;
        b[i++] = x0; b[i++] = y1; b[i++] = x0; b[i++] = y1 - len;
        b[i++] = x1; b[i++] = y1; b[i++] = x1 - len; b[i++] = y1;
        b[i++] = x1; b[i++] = y1; b[i++] = x1; b[i] = y1 - len;
        c.drawLines(b, 0, 32, line);
    }

    private static int a(int color, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
