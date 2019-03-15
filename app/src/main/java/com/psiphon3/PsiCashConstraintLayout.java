package com.psiphon3;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.support.constraint.ConstraintLayout;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class PsiCashConstraintLayout extends ConstraintLayout {
    public PsiCashConstraintLayout(Context context) {
        super(context);
    }

    public PsiCashConstraintLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PsiCashConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        int offset = 40;
        int notchX = (int)(canvas.getWidth() / 3.5f);
        int leftX = offset;
        int topY = offset;
        int rightX = canvas.getWidth() - offset;
        int bottomY = canvas.getHeight() - offset;
        int strokeWidth = 8;

        TextView tv = new TextView(getContext());
        tv.setText("0");
        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(Resources.getSystem().getDisplayMetrics().widthPixels , MeasureSpec.AT_MOST);
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        tv.measure(widthMeasureSpec, heightMeasureSpec);

        int notchY = tv.getMeasuredHeight();

        Paint paint = new Paint();
        // Line color
        int color = Color.argb(255, 77, 77, 77 );
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        // Line width in pixels
        paint.setStrokeWidth(strokeWidth);
        paint.setAntiAlias(true);

        CornerPathEffect corEffect = new CornerPathEffect(50f);
        paint.setPathEffect(corEffect);

        /*
            6                        5
            +-----------------------+
         7  |                       |  4
   8 +------+                       +--------+ 3
     |                                       |
     |                                       |
     |                                       |
     |                                       |
     |                                       |
     |                                       |
     |                                       |
     |                                       |
     |                                       |
     +---------------------------------------+
    1                                         2
    */

        // draw the bottom open rectangle shape with rounded corners
        Path path = new Path();
        path.moveTo(leftX + notchX, topY + notchY); //7
        path.lineTo(leftX, topY + notchY); //8
        path.lineTo(leftX, bottomY); //1
        path.lineTo(rightX, bottomY); //2
        path.lineTo(rightX, topY + notchY); //3
        path.lineTo(rightX - notchX, topY + notchY); //4
        canvas.drawPath(path, paint);

        // draw the top open rectangle shape with rounded corners
        // extend (5,4) and (6,7) legs to account for the stroke width
        path = new Path();
        path.moveTo(rightX - notchX, topY + notchY + strokeWidth / 2); //4
        path.lineTo(rightX - notchX, topY); //5
        path.lineTo(leftX + notchX, topY); //6
        path.lineTo(leftX + notchX, topY + notchY + strokeWidth / 2); //7
        canvas.drawPath(path, paint);

        super.dispatchDraw(canvas);

    }

    private static int getTextViewHeight(TextView textView) {
        WindowManager wm =
                (WindowManager) textView.getContext().getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();

        int deviceWidth;

        if(android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2){
            Point size = new Point();
            display.getSize(size);
            deviceWidth = size.x;
        } else {
            deviceWidth = display.getWidth();
        }

        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(deviceWidth, View.MeasureSpec.AT_MOST);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        textView.measure(widthMeasureSpec, heightMeasureSpec);
        return textView.getMeasuredHeight();
    }
}
