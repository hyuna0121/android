package com.example.hbook.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hbook.R;
import com.example.hbook.model.CapturedItem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 스캔 전 미리보기 화면.
 *
 * 기존: WebView 로 서버 HTML 렌더링 (실험용)
 * 변경: 썸네일 리스트 + 각 항목 탭 → CropActivity 로 영역 수정 가능
 *
 * Intent extras (입력):
 *   EXTRA_IMAGE_PATHS   String[]  — 이미지 경로 배열
 *   EXTRA_CORNERS_LIST  String[]  — 각 이미지 corners 문자열 배열
 *   EXTRA_AUTO_FLAGS    boolean[] — 각 이미지가 자동 감지됐는지 여부
 *
 * Intent extras (출력, RESULT_OK 시):
 *   EXTRA_UPDATED_CORNERS  String[] — 수정 완료 후 corners 배열
 *                                     (수정 없으면 입력과 동일)
 */
public class PreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATHS     = "image_paths";
    public static final String EXTRA_CORNERS_LIST    = "corners_list";
    public static final String EXTRA_AUTO_FLAGS      = "auto_flags";     // ← 신규
    public static final String EXTRA_UPDATED_CORNERS = "updated_corners"; // ← 신규 (출력)

    private RecyclerView     recyclerView;
    private PreviewAdapter   adapter;
    private LinearLayout     loadingView;

    // 현재 수정 중인 이미지 인덱스 (CropActivity 결과 반영 시 사용)
    private int editingIndex = -1;

    // 데이터 (수정 가능하므로 배열 복사본 보유)
    private String[]  imagePaths;
    private String[]  cornersList;
    private boolean[] autoFlags;

    // ── CropActivity 런처 (미리보기에서 영역 수정) ───────────────────
    private final ActivityResultLauncher<Intent> editCropLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null
                                && editingIndex >= 0) {

                            String newCorners = result.getData()
                                    .getStringExtra(CropActivity.EXTRA_CORNERS);
                            if (newCorners != null) {
                                cornersList[editingIndex] = newCorners;
                                autoFlags[editingIndex]   = false; // 수동 수정 완료
                                adapter.notifyItemChanged(editingIndex);
                                Toast.makeText(this, (editingIndex + 1) + "번 사진 영역이 수정됐어요.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                        editingIndex = -1;
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview_new); // 새 레이아웃 사용

        recyclerView = findViewById(R.id.recycler_preview);
        loadingView  = findViewById(R.id.loading_view);
        TextView tvClose    = findViewById(R.id.tv_close);
        TextView btnConfirm = findViewById(R.id.btn_confirm);

        // ── 데이터 수신 ─────────────────────────────────────────────
        imagePaths  = getIntent().getStringArrayExtra(EXTRA_IMAGE_PATHS);
        String[] inputCorners = getIntent().getStringArrayExtra(EXTRA_CORNERS_LIST);
        boolean[] inputFlags  = getIntent().getBooleanArrayExtra(EXTRA_AUTO_FLAGS);

        if (imagePaths == null || imagePaths.length == 0) {
            Toast.makeText(this, "전달된 이미지가 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 방어적 복사 (수정 가능하게)
        cornersList = inputCorners != null
                ? Arrays.copyOf(inputCorners, imagePaths.length)
                : new String[imagePaths.length];
        autoFlags   = inputFlags != null
                ? Arrays.copyOf(inputFlags, imagePaths.length)
                : new boolean[imagePaths.length];

        // ── RecyclerView ────────────────────────────────────────────
        adapter = new PreviewAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // ── 버튼 ────────────────────────────────────────────────────
        tvClose.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        btnConfirm.setOnClickListener(v -> {
            // 수정된 corners 를 CameraActivity 로 돌려줌
            Intent result = new Intent();
            result.putExtra(EXTRA_UPDATED_CORNERS, cornersList);
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // 특정 이미지 영역 수정 → CropActivity 실행
    // ─────────────────────────────────────────────────────────────────

    private void openEditCrop(int index) {
        editingIndex = index;
        String label = (index + 1) + " / " + imagePaths.length + "  •  영역 수정";

        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, imagePaths[index]);
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, label);

        // 현재 corners 를 자동 감지 초기값으로 전달 (수정 전 상태로 시작)
        String currentCorners = cornersList[index];
        if (currentCorners != null && !currentCorners.isEmpty()) {
            intent.putExtra(CropActivity.EXTRA_AUTO_CORNERS, currentCorners);
        }

        editCropLauncher.launch(intent);
    }

    // ─────────────────────────────────────────────────────────────────
    // RecyclerView Adapter
    // ─────────────────────────────────────────────────────────────────

    private class PreviewAdapter
            extends RecyclerView.Adapter<PreviewAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_preview_image, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return imagePaths != null ? imagePaths.length : 0;
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            ImageView imgThumb;
            TextView  tvLabel;
            TextView  tvStatus;
            TextView  tvEdit;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                imgThumb = itemView.findViewById(R.id.img_thumb);
                tvLabel  = itemView.findViewById(R.id.tv_label);
                tvStatus = itemView.findViewById(R.id.tv_status);
                tvEdit   = itemView.findViewById(R.id.tv_edit);
            }

            void bind(int position) {
                // ── 레이블 ──────────────────────────────────────────
                tvLabel.setText((position + 1) + "번 사진");

                // ── 자동/수동 상태 배지 ──────────────────────────────
                if (autoFlags[position]) {
                    tvStatus.setText("자동 인식됨");
                    tvStatus.setTextColor(0xFF4F8EF7);   // 파란색
                } else {
                    tvStatus.setText("직접 지정");
                    tvStatus.setTextColor(0xFF888888);   // 회색
                }

                // ── 썸네일 (비동기 로드) ─────────────────────────────
                imgThumb.setImageBitmap(null);
                loadThumb(imagePaths[position], imgThumb);

                // ── "수정" 버튼 ─────────────────────────────────────
                tvEdit.setOnClickListener(v -> openEditCrop(position));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 썸네일 로드 (간단한 백그라운드 처리)
    // ─────────────────────────────────────────────────────────────────

    private void loadThumb(String path, ImageView target) {
        new Thread(() -> {
            Bitmap thumb = decodeSampledBitmap(path, 200, 200);
            if (thumb != null) {
                runOnUiThread(() -> target.setImageBitmap(thumb));
            }
        }).start();
    }

    private static Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        int sampleSize = 1;
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            sampleSize = Math.max(
                    Math.round((float) opts.outHeight / reqH),
                    Math.round((float) opts.outWidth  / reqW));
        }

        // EXIF 회전
        int rotation = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            int ori = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if      (ori == ExifInterface.ORIENTATION_ROTATE_90)  rotation = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (IOException ignored) {}

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        Bitmap bmp = BitmapFactory.decodeFile(path, decodeOpts);
        if (bmp == null) return null;

        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(
                    bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            bmp.recycle();
            return rotated;
        }
        return bmp;
    }
}
