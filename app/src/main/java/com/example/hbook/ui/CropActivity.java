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
        TextView tvBadge    = findViewById(R.id.tv_page_badge);
        TextView tvCancel   = findViewById(R.id.tv_cancel);
        TextView tvReset    = findViewById(R.id.tv_reset);
        TextView btnRetake  = findViewById(R.id.btn_retake);
        TextView btnConfirm = findViewById(R.id.btn_confirm);

        if (pageLabel != null) tvBadge.setText(pageLabel);

        loadBitmap();

        tvCancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        tvReset.setOnClickListener(v -> cropView.resetCorners());

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

    private void loadBitmap() {
        File file = new File(imagePath);
        if (!file.exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // ── STEP 1: EXIF 회전 각도 먼저 읽기 ──
        int rotation = getExifRotation(imagePath);

        // ── STEP 2: 원본 파일의 실제 크기 측정 (디코딩 없이) ──
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagePath, boundsOpts);
        int rawW = boundsOpts.outWidth;   // EXIF 적용 전 픽셀 너비
        int rawH = boundsOpts.outHeight;  // EXIF 적용 전 픽셀 높이

        // EXIF 90/270도 회전이면 너비·높이가 뒤바뀜 → 회전 후 기준으로 보정
        int originalWidth, originalHeight;
        if (rotation == 90 || rotation == 270) {
            originalWidth  = rawH;  // 회전 후 너비 = 회전 전 높이
            originalHeight = rawW;  // 회전 후 높이 = 회전 전 너비
        } else {
            originalWidth  = rawW;
            originalHeight = rawH;
        }

        // ── STEP 3: 화면 표시용 Bitmap 디코딩 (inSampleSize 로 축소) ──
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

        // ── STEP 4: EXIF 회전 적용 (화면 표시용 Bitmap 에만) ──
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(
                    bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            bmp.recycle();
            bmp = rotated;
        }

        // ── STEP 5: CropView 에 표시용 Bitmap + 원본 해상도 전달 ──
        // getCorners() 는 originalWidth/Height 기준으로 좌표를 역산하므로
        // 서버로 전송되는 좌표가 원본 파일 픽셀과 1:1로 대응됨
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
}
