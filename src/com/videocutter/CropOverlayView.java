package com.videocutter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draggable crop rectangle over the video preview.
 * Outside the box is dimmed; corners and edges resize; the interior moves the box.
 */
public class CropOverlayView extends View {

    private enum DragMode {
        NONE, MOVE,
        LEFT, RIGHT, TOP, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    /** Active crop in view coordinates (stays inside contentBounds). */
    private final RectF cropRect = new RectF();

    /** Area that maps to the actual video frame (the letterboxed region). */
    private final RectF contentBounds = new RectF();

    private boolean enabledCrop = false;
    private DragMode dragMode = DragMode.NONE;
    private float lastX = 0f;
    private float lastY = 0f;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float handleRadius;
    private final float hitSlop;
    private final float minSize;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;

        dimPaint.setColor(0x99000000);
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f * density);

        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);

        handleRadius = 10f * density;
        hitSlop = 24f * density;
        minSize = 48f * density;
    }

    public void setCropEnabled(boolean enabled) {
        enabledCrop = enabled;
        setVisibility(enabled ? VISIBLE : GONE);
        if (enabled && cropRect.isEmpty() && contentBounds.width() > 0f) {
            resetCropToContent();
        }
        invalidate();
    }

    public boolean isCropEnabled() {
        return enabledCrop;
    }

    public void setContentBounds(float left, float top, float right, float bottom) {
        contentBounds.set(left, top, right, bottom);
        if (enabledCrop) {
            if (cropRect.isEmpty()) {
                resetCropToContent();
            } else {
                // Keep the crop inside the new content bounds
                cropRect.left = clamp(cropRect.left, contentBounds.left, contentBounds.right - minSize);
                cropRect.top = clamp(cropRect.top, contentBounds.top, contentBounds.bottom - minSize);
                cropRect.right = clamp(cropRect.right, cropRect.left + minSize, contentBounds.right);
                cropRect.bottom = clamp(cropRect.bottom, cropRect.top + minSize, contentBounds.bottom);
            }
        }
        invalidate();
    }

    public void resetCropToContent() {
        if (contentBounds.width() <= 0f || contentBounds.height() <= 0f) return;
        // Default to roughly 80 percent, centered
        float insetX = contentBounds.width() * 0.1f;
        float insetY = contentBounds.height() * 0.1f;
        cropRect.set(
                contentBounds.left + insetX,
                contentBounds.top + insetY,
                contentBounds.right - insetX,
                contentBounds.bottom - insetY);
        invalidate();
    }

    /**
     * Crop as fractions of the displayed video frame, origin at the top left.
     * Returns {left, top, right, bottom} in the range 0..1, or null if the crop is the full frame
     * (or cropping is off).
     */
    public float[] getCropFractions() {
        if (!enabledCrop || contentBounds.width() <= 0f || contentBounds.height() <= 0f) return null;
        boolean almostFull =
                cropRect.left <= contentBounds.left + 2f
                        && cropRect.top <= contentBounds.top + 2f
                        && cropRect.right >= contentBounds.right - 2f
                        && cropRect.bottom >= contentBounds.bottom - 2f;
        if (almostFull) return null;

        float l = clamp((cropRect.left - contentBounds.left) / contentBounds.width(), 0f, 1f);
        float r = clamp((cropRect.right - contentBounds.left) / contentBounds.width(), 0f, 1f);
        float t = clamp((cropRect.top - contentBounds.top) / contentBounds.height(), 0f, 1f);
        float b = clamp((cropRect.bottom - contentBounds.top) / contentBounds.height(), 0f, 1f);
        return new float[]{l, t, r, b};
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabledCrop || cropRect.isEmpty()) return;

        // Dim outside the crop using an even-odd path
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
        path.addRect(cropRect, Path.Direction.CW);
        canvas.drawPath(path, dimPaint);

        canvas.drawRect(cropRect, borderPaint);

        // Rule-of-thirds grid
        float w3 = cropRect.width() / 3f;
        float h3 = cropRect.height() / 3f;
        canvas.drawLine(cropRect.left + w3, cropRect.top, cropRect.left + w3, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left + 2 * w3, cropRect.top, cropRect.left + 2 * w3, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + h3, cropRect.right, cropRect.top + h3, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + 2 * h3, cropRect.right, cropRect.top + 2 * h3, gridPaint);

        // Corner handles
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadius, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadius, handlePaint);
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadius, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabledCrop) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragMode = hitTest(event.getX(), event.getY());
                lastX = event.getX();
                lastY = event.getY();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(dragMode != DragMode.NONE);
                }
                return dragMode != DragMode.NONE;

            case MotionEvent.ACTION_MOVE:
                if (dragMode == DragMode.NONE) return false;
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                applyDrag(dx, dy);
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DragMode.NONE;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private static boolean near(float px, float py, float qx, float qy, float slop) {
        return (float) Math.hypot(px - qx, py - qy) <= slop;
    }

    private DragMode hitTest(float x, float y) {
        float l = cropRect.left;
        float t = cropRect.top;
        float r = cropRect.right;
        float b = cropRect.bottom;

        if (near(x, y, l, t, hitSlop)) return DragMode.TOP_LEFT;
        if (near(x, y, r, t, hitSlop)) return DragMode.TOP_RIGHT;
        if (near(x, y, l, b, hitSlop)) return DragMode.BOTTOM_LEFT;
        if (near(x, y, r, b, hitSlop)) return DragMode.BOTTOM_RIGHT;
        if (Math.abs(x - l) <= hitSlop && y >= t - hitSlop && y <= b + hitSlop) return DragMode.LEFT;
        if (Math.abs(x - r) <= hitSlop && y >= t - hitSlop && y <= b + hitSlop) return DragMode.RIGHT;
        if (Math.abs(y - t) <= hitSlop && x >= l - hitSlop && x <= r + hitSlop) return DragMode.TOP;
        if (Math.abs(y - b) <= hitSlop && x >= l - hitSlop && x <= r + hitSlop) return DragMode.BOTTOM;
        if (cropRect.contains(x, y)) return DragMode.MOVE;
        return DragMode.NONE;
    }

    private void applyDrag(float dx, float dy) {
        RectF c = contentBounds;
        switch (dragMode) {
            case MOVE: {
                float nl = cropRect.left + dx;
                float nt = cropRect.top + dy;
                float nr = cropRect.right + dx;
                float nb = cropRect.bottom + dy;
                float w = cropRect.width();
                float h = cropRect.height();
                if (nl < c.left) { nl = c.left; nr = nl + w; }
                if (nr > c.right) { nr = c.right; nl = nr - w; }
                if (nt < c.top) { nt = c.top; nb = nt + h; }
                if (nb > c.bottom) { nb = c.bottom; nt = nb - h; }
                cropRect.set(nl, nt, nr, nb);
                break;
            }
            case LEFT:
                dragLeft(dx, c);
                break;
            case RIGHT:
                dragRight(dx, c);
                break;
            case TOP:
                dragTop(dy, c);
                break;
            case BOTTOM:
                dragBottom(dy, c);
                break;
            case TOP_LEFT:
                dragLeft(dx, c);
                dragTop(dy, c);
                break;
            case TOP_RIGHT:
                dragRight(dx, c);
                dragTop(dy, c);
                break;
            case BOTTOM_LEFT:
                dragLeft(dx, c);
                dragBottom(dy, c);
                break;
            case BOTTOM_RIGHT:
                dragRight(dx, c);
                dragBottom(dy, c);
                break;
            default:
                break;
        }
    }

    private void dragLeft(float dx, RectF c) {
        cropRect.left = Math.min(cropRect.right - minSize, Math.max(c.left, cropRect.left + dx));
    }

    private void dragRight(float dx, RectF c) {
        cropRect.right = Math.max(cropRect.left + minSize, Math.min(c.right, cropRect.right + dx));
    }

    private void dragTop(float dy, RectF c) {
        cropRect.top = Math.min(cropRect.bottom - minSize, Math.max(c.top, cropRect.top + dy));
    }

    private void dragBottom(float dy, RectF c) {
        cropRect.bottom = Math.max(cropRect.top + minSize, Math.min(c.bottom, cropRect.bottom + dy));
    }
}
