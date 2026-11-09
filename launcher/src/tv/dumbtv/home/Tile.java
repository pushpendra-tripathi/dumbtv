package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

/**
 * A card drawn in one pass: banner, or icon plus name, or an input name; focus ring on top.
 * In the Cinema look a focused card lifts instead: it grows, casts a shadow, catches a light
 * gloss along its top and one sweep of light across it, and has no ring.
 */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.08f;
    private static final float CINEMA_SCALE = 1.14f;

    final Item item;
    final String kind;
    private final CharSequence title;
    private final int cardW, cardH, labelH;
    private final boolean labelAlways, labelOnFocus;
    private final float radius, stroke;
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;
    private final AfterglowTile afterglow;

    private final boolean cinema;
    private final float lift;
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix = new Matrix();
    private float sheenBand;
    /** Where the sweep of light is, 0 to 1; outside that range nothing is drawn. */
    private float sheen = -1f;
    private ValueAnimator sheenAnimation;

    Tile(Context c, Item item, String kind, CharSequence title, int cardW, int cardH,
            String labels, int accent) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.cardW = cardW;
        this.cardH = cardH;
        boolean app = Catalog.APPS.equals(kind);
        Config cfg = Config.get(c);
        cinema = cfg.cinema();
        afterglow = app && "afterglow".equals(cfg.get(Config.TILE_STYLE))
                ? new AfterglowTile(c, item.id, title, accent) : null;
        labelH = app && afterglow == null ? Ui.dp(c, 26) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        radius = Ui.dp(c, cinema ? 14 : 10);
        stroke = Ui.dp(c, afterglow == null ? 2.5f : 1f);
        lift = Ui.dp(c, 30);

        fill.setColor(app ? Ui.SURFACE : 0xFF17181B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        dot.setColor(0xFF34C759);
        inner.setColor(Ui.TEXT);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 14 : 17));
        under.setColor(Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextAlign(Paint.Align.CENTER);
        under.setTextSize(Ui.dp(c, 13));

        if (cinema) {
            // Built here, not in onSizeChanged: a card's size is fixed at construction, and a
            // focused tile can be drawn (and its sweep started) before its first layout, which
            // crashed the launcher with a null shader.
            gloss.setShader(new LinearGradient(0, 0, 0, cardH * 0.55f,
                    0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            sheenBand = cardW * 0.35f;
            sheenPaint.setShader(new LinearGradient(0, 0, sheenBand, sheenBand * 0.5f,
                    new int[] {0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            // The shadow follows the card only, not the name drawn under it.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, Tile.this.cardW, Tile.this.cardH, radius);
                }
            });
            setElevation(Ui.dp(c, 2));
        }

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        if (afterglow != null) afterglow.resize(cardW, cardH);
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().cancel();
        if (cinema) {
            float s = focused ? CINEMA_SCALE : 1f;
            animate().scaleX(s).scaleY(s)
                    .translationZ(focused ? lift : 0f)
                    .translationY(focused ? -lift / 4 : 0f)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(focused ? 200 : 160).start();
            if (focused) sweep();
            else stopSweep();
        } else if (focused) {
            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(110).start();
        } else {
            setScaleX(1f);
            setScaleY(1f);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }

    /** One sweep of light across the card, a moment after it lifts. */
    private void sweep() {
        stopSweep();
        sheenAnimation = ValueAnimator.ofFloat(0f, 1f);
        sheenAnimation.setStartDelay(120);
        sheenAnimation.setDuration(750);
        sheenAnimation.setInterpolator(new DecelerateInterpolator());
        sheenAnimation.addUpdateListener(a -> {
            sheen = (Float) a.getAnimatedValue();
            invalidate();
        });
        sheenAnimation.start();
    }

    private void stopSweep() {
        if (sheenAnimation != null) {
            sheenAnimation.cancel();
            sheenAnimation = null;
        }
        sheen = -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (afterglow != null) {
            afterglow.draw(canvas, icon, cardW, cardH);
        } else if (banner != null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.draw(canvas);
        } else if (icon != null) {
            int size = cardH * 46 / 100;
            int left = (cardW - size) / 2, top = cardH * 12 / 100;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            drawLine(canvas, inner, cardW / 2f, cardH - cardH * 13 / 100f);
        } else if (!Catalog.APPS.equals(kind)) {
            drawLine(canvas, inner, cardW / 2f, cardH / 2f - (inner.ascent() + inner.descent()) / 2);
        }
        if (cinema && isFocused() && !moving) {
            canvas.drawRect(card, gloss);
            if (sheen >= 0f && sheen <= 1f) {
                float x = -sheenBand * 2 + sheen * (cardW + sheenBand * 3);
                sheenMatrix.setTranslate(x, 0);
                sheenPaint.getShader().setLocalMatrix(sheenMatrix);
                canvas.drawRect(card, sheenPaint);
            }
        }
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        if (afterglow != null) afterglow.edge(canvas, cardW, cardH, radius);

        if (item.state == TvInputManager.INPUT_STATE_CONNECTED) {
            float r = Ui.dp(getContext(), 3.5f);
            canvas.drawCircle(cardW - r * 3, r * 3, r, dot);
        }
        // Cinema shows focus by lifting the card; the ring stays for moving a tile.
        if (moving || isFocused() && !cinema) {
            float half = stroke / 2;
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {stroke * 3, stroke * 2}, 0) : null);
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, ring);
        }
        if (moving) drawArrows(canvas);
        boolean label = banner != null && (labelAlways || labelOnFocus && isFocused());
        if (labelH > 0 && label) {
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 6));
        }
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline) {
        float room = cardW - Ui.dp(getContext(), 16);
        CharSequence s = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        inner.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(pad, mid);
        arrow.lineTo(pad + w, mid - h / 2);
        arrow.lineTo(pad + w, mid + h / 2);
        arrow.close();
        arrow.moveTo(cardW - pad, mid);
        arrow.lineTo(cardW - pad - w, mid - h / 2);
        arrow.lineTo(cardW - pad - w, mid + h / 2);
        arrow.close();
        canvas.drawPath(arrow, inner);
    }
}
