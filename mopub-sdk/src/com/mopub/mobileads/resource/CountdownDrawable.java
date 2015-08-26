package com.mopub.mobileads.resource;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.mopub.common.util.Dips;

public class CountdownDrawable extends CircleDrawable implements TextDrawable {
    private final static float TEXT_SIZE_SP = 18f;
    private final Paint mTextPaint;
    private String mSecondsRemaining;
    private final float textSizePixels;
    private Rect mTextRect;

    public CountdownDrawable(final Context context) {
        super();

        mSecondsRemaining = "";

        mTextPaint = new Paint();

        textSizePixels = Dips.dipsToFloatPixels(TEXT_SIZE_SP, context);

        mTextPaint.setTextSize(textSizePixels);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setStyle(Paint.Style.FILL);
        mTextPaint.setTextAlign(Paint.Align.LEFT);

        mTextRect = new Rect();
    }

    @Override
    public void draw(final Canvas canvas) {
        super.draw(canvas);

        final String text = String.valueOf(mSecondsRemaining);

        mTextPaint.getTextBounds(text, 0, text.length(), mTextRect);

        final int x = getCenterX() - mTextRect.width() / 2;
        final int y = getCenterY() + mTextRect.height() / 2;

        canvas.drawText(text, x, y, mTextPaint);
    }

    /**
     * TextDrawable implementation
     */

    public void updateText(final String text) {
        if (!mSecondsRemaining.equals(text)) {
            mSecondsRemaining = text;
            invalidateSelf();
        }
    }
}
