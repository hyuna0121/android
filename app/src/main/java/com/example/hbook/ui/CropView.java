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
 *   1. setBitmap(bmp, originalWidth, originalHeight) 으로 이미지 설정
 *   2. (선택) setCorners(float[8]) 으로 자동 감지된 꼭짓점 적용
 *      → 미지정 시 resetCorners() 가 네 모서리로 초기화
 *   3. getCorners() 로 원본 픽셀 좌표 기준 꼭짓점 획득
 *
 * 좌표 순서: [0]=TL, [1]=TR, [2]=BR, [3]=BL
 */
public class CropView extends View {

    // ── 디자인 상수 ──────────────────────────────────────────────────
    private static final float HANDLE_RADIUS_DP = 22f;
    private static final float LINE_WIDTH_DP    = 2f;
    private static final float TOUCH_RADIUS_DP  = 40f;

    private static final int COLOR_HANDLE       = 0xFF4F8EF7;
    private static final int COLOR_HANDLE_LABEL = 0xFFFFFFFF;
    private static final int COLOR_LINE         = 0xFF4F8EF7;
    private static final int COLOR_DIMMED       = 0x77000000;

    // ── 상태 ─────────────────────────────────────────────────────────
    private final PointF[] pts = new PointF[4];
    private int dragIndex = -1;

    // ── 이미지 ───────────────────────────────────────────────────────
    private Bitmap        bitmap;
    private final RectF   imageRect = new RectF();
    private int           originalWidth  = 0;
    private int           originalHeight = 0;

    /**
     * 자동 감지된 초기 꼭짓점 (원본 픽셀 좌표).
     * setBitmap() 이후 layoutImage() 가 끝나면 뷰 좌표로 변환해 적용됩니다.
     * null 이면 resetCorners() 로 네 모서리 초기화.
     */
    private float[] pendingAutoCorners = null;

    // ── Paint ────────────────────────────────────────────────────────
    private final Paint paintLine   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabel  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDim    = new Paint();

    private float handleRadius;
    private float touchRadius;

    // ── 생성자 ───────────────────────────────────────────────────────
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
        handleRadius  = HANDLE_RADIUS_DP * density;
        touchRadius   = TOUCH_RADIUS_DP  * density;

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

    /**
     * 이미지를 설정합니다.
     *
     * @param bmp            화면 표시용 Bitmap (축소·회전 적용됨)
     * @param originalWidth  원본 파일의 실제 너비  (EXIF 회전 반영 후)
     * @param originalHeight 원본 파일의 실제 높이 (EXIF 회전 반영 후)
     */
    public void setBitmap(Bitmap bmp, int originalWidth, int originalHeight) {
        this.bitmap         = bmp;
        this.originalWidth  = originalWidth;
        this.originalHeight = originalHeight;
        if (getWidth() > 0) {
            layoutImage();
            applyCornersOrReset();
        }
        invalidate();
    }

    /**
     * 서버 자동 감지 결과(원본 픽셀 좌표)를 꼭짓점 초기값으로 설정합니다.
     *
     * setBitmap() 이전에 호출해도 되고 이후에 호출해도 됩니다.
     * - 이전 호출 → pendingAutoCorners 에 저장 후 layoutImage() 때 적용
     * - 이후 호출 → 즉시 뷰 좌표로 변환해 표시
     *
     * @param originalPixelCorners float[8]: x0,y0(TL), x1,y1(TR), x2,y2(BR), x3,y3(BL)
     *                             원본 파일 픽셀 좌표 기준
     */
    public void setCorners(float[] originalPixelCorners) {
        pendingAutoCorners = originalPixelCorners;
        if (bitmap != null && getWidth() > 0) {
            applyAutoCorners(originalPixelCorners);
            invalidate();
        }
        // bitmap 미설정 상태면 setBitmap() → onSizeChanged() 에서 applyCornersOrReset() 호출됨
    }

    /** 꼭짓점을 네 모서리로 초기화 */
    public void resetCorners() {
        pendingAutoCorners = null;
        if (bitmap == null) return;
        float l = imageRect.left;
        float t = imageRect.top;
        float r = imageRect.right;
        float b = imageRect.bottom;
        float m = handleRadius;
        pts[0].set(l + m, t + m); // TL
        pts[1].set(r - m, t + m); // TR
        pts[2].set(r - m, b - m); // BR
        pts[3].set(l + m, b - m); // BL
        invalidate();
    }

