package com.mopub.mobileads.resource;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;

public class CloseButtonDrawable extends CircleDrawable {
    private Point centerPoint;
    private Point bottomLeftPoint;
    private Point topLeftPoint;
    private Point topRightPoint;
    private Point bottomRightPoint;
    private final Paint closeButtonPaint;
    private int mDisplacement;

    public CloseButtonDrawable() {
        super();

        closeButtonPaint = new Paint(getPaint());
        closeButtonPaint.setStrokeWidth(4.5f);
        closeButtonPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(final Canvas canvas) {
        super.draw(canvas);

        mDisplacement = (int) (0.5f * getRadius() / (float) Math.sqrt(2f));

        centerPoint = new Point(getCenterX(), getCenterY());

        bottomLeftPoint = new Point(centerPoint);
        bottomLeftPoint.offset(-mDisplacement, mDisplacement);

        topLeftPoint = new Point(centerPoint);
        topLeftPoint.offset(-mDisplacement, -mDisplacement);

        topRightPoint = new Point(centerPoint);
        topRightPoint.offset(mDisplacement, -mDisplacement);

        bottomRightPoint = new Point(centerPoint);
        bottomRightPoint.offset(mDisplacement, mDisplacement);

        canvas.drawLine(bottomLeftPoint.x, bottomLeftPoint.y, topRightPoint.x, topRightPoint.y, closeButtonPaint);
        canvas.drawLine(topLeftPoint.x, topLeftPoint.y, bottomRightPoint.x, bottomRightPoint.y, closeButtonPaint);
    }
}