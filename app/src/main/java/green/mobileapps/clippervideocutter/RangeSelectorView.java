package green.mobileapps.clippervideocutter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Two-handle range selector that replaces Material's RangeSlider.
 *
 * It is transparent apart from a dim overlay outside the selected range and small grips on the
 * two handles. It also handles scrubbing: touching away from a handle reports a playhead position
 * instead of moving a handle.
 *
 * Values are in milliseconds and map linearly onto the full width of the view.
 */
public class RangeSelectorView extends View {

    public interface Listener {
        void onRangeChanged(long startMs, long endMs, boolean fromUser);

        void onScrub(long positionMs, boolean scrubbing);
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_START = 1;
    private static final int DRAG_END = 2;
    private static final int DRAG_SCRUB = 3;

    private long durationMs = 1L;
    private long startMs = 0L;
    private long endMs = 1L;
    private long minGapMs = 1L;

    private int dragMode = DRAG_NONE;
    private Listener listener;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF gripRect = new RectF();

    private final float hitRadius;
    private final float gripWidth;
    private final float gripHeight;

    public RangeSelectorView(Context context) {
        this(context, null);
    }

    public RangeSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0x80000000);
        dimPaint.setStyle(Paint.Style.FILL);
        gripPaint.setColor(0xFFFFFFFF);
        gripPaint.setStyle(Paint.Style.FILL);
        hitRadius = 24f * density;
        gripWidth = 5f * density;
        gripHeight = 24f * density;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDuration(long ms) {
        durationMs = Math.max(1L, ms);
        startMs = Math.max(0L, Math.min(startMs, durationMs));
        endMs = Math.max(startMs, Math.min(endMs, durationMs));
        invalidate();
    }

    /** Set both handles without notifying the listener. */
    public void setValues(long start, long end) {
        startMs = Math.max(0L, Math.min(start, durationMs));
        endMs = Math.max(startMs, Math.min(end, durationMs));
        invalidate();
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    private float msToX(long ms) {
        return (float) ms / (float) durationMs * getWidth();
    }

    private long xToMs(float x) {
        int w = getWidth();
        if (w <= 0) return 0L;
        float ratio = Math.max(0f, Math.min(1f, x / w));
        return (long) (ratio * durationMs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float sx = msToX(startMs);
        float ex = msToX(endMs);
        int h = getHeight();
        int w = getWidth();

        if (sx > 0f) canvas.drawRect(0f, 0f, sx, h, dimPaint);
        if (ex < w) canvas.drawRect(ex, 0f, w, h, dimPaint);

        float top = (h - gripHeight) / 2f;
        gripRect.set(sx - gripWidth / 2f, top, sx + gripWidth / 2f, top + gripHeight);
        canvas.drawRoundRect(gripRect, gripWidth / 2f, gripWidth / 2f, gripPaint);
        gripRect.set(ex - gripWidth / 2f, top, ex + gripWidth / 2f, top + gripHeight);
        canvas.drawRoundRect(gripRect, gripWidth / 2f, gripWidth / 2f, gripPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float ds = Math.abs(x - msToX(startMs));
                float de = Math.abs(x - msToX(endMs));
                if (ds <= hitRadius && ds <= de) {
                    dragMode = DRAG_START;
                } else if (de <= hitRadius) {
                    dragMode = DRAG_END;
                } else {
                    dragMode = DRAG_SCRUB;
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                handleMove(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                handleMove(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragMode == DRAG_SCRUB && listener != null) {
                    listener.onScrub(xToMs(x), false);
                }
                dragMode = DRAG_NONE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void handleMove(float x) {
        long ms = xToMs(x);
        if (dragMode == DRAG_START) {
            long newStart = Math.max(0L, Math.min(ms, endMs - minGapMs));
            if (newStart != startMs) {
                startMs = newStart;
                invalidate();
                if (listener != null) listener.onRangeChanged(startMs, endMs, true);
            }
        } else if (dragMode == DRAG_END) {
            long newEnd = Math.min(durationMs, Math.max(ms, startMs + minGapMs));
            if (newEnd != endMs) {
                endMs = newEnd;
                invalidate();
                if (listener != null) listener.onRangeChanged(startMs, endMs, true);
            }
        } else if (dragMode == DRAG_SCRUB) {
            if (listener != null) listener.onScrub(ms, true);
        }
    }
}
