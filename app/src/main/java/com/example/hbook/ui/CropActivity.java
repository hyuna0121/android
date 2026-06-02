package com.example.hbook.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hbook.R;

import java.io.File;
import java.io.IOException;

/**
 * 이미지 위에서 4개 꼭짓점을 지정(또는 확인)하는 화면.
 *
 * Intent extras (입력):
 *   EXTRA_IMAGE_PATH   (String)  — 이미지 파일 경로 (필수)
 *   EXTRA_PAGE_LABEL   (String)  — 상단 뱃지 텍스트 (예: "2 / 5  •  확인 필요")
 *   EXTRA_AUTO_CORNERS (String)  — 서버 자동 감지 꼭짓점 "x0,y0,...,x3,y3" (선택)
 *                                  있으면 해당 위치로 초기화, 없으면 네 모서리 초기화
 *
 * Intent extras (출력):
 *   EXTRA_IMAGE_PATH   (String)  — 입력과 동일
 *   EXTRA_CORNERS      (String)  — 최종 확정 꼭짓점 문자열
 */
public class CropActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH   = "image_path";
    public static final String EXTRA_PAGE_LABEL   = "page_label";
    public static final String EXTRA_AUTO_CORNERS = "auto_corners"; // ← 신규
    public static final String EXTRA_CORNERS      = "corners";

    private CropView cropView;
    private String   imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        imagePath              = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        String pageLabel       = getIntent().getStringExtra(EXTRA_PAGE_LABEL);
        String autoCornersStr  = getIntent().getStringExtra(EXTRA_AUTO_CORNERS); // ← 신규

        if (imagePath == null) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cropView = findViewById(R.id.crop_view);
        TextView tvBadge    = findViewById(R.id.tv_page_badge);
        TextView tvCancel   = findViewById(R.id.tv_cancel);
        TextView tvReset    = findViewById(R.id.tv_reset);
        TextView btnRetake  = findViewById(R.id.btn_retake);
        TextView btnConfirm = findViewById(R.id.btn_confirm);

        if (pageLabel != null) tvBadge.setText(pageLabel);

        // ── 자동 감지 꼭짓점이 있으면 CropView 에 미리 설정 ──────────
        // loadBitmap() 보다 먼저 호출해도 됩니다.
        // CropView.setCorners() 내부에서 bitmap 준비 후 자동 적용합니다.
        if (autoCornersStr != null && !autoCornersStr.isEmpty()) {
            float[] autoCorners = parseCorners(autoCornersStr);
            if (autoCorners != null) {
                cropView.setCorners(autoCorners);
            }
        }

        loadBitmap();

        tvCancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // 초기화: 자동 감지 꼭짓점이 있으면 그 위치로, 없으면 네 모서리로 초기화
        tvReset.setOnClickListener(v -> {
            if (autoCornersStr != null && !autoCornersStr.isEmpty()) {
                float[] autoCorners = parseCorners(autoCornersStr);
                if (autoCorners != null) {
                    cropView.setCorners(autoCorners);
                    return;
                }
            }
            cropView.resetCorners();
        });

        btnRetake.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra("retake", true);
            setResult(Activity.RESULT_CANCELED, result);
            finish();
        });

        btnConfirm.setOnClickListener(v -> {
            float[] corners = cropView.getCorners();
            if (corners == null) {
                Toast.makeText(this, "이미지를 불러오는 중입니다.", Toast.LENGTH_SHORT).show();
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) sb.append(",");
                sb.append(Math.round(corners[i]));
            }

            Intent result = new Intent();
            result.putExtra(EXTRA_IMAGE_PATH, imagePath);
            result.putExtra(EXTRA_CORNERS, sb.toString());
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // Bitmap 로드 (EXIF 회전 반영)
    // ─────────────────────────────────────────────────────────────────

    private void loadBitmap() {
        File file = new File(imagePath);
        if (!file.exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        int rotation = getExifRotation(imagePath);

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagePath, boundsOpts);
        int rawW = boundsOpts.outWidth;
        int rawH = boundsOpts.outHeight;

        int originalWidth, originalHeight;
        if (rotation == 90 || rotation == 270) {
            originalWidth  = rawH;
            originalHeight = rawW;
        } else {
            originalWidth  = rawW;
            originalHeight = rawH;
        }

        int targetSize = 2048;
        int sampleSize = 1;
        if (rawW > targetSize || rawH > targetSize) {
            sampleSize = Math.max(rawW / targetSize, rawH / targetSize);
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        Bitmap bmp = BitmapFactory.decodeFile(imagePath, decodeOpts);

        if (bmp == null) {
            Toast.makeText(this, "이미지 디코딩 실패", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(
                    bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            bmp.recycle();
            bmp = rotated;
        }

        // setBitmap() 내부에서 pendingAutoCorners 가 있으면 자동 적용됨
        cropView.setBitmap(bmp, originalWidth, originalHeight);
    }

    private int getExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static float[] parseCorners(String s) {
        try {
            String[] parts = s.split(",");
            if (parts.length != 8) return null;
            float[] arr = new float[8];
            for (int i = 0; i < 8; i++) arr[i] = Float.parseFloat(parts[i].trim());
            return arr;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
