package com.oriontv.plus.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.oriontv.plus.Ui;

/**
 * 播放进度条：显示播放进度与缓冲进度。
 * 触屏 / 鼠标：按下或拖动即跳转进度；遥控器方向键由播放页统一处理。
 */
public class TvSeekBar extends View {

    /** 拖动进度的回调 */
    public interface OnSeekListener {
        void onSeekStart();

        void onSeeking(float fraction);

        void onSeekEnd(float fraction);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bufferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float progress = 0f;
    private float buffered = 0f;
    private boolean dragging = false;
    private OnSeekListener seekListener;

    public TvSeekBar(Context context) {
        this(context, null);
    }

    public TvSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(0xFF33414D);
        bufferPaint.setColor(0x66A7B3BF);
        progressPaint.setColor(0xFF22C55E);
        thumbPaint.setColor(0xFF22C55E);
        setFocusable(true);
        // 不设置 focusableInTouchMode：触屏可直接拖动进度，TV 端仍可方向键聚焦
    }

    public void setOnSeekListener(OnSeekListener l) {
        seekListener = l;
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    public void setBuffered(float p) {
        buffered = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getWidth() <= 0) {
            return false;
        }
        float fraction = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                setProgress(fraction);
                if (seekListener != null) {
                    seekListener.onSeekStart();
                    seekListener.onSeeking(fraction);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    setProgress(fraction);
                    if (seekListener != null) {
                        seekListener.onSeeking(fraction);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    setProgress(fraction);
                    if (seekListener != null) {
                        seekListener.onSeekEnd(fraction);
                    }
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        boolean active = isFocused() || dragging;
        float barH = active ? Ui.dp(getContext(), 7) : Ui.dp(getContext(), 4);
        float cy = h / 2f;
        float top = cy - barH / 2f;
        float radius = barH / 2f;

        rect.set(0, top, w, top + barH);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        if (buffered > 0.001f) {
            rect.set(0, top, w * buffered, top + barH);
            canvas.drawRoundRect(rect, radius, radius, bufferPaint);
        }

        if (progress > 0.001f) {
            rect.set(0, top, Math.max(barH, w * progress), top + barH);
            canvas.drawRoundRect(rect, radius, radius, progressPaint);
        }

        if (active) {
            float cx = Math.max(Ui.dp(getContext(), 8), Math.min(w - Ui.dp(getContext(), 8), w * progress));
            canvas.drawCircle(cx, cy, Ui.dp(getContext(), dragging ? 10 : 8), thumbPaint);
        }
    }
}
