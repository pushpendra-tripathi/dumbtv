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
     * The F1 race card's art: the track outline as a white line with a red glow and a dot at the
     * first point (the start line in f1-circuits), on the right of the band; the rest transparent
     * so the wallpaper shows.
     */
    static Bitmap track(double[] lon, double[] lat, float aspect) {
        if (lon == null || lat == null || lon.length < 3 || aspect <= 0) return null;
        try {
            int outH = Math.max(1, Math.round(BACKDROP_W / aspect));
            Bitmap out = Bitmap.createBitmap(BACKDROP_W, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            // Right of the band, below the chips and clear of the title and the first tile row.
            float boxL = BACKDROP_W * 0.58f, boxR = BACKDROP_W * 0.94f, boxT = outH * 0.28f, boxB = outH * 0.78f;
            float[] pts = Info.fit(lon, lat, boxL, boxT, boxR - boxL, boxB - boxT);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(pts[0], pts[1]);
            for (int i = 2; i < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
            path.close();
            float unit = BACKDROP_W / 960f;
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeJoin(Paint.Join.ROUND);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setStrokeWidth(10 * unit);
            glow.setColor(0xCCE8202A);
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(9 * unit, android.graphics.BlurMaskFilter.Blur.NORMAL));
            c.drawPath(path, glow);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeWidth(3.2f * unit);
            line.setColor(0xF2FFFFFF);
            c.drawPath(path, line);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFFFFFFFF);
            c.drawCircle(pts[0], pts[1], 4.5f * unit, dot);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
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
