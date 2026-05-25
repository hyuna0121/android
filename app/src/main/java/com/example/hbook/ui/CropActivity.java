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
 * 이미지 한 장을 받아 꼭짓점 지정 UI를 보여주고 결과를 돌려주는 액티비티.
 *
 * Intent extras (입력):
 *   EXTRA_IMAGE_PATH  (String)  - 원본 이미지 파일 경로
 *   EXTRA_PAGE_LABEL  (String)  - 상단 "1 / 3" 형태의 뱃지 텍스트
 *
 * Result extras (출력, RESULT_OK):
 *   EXTRA_IMAGE_PATH  (String)  - 입력과 동일하게 그대로 반환
 *   EXTRA_CORNERS     (String)  - "x0,y0,x1,y1,x2,y2,x3,y3" (원본 픽셀 좌표)
 */
public class CropActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "image_path";
    public static final String EXTRA_PAGE_LABEL = "page_label";
    public static final String EXTRA_CORNERS    = "corners";

    private CropView cropView;
    private String   imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        String pageLabel = getIntent().getStringExtra(EXTRA_PAGE_LABEL);

        if (imagePath == null) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cropView = findViewById(R.id.crop_view);
        TextView tvBadge   = findViewById(R.id.tv_page_badge);
        TextView tvCancel  = findViewById(R.id.tv_cancel);
        TextView tvReset   = findViewById(R.id.tv_reset);
        TextView btnRetake = findViewById(R.id.btn_retake);
        TextView btnConfirm= findViewById(R.id.btn_confirm);

        // ── 뱃지 ──────────────────────────────────────────────────────
        if (pageLabel != null) tvBadge.setText(pageLabel);

        // ── 이미지 로드 ───────────────────────────────────────────────
        loadBitmap();

        // ── 버튼 ──────────────────────────────────────────────────────
        tvCancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        tvReset.setOnClickListener(v -> cropView.resetCorners());

        btnRetake.setOnClickListener(v -> {
            // 다시 찍기 = RESULT_CANCELED 로 돌아가면
            // CameraActivity 에서 해당 파일을 버리고 카메라를 다시 열게 처리
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

            // 좌표를 문자열로 직렬화해서 반환
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

    /** 파일에서 Bitmap 로드 + EXIF 회전 보정 */
    private void loadBitmap() {
        File file = new File(imagePath);
        if (!file.exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 1단계: 크기만 읽기
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagePath, opts);

        // 2단계: 화면에 맞게 축소 비율 결정
        int targetW = 2048;
        int targetH = 2048;
        int sampleSize = 1;
        if (opts.outWidth > targetW || opts.outHeight > targetH) {
            sampleSize = Math.max(opts.outWidth / targetW, opts.outHeight / targetH);
        }

        // 3단계: 실제 디코딩
        opts.inJustDecodeBounds = false;
        opts.inSampleSize       = sampleSize;
        Bitmap bmp = BitmapFactory.decodeFile(imagePath, opts);

        if (bmp == null) {
            Toast.makeText(this, "이미지 디코딩 실패", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 4단계: EXIF 회전 정보 읽어서 올바른 방향으로 회전
        bmp = rotateBitmapByExif(bmp, imagePath);

        cropView.setBitmap(bmp);
    }

    /**
     * EXIF orientation 태그를 읽어 Bitmap을 올바른 방향으로 회전합니다.
     * 카메라 앱이 세로로 찍어도 JPEG 픽셀은 가로로 저장하고
     * EXIF에 "90도 회전" 메타데이터를 기록하는 경우가 많기 때문에 필요합니다.
     */
    private Bitmap rotateBitmapByExif(Bitmap bmp, String path) {
        int rotation = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  rotation =  90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: rotation = 0; break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (rotation == 0) return bmp;

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        bmp.recycle();
        return rotated;
    }
}
