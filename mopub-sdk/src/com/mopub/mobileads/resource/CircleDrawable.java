package com.mopub.mobileads.resource;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

public abstract class CircleDrawable extends Drawable {
    private final Paint mPaint;

    public CircleDrawable() {
        super();

        mPaint = new Paint();

        mPaint.setAntiAlias(true);
        mPaint.setStrokeWidth(3);
        mPaint.setColor(Color.WHITE);
        mPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void draw(final Canvas canvas) {
        canvas.drawCircle(getCenterX(), getCenterY(), getRadius(), mPaint);
    }

    @Override
    public void setAlpha(int i) {

    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return 0;
    }

    protected Paint getPaint() {
        return mPaint;
    }

    protected int getCenterX() {
        return getBounds().width() / 2;
    }

    protected int getCenterY() {
        return getBounds().height() / 2;
    }

    protected int getRadius() {
        return Math.min(getCenterX(), getCenterY());
    }
}
