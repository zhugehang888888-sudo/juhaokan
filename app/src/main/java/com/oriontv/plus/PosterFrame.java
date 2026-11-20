package com.oriontv.plus;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 竖版海报容器：宽高比固定 1 : 1.42 */
public class PosterFrame extends FrameLayout {

    public PosterFrame(Context c) {
        super(c);
    }

    public PosterFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    public PosterFrame(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        int w = getMeasuredWidth();
        int h = Math.round(w * 1.42f);
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