    /**
     * 원본 이미지 픽셀 좌표 기준 꼭짓점 반환.
     *
     * @return float[8]: x0,y0(TL), x1,y1(TR), x2,y2(BR), x3,y3(BL)  원본 픽셀 좌표
     *         bitmap 미설정 시 null
     */
    public float[] getCorners() {
        if (bitmap == null) return null;

        float displayW = imageRect.width();
        float displayH = imageRect.height();
        float scaleX   = originalWidth  / displayW;
        float scaleY   = originalHeight / displayH;

        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            result[i * 2]     = (pts[i].x - imageRect.left) * scaleX;
            result[i * 2 + 1] = (pts[i].y - imageRect.top)  * scaleY;
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────

    /**
     * pendingAutoCorners 가 있으면 뷰 좌표로 변환해 적용,
     * 없으면 resetCorners() 로 네 모서리 초기화.
     */
    private void applyCornersOrReset() {
        if (pendingAutoCorners != null) {
            applyAutoCorners(pendingAutoCorners);
        } else {
            resetCorners();
        }
    }

    /**
     * 원본 픽셀 좌표 → 뷰 표시 좌표로 변환해 pts[] 에 적용.
     */
    private void applyAutoCorners(float[] originalPixelCorners) {
        if (originalPixelCorners == null || originalPixelCorners.length < 8) {
            resetCorners();
            return;
        }

        float displayW = imageRect.width();
        float displayH = imageRect.height();
        if (displayW <= 0 || displayH <= 0 || originalWidth <= 0 || originalHeight <= 0) {
            resetCorners();
            return;
        }

        float scaleX = displayW  / originalWidth;
        float scaleY = displayH / originalHeight;

        for (int i = 0; i < 4; i++) {
            float viewX = imageRect.left + originalPixelCorners[i * 2]     * scaleX;
            float viewY = imageRect.top  + originalPixelCorners[i * 2 + 1] * scaleY;

            // 핸들이 imageRect 밖으로 나가지 않게 클램프
            viewX = Math.max(imageRect.left  + handleRadius,
                    Math.min(imageRect.right  - handleRadius, viewX));
            viewY = Math.max(imageRect.top   + handleRadius,
                    Math.min(imageRect.bottom - handleRadius, viewY));

            pts[i].set(viewX, viewY);
        }
        invalidate();
    }

    // ─────────────────────────────────────────────────────────────────
    // 레이아웃 & 드로잉
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmap != null) {
            layoutImage();
            applyCornersOrReset();
        }
    }

    private void layoutImage() {
        float vw    = getWidth();
        float vh    = getHeight();
        float bw    = bitmap.getWidth();
        float bh    = bitmap.getHeight();
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

        canvas.drawBitmap(bitmap, null, imageRect, null);

        // 사다리꼴 바깥 어둡게
        Path dimPath = new Path();
        dimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        dimPath.moveTo(pts[0].x, pts[0].y);
        dimPath.lineTo(pts[1].x, pts[1].y);
        dimPath.lineTo(pts[2].x, pts[2].y);
        dimPath.lineTo(pts[3].x, pts[3].y);
        dimPath.close();
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        canvas.drawPath(dimPath, paintDim);

        // 사다리꼴 테두리
        Path linePath = new Path();
        linePath.moveTo(pts[0].x, pts[0].y);
        linePath.lineTo(pts[1].x, pts[1].y);
        linePath.lineTo(pts[2].x, pts[2].y);
        linePath.lineTo(pts[3].x, pts[3].y);
        linePath.close();
        canvas.drawPath(linePath, paintLine);

        // 핸들
        String[] labels = {"TL", "TR", "BR", "BL"};
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(pts[i].x, pts[i].y, handleRadius, paintHandle);
            float labelY = pts[i].y + (paintLabel.getTextSize() / 3f);
            canvas.drawText(labels[i], pts[i].x, labelY, paintLabel);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 터치 처리 — 핸들 드래그
    // ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragIndex = findHandle(x, y);
                return dragIndex >= 0;

            case MotionEvent.ACTION_MOVE:
                if (dragIndex >= 0) {
                    float cx = Math.max(0f, Math.min(getWidth(),  x));
                    float cy = Math.max(0f, Math.min(getHeight(), y));
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

    private int findHandle(float x, float y) {
        for (int i = 0; i < 4; i++) {
            float dx = x - pts[i].x;
            float dy = y - pts[i].y;
            if (dx * dx + dy * dy <= touchRadius * touchRadius) return i;
        }
        return -1;
    }
}
