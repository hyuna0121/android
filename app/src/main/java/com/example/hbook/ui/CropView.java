package com.example.hbook.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 이미지 위에 4개의 드래그 가능한 꼭짓점 핸들을 표시하는 커스텀 View.
 *
 * 사용법:
 *   1. setBitmap() 으로 원본 이미지 설정
 *   2. getCorners() 로 원본 픽셀 좌표 기준 꼭짓점 획득
 *
 * 좌표 순서: [0]=TL, [1]=TR, [2]=BR, [3]=BL
 */
public class CropView extends View {

    // ── 디자인 상수 ──────────────────────────────────────────────────
    private static final float HANDLE_RADIUS_DP   = 22f;
    private static final float LINE_WIDTH_DP      = 2f;
    private static final float TOUCH_RADIUS_DP    = 40f;

    private static final int   COLOR_HANDLE       = 0xFF4F8EF7;  // 파란색
    private static final int   COLOR_HANDLE_LABEL = 0xFFFFFFFF;
    private static final int   COLOR_LINE         = 0xFF4F8EF7;
    private static final int   COLOR_DIMMED        = 0x77000000;

    // ── 상태 ─────────────────────────────────────────────────────────
    /** 뷰 좌표계 기준 꼭짓점 4개 (TL, TR, BR, BL) */
    private final PointF[] pts = new PointF[4];
    private int dragIndex = -1;   // 현재 드래그 중인 꼭짓점 인덱스 (-1 = 없음)

    // ── 이미지 ───────────────────────────────────────────────────────
    private Bitmap bitmap;
    /** 이미지가 뷰 안에 그려지는 실제 영역 */
    private final RectF imageRect = new RectF();
    private final Matrix imgMatrix = new Matrix();

    // ── Paint ────────────────────────────────────────────────────────
    private final Paint paintLine   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabel  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDim    = new Paint();

    private float handleRadius;
    private float touchRadius;

    public CropView(Context context) {
        super(context);
        init(context);
    }

    public CropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        handleRadius  = HANDLE_RADIUS_DP  * density;
        touchRadius   = TOUCH_RADIUS_DP   * density;

        paintLine.setColor(COLOR_LINE);
        paintLine.setStrokeWidth(LINE_WIDTH_DP * density);
        paintLine.setStyle(Paint.Style.STROKE);
        paintLine.setPathEffect(new DashPathEffect(new float[]{18f, 10f}, 0f));

        paintHandle.setColor(COLOR_HANDLE);
        paintHandle.setStyle(Paint.Style.FILL);

        paintLabel.setColor(COLOR_HANDLE_LABEL);
        paintLabel.setTextSize(13f * density);
        paintLabel.setTextAlign(Paint.Align.CENTER);

        paintDim.setColor(COLOR_DIMMED);
        paintDim.setStyle(Paint.Style.FILL);

        for (int i = 0; i < 4; i++) pts[i] = new PointF();
    }

    // ─────────────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────────────

    /** 이미지를 설정하고 꼭짓점을 네 모서리에 초기화 */
    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        if (getWidth() > 0) {
            layoutImage();
            resetCorners();
        }
        invalidate();
    }

    /** 꼭짓점을 네 모서리로 초기화 */
    public void resetCorners() {
        if (bitmap == null) return;
        float l = imageRect.left;
        float t = imageRect.top;
        float r = imageRect.right;
        float b = imageRect.bottom;
        // 살짝 안쪽으로 (핸들이 잘리지 않도록)
        float m = handleRadius;
        pts[0].set(l + m, t + m); // TL
        pts[1].set(r - m, t + m); // TR
        pts[2].set(r - m, b - m); // BR
        pts[3].set(l + m, b - m); // BL
        invalidate();
    }

    /**
     * 원본 이미지 픽셀 좌표 기준 꼭짓점 반환.
     * @return float[8]: x0,y0(TL), x1,y1(TR), x2,y2(BR), x3,y3(BL)
     */
    public float[] getCorners() {
        if (bitmap == null) return null;

        float scaleX = bitmap.getWidth()  / imageRect.width();
        float scaleY = bitmap.getHeight() / imageRect.height();

        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            result[i * 2]     = (pts[i].x - imageRect.left) * scaleX;
            result[i * 2 + 1] = (pts[i].y - imageRect.top)  * scaleY;
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    // 레이아웃 & 드로잉
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmap != null) {
            layoutImage();
            resetCorners();
        }
    }

    /** 이미지를 뷰 안에 비율 유지하며 꽉 채우는 영역 계산 */
    private void layoutImage() {
        float vw = getWidth();
        float vh = getHeight();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();

        float scale = Math.min(vw / bw, vh / bh);
        float iw    = bw * scale;
        float ih    = bh * scale;
        float left  = (vw - iw) / 2f;
        float top   = (vh - ih) / 2f;
        imageRect.set(left, top, left + iw, top + ih);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        // 1. 이미지 그리기
        canvas.drawBitmap(bitmap, null, imageRect, null);

        // 2. 사다리꼴 바깥 어둡게
        Path dimPath = new Path();
        dimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        dimPath.moveTo(pts[0].x, pts[0].y);
        dimPath.lineTo(pts[1].x, pts[1].y);
        dimPath.lineTo(pts[2].x, pts[2].y);
        dimPath.lineTo(pts[3].x, pts[3].y);
        dimPath.close();
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        canvas.drawPath(dimPath, paintDim);

        // 3. 사다리꼴 테두리 (점선)
        Path linePath = new Path();
        linePath.moveTo(pts[0].x, pts[0].y);
        linePath.lineTo(pts[1].x, pts[1].y);
        linePath.lineTo(pts[2].x, pts[2].y);
        linePath.lineTo(pts[3].x, pts[3].y);
        linePath.close();
        canvas.drawPath(linePath, paintLine);

        // 4. 핸들 (원 + 라벨)
        String[] labels = {"TL", "TR", "BR", "BL"};
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(pts[i].x, pts[i].y, handleRadius, paintHandle);
            float labelY = pts[i].y + (paintLabel.getTextSize() / 3f);
            canvas.drawText(labels[i], pts[i].x, labelY, paintLabel);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 터치 이벤트
    // ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragIndex = findClosestHandle(x, y);
                return dragIndex >= 0;

            case MotionEvent.ACTION_MOVE:
                if (dragIndex >= 0) {
                    // 이미지 영역 안으로 클램프
                    float cx = Math.max(imageRect.left,  Math.min(imageRect.right,  x));
                    float cy = Math.max(imageRect.top,   Math.min(imageRect.bottom, y));
                    pts[dragIndex].set(cx, cy);
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragIndex = -1;
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** 터치 위치에서 가장 가까운 핸들 인덱스를 반환. 없으면 -1 */
    private int findClosestHandle(float x, float y) {
        int   best = -1;
        float minD = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float dx = x - pts[i].x;
            float dy = y - pts[i].y;
            float d  = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < touchRadius && d < minD) {
                minD = d;
                best = i;
            }
        }
        return best;
    }
}
