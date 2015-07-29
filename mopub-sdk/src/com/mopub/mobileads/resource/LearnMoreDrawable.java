package com.mopub.mobileads.resource;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;

public class LearnMoreDrawable extends CircleDrawable {

    private final Paint learnMorePaint;
    private Point centerPoint;
    private Point bottomLeftPoint;
    private Point topRightPoint;
    private Point leftBarbPoint;
    private Point rightBarbPoint;
    private int mDisplacement;
    private int mBarbLength;

    public LearnMoreDrawable() {
        super();

        learnMorePaint = new Paint(getPaint());
        learnMorePaint.setStrokeWidth(4.5f);
        learnMorePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(final Canvas canvas) {
        super.draw(canvas);

        mDisplacement = (int) (0.5f * getRadius() / Math.sqrt(2f));
        mBarbLength = (int) (1.5f * mDisplacement);

        centerPoint = new Point(getCenterX(), getCenterY());

        bottomLeftPoint = new Point(centerPoint);
        bottomLeftPoint.offset(-mDisplacement, mDisplacement);

        topRightPoint = new Point(centerPoint);
        topRightPoint.offset(mDisplacement, -mDisplacement);

        leftBarbPoint = new Point(topRightPoint);
        leftBarbPoint.offset(-mBarbLength, 0);

        rightBarbPoint = new Point(topRightPoint);
        rightBarbPoint.offset(0, mBarbLength);

        canvas.drawLine(bottomLeftPoint.x, bottomLeftPoint.y, topRightPoint.x, topRightPoint.y, learnMorePaint);
        canvas.drawLine(topRightPoint.x, topRightPoint.y, leftBarbPoint.x, leftBarbPoint.y, learnMorePaint);
        canvas.drawLine(topRightPoint.x, topRightPoint.y, rightBarbPoint.x, rightBarbPoint.y, learnMorePaint);
    }
}