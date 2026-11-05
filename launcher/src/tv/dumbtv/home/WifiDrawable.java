package tv.dumbtv.home;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Four rising bars for the Wi-Fi chip: {@code bars} of them lit, the rest faint. */
final class WifiDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final int w, h;
    private int bars;
    private int color = 0xFFFFFFFF;
    private ColorStateList colors;

    WifiDrawable(int width, int height) {
        w = width;
        h = height;
    }

    /** Follows the chip's text colour, which flips when the chip takes focus. */
    void setColors(ColorStateList c) {
        colors = c;
        onStateChange(getState());
    }

    @Override
    public boolean isStateful() {
        return colors != null && colors.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (colors == null) return false;
        int next = colors.getColorForState(state, color);
        if (next == color) return false;
        color = next;
        invalidateSelf();
        return true;
    }

    /** 0 to 4; 0 draws every bar faint, for offline. */
    void setBars(int n) {
        bars = Math.max(0, Math.min(4, n));
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float gap = b.width() / 10f, bw = (b.width() - gap * 3) / 4f, r = bw / 3f;
        for (int i = 0; i < 4; i++) {
            float top = b.bottom - b.height() * (i + 1) / 4f;
            float left = b.left + i * (bw + gap);
            bar.set(left, top, left + bw, b.bottom);
            paint.setColor(i < bars ? color : (color & 0x00FFFFFF) | 0x47000000);
            canvas.drawRoundRect(bar, r, r, paint);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return w;
    }

    @Override
    public int getIntrinsicHeight() {
        return h;
    }

    @Override
    public void setAlpha(int alpha) {
        color = (color & 0x00FFFFFF) | (alpha << 24);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
