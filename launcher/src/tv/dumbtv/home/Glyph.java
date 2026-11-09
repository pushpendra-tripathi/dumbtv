package tv.dumbtv.home;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The Cinema 2 look's small line icons, drawn in code: the weather (sun, cloud, rain, snow) and
 * the two buttons (sliders for Options, a gear for Settings). No icon fonts or image files, so
 * they are sharp at any size and need nothing the TV may lack.
 */
final class Glyph extends Drawable {
    static final int SUN = Info.SKY_SUN, CLOUD = Info.SKY_CLOUD, RAIN = Info.SKY_RAIN, SNOW = Info.SKY_SNOW;
    static final int SLIDERS = 4, GEAR = 5;

    private final int kind, size;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int color = 0xD9FFFFFF;
    private ColorStateList colors;

    Glyph(int kind, int size) {
        this.kind = kind;
        this.size = size;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    /** The weather glyph for a WMO code, by {@link Info#skyKind}. */
    static Glyph sky(int code, int size) {
        return new Glyph(Info.skyKind(code), size);
    }

    /** Follows a button's text colour, which flips when it takes focus. */
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

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()), x = b.exactCenterX(), y = b.exactCenterY();
        stroke.setColor(color);
        fill.setColor(color);
        stroke.setStrokeWidth(s * 0.09f);
        switch (kind) {
            case SUN:
                c.drawCircle(x, y, s * 0.2f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(x + cos * s * 0.32f, y + sin * s * 0.32f, x + cos * s * 0.44f, y + sin * s * 0.44f, stroke);
                }
                break;
            case CLOUD:
            case RAIN:
            case SNOW:
                float top = kind == CLOUD ? 0 : -s * 0.12f;
                cloud(c, x, y + top, s);
                if (kind == RAIN) {
                    for (int i = -1; i <= 1; i++) {
                        float dx = x + i * s * 0.18f;
                        c.drawLine(dx, y + s * 0.2f, dx - s * 0.05f, y + s * 0.38f, stroke);
                    }
                } else if (kind == SNOW) {
                    for (int i = -1; i <= 1; i++) c.drawCircle(x + i * s * 0.18f, y + s * 0.3f, s * 0.045f, fill);
                }
                break;
            case SLIDERS:
                for (int i = 0; i < 3; i++) {
                    float ly = y + (i - 1) * s * 0.28f, knob = x + (i == 1 ? 0.14f : -0.14f) * s;
                    c.drawLine(x - s * 0.4f, ly, x + s * 0.4f, ly, stroke);
                    c.drawCircle(knob, ly, s * 0.09f, fill);
                }
                break;
            case GEAR:
            default:
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    stroke.setStrokeWidth(s * 0.14f);
                    c.drawLine(x + cos * s * 0.28f, y + sin * s * 0.28f, x + cos * s * 0.42f, y + sin * s * 0.42f, stroke);
                }
                stroke.setStrokeWidth(s * 0.09f);
                c.drawCircle(x, y, s * 0.28f, stroke);
                c.drawCircle(x, y, s * 0.1f, stroke);
                break;
        }
    }

    /** Three humps over a flat base, as one outline. */
    private void cloud(Canvas c, float x, float y, float s) {
        path.reset();
        float base = y + s * 0.16f, left = x - s * 0.38f, right = x + s * 0.38f;
        path.moveTo(left + s * 0.1f, base);
        oval.set(left, base - s * 0.26f, left + s * 0.28f, base);
        path.arcTo(oval, 90, 180);
        oval.set(x - s * 0.2f, y - s * 0.3f, x + s * 0.16f, y + s * 0.06f);
        path.arcTo(oval, 200, 150);
        oval.set(right - s * 0.3f, base - s * 0.32f, right, base);
        path.arcTo(oval, 250, 200);
        path.close();
        c.drawPath(path, stroke);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        color = (color & 0x00FFFFFF) | (alpha << 24);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        stroke.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
