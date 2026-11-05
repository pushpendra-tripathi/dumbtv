package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The art behind the top of the screen in the Cinema look: the focused app's banner, blurred,
 * darkened and faded out towards the bottom so the tiles below sit on the wallpaper.
 *
 * The blur is cheap: the banner is drawn into a bitmap 32 pixels wide, box-blurred there three
 * times (close to a gaussian), then scaled up in steps with bilinear filtering. A wordmark becomes
 * a glow of its colours instead of readable, blocky letters. No RenderScript, nothing newer than
 * Android 7.1, and the result is a few hundred KB.
 */
final class Hero {
    /** Width and height of the band, as a fraction of the screen. */
    static final float HEIGHT = 0.62f;
    private static final int SMALL_W = 32;
    private static final int BLUR_RADIUS = 3;
    private static final int BLUR_PASSES = 3;
    private static final int OUT_W = 384;
    private static final int BACKDROP_W = 960;

    private Hero() {}

    /** Box-blurs {@code b} in place, {@code passes} times, edges clamped. */
    static void blur(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** One box pass from {@code in} to {@code out}, along rows or along columns. */
    static void box(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w, len = rows ? w : h, n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int a = 0, red = 0, g = 0, bl = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int c = in[rows ? line * w + j : j * w + line];
                    a += c >>> 24;
                    red += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    bl += c & 0xFF;
                }
                out[rows ? line * w + i : i * w + line] = (a / n) << 24 | (red / n) << 16 | (g / n) << 8 | bl / n;
            }
        }
    }

    /**
     * A sharp backdrop for a new-release slide: {@code file} centre-cropped to the band, darkened
     * on the left where the title sits, and faded out at the bottom like the blurred art.
     */
    static Bitmap backdrop(java.io.File file, float aspect) {
        if (file == null || aspect <= 0) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= BACKDROP_W) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        try {
            int outH = Math.max(1, Math.round(BACKDROP_W / aspect));
            Bitmap out = Bitmap.createBitmap(BACKDROP_W, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            // Centre-crop, biased to the top: a backdrop's subject is rarely at its bottom edge.
            float scale = Math.max(BACKDROP_W / (float) src.getWidth(), outH / (float) src.getHeight());
            float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
            android.graphics.RectF dst = new android.graphics.RectF((BACKDROP_W - dw) / 2, (outH - dh) * 0.3f,
                    (BACKDROP_W + dw) / 2, (outH - dh) * 0.3f + dh);
            c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            Paint side = new Paint();
            side.setShader(new LinearGradient(0, 0, BACKDROP_W * 0.65f, 0,
                    0xB0000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, BACKDROP_W, outH, side);
            c.drawColor(0x26000000);
            Paint mask = new Paint();
            mask.setShader(new LinearGradient(0, 0, 0, outH,
                    new int[] {0xFF000000, 0xFF000000, 0x00000000},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            c.drawRect(0, 0, BACKDROP_W, outH, mask);
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            src.recycle();
        }
    }

    /**
     * The F1 race card's art: a circuit's track map, redrawn as white lines with a soft red glow
     * and placed on the right of the band, the rest transparent so the wallpaper shows. Works
     * for maps on a transparent background (the line is the alpha) and on a plain one (the line
     * is whatever differs from the corner colour).
     */
    static Bitmap circuit(java.io.File file, float aspect) {
        if (file == null || aspect <= 0) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 640) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        Bitmap lines = null, glow = null;
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            int[] px = new int[sw * sh];
            src.getPixels(px, 0, sw, 0, 0, sw, sh);
            int clear = 0;
            for (int c : px) if (c >>> 24 < 16) clear++;
            boolean transparent = clear > px.length / 4;
            float bg = lum(px[0]);
            int[] ink = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                float a = (px[i] >>> 24) / 255f;
                float v = transparent ? a : Math.min(1f, Math.abs(lum(px[i]) - bg) * 1.8f) * a;
                ink[i] = Math.round(v * 255);
            }
            // Opening (erode twice, then dilate twice: 5x5): drops strokes up to about four pixels
            // wide, which on a circuit map are its labels, legend and scale bar, and keeps the
            // track line, drawn thicker than that at this size.
            ink = spread(spread(ink, sw, sh, false), sw, sh, false);
            ink = spread(spread(ink, sw, sh, true), sw, sh, true);
            for (int i = 0; i < px.length; i++) px[i] = ink[i] << 24 | 0x00FFFFFF;
            lines = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
            glow = Bitmap.createScaledBitmap(lines, Math.max(1, sw / 6), Math.max(1, sh / 6), true);
            blur(glow, 2, 3);

            int outH = Math.max(1, Math.round(BACKDROP_W / aspect));
            Bitmap out = Bitmap.createBitmap(BACKDROP_W, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            // Fit the map into the right of the band, below the chips and clear of the title.
            float boxL = BACKDROP_W * 0.56f, boxR = BACKDROP_W * 0.95f, boxT = outH * 0.27f, boxB = outH * 0.78f;
            float scale = Math.min((boxR - boxL) / sw, (boxB - boxT) / sh);
            float dw = sw * scale, dh = sh * scale;
            float left = boxL + ((boxR - boxL) - dw) / 2, top = boxT + ((boxB - boxT) - dh) / 2;
            android.graphics.RectF dst = new android.graphics.RectF(left, top, left + dw, top + dh);
            Paint red = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            red.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFE8202A, PorterDuff.Mode.SRC_IN));
            red.setAlpha(200);
            c.drawBitmap(glow, null, dst, red);
            Paint white = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            white.setAlpha(235);
            c.drawBitmap(lines, null, dst, white);
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            src.recycle();
            if (lines != null) lines.recycle();
            if (glow != null) glow.recycle();
        }
    }

    /** One 3x3 pass over an alpha mask: the maximum ({@code grow}) or minimum of each neighbourhood. */
    static int[] spread(int[] in, int w, int h, boolean grow) {
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int best = grow ? 0 : 255;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.max(0, Math.min(h - 1, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int v = in[yy * w + Math.max(0, Math.min(w - 1, x + dx))];
                        best = grow ? Math.max(best, v) : Math.min(best, v);
                    }
                }
                out[y * w + x] = best;
            }
        }
        return out;
    }

    private static float lum(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    /** A blurred, faded bitmap of {@code art} for a band of {@code aspect} (width / height). */
    static Bitmap from(Drawable art, float aspect) {
        if (art == null || art.getConstantState() == null || aspect <= 0) return null;
        int smallH = Math.max(1, Math.round(SMALL_W / aspect));
        Bitmap small = null, mid = null, big = null;
        try {
            Drawable copy = art.getConstantState().newDrawable().mutate();
            small = Bitmap.createBitmap(SMALL_W, smallH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.drawColor(Color.BLACK);
            // Centre-crop the banner into the band's shape.
            int iw = Math.max(1, copy.getIntrinsicWidth()), ih = Math.max(1, copy.getIntrinsicHeight());
            float scale = Math.max(SMALL_W / (float) iw, smallH / (float) ih);
            int w = Math.round(iw * scale), h = Math.round(ih * scale);
            int left = (SMALL_W - w) / 2, top = (smallH - h) / 2;
            copy.setBounds(left, top, left + w, top + h);
            copy.draw(c);
            blur(small, BLUR_RADIUS, BLUR_PASSES);

            Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            mid = Bitmap.createScaledBitmap(small, SMALL_W * 4, smallH * 4, true);
            int outH = Math.max(1, Math.round(OUT_W / aspect));
            Bitmap out = Bitmap.createBitmap(OUT_W, outH, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(out);
            big = Bitmap.createScaledBitmap(mid, OUT_W, outH, true);
            oc.drawBitmap(big, 0, 0, smooth);
            // Darken so the app name stays readable on bright art.
            oc.drawColor(0x66000000);
            // Fade out: opaque at the top, gone at the bottom.
            Paint mask = new Paint();
            mask.setShader(new LinearGradient(0, 0, 0, outH,
                    new int[] {0xFF000000, 0xCC000000, 0x00000000},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            oc.drawRect(0, 0, OUT_W, outH, mask);
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (small != null) small.recycle();
            if (mid != null) mid.recycle();
            if (big != null) big.recycle();
        }
    }
}
