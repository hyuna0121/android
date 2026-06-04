package com.example.hbook.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.DetectCornersResponse;
import com.example.hbook.model.UserSetting;
import com.example.hbook.network.ApiClient;
import com.example.hbook.network.ApiService;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 1단계 화면: 카메라 촬영 / 갤러리 선택
 *
 * 역할:
 *   - 사진 촬영 또는 갤러리 선택 (최대 MAX_IMAGES장)
 *   - 5장 초과 시 안내 다이얼로그
 *   - "꼭짓점 판별" 버튼 → /api/detect-corners 요청
 *   - 결과 수신 후 ReviewActivity 로 이동
 */
public class CameraActivity extends AppCompatActivity {

    private static final String TAG        = "CameraActivity";
    private static final int    MAX_IMAGES = 5;

    // ── UI ──────────────────────────────────────────────────────────
    private PreviewView     viewFinder;
    private ImageCapture    imageCapture;
    private ExecutorService cameraExecutor;

    private TextView  tvBookTitle;
    private TextView  tvBack;
    private TextView  tvCount;
    private View      btnCapture;
    private ImageView btnGallery;
    private TextView  btnDetect;
    private View      loadingOverlay;
    private TextView  tvLoading;

    // ── 데이터 ───────────────────────────────────────────────────────
    private String      bookName;
    private int         currentUserId = -1;
    private UserSetting currentUserSetting;

    private final List<File> selectedFiles = new ArrayList<>();

    // ── ApiService — ApiClient 싱글턴으로 초기화 ──────────────────────
    // 기존: final 필드로 OkHttpClient + Retrofit 블록 직접 선언
    // 변경: onCreate 에서 ApiClient.getService(this) 한 줄로 교체
    private ApiService apiService;

    // ── 갤러리 런처 ─────────────────────────────────────────────────
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris == null || uris.isEmpty()) return;

                int remaining = MAX_IMAGES - selectedFiles.size();
                if (remaining <= 0) { showMaxImagesDialog(); return; }

                List<Uri> sorted = new ArrayList<>(uris);
                sorted.sort((a, b) -> getFileName(a).compareTo(getFileName(b)));

                boolean truncated = sorted.size() > remaining;
                if (truncated) sorted = sorted.subList(0, remaining);

                for (Uri uri : sorted) {
                    File f = uriToFile(uri);
                    if (f != null) selectedFiles.add(f);
                }

                if (truncated) {
                    new AlertDialog.Builder(this)
                            .setTitle("선택 제한")
                            .setMessage("최대 " + MAX_IMAGES + "장까지 선택할 수 있어요.\n"
                                    + "앞의 " + remaining + "장만 추가됐어요.")
                            .setPositiveButton("확인", null)
                            .show();
                }
                updateUI();
            });

    // ── onCreate ────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);
        bookName      = getIntent().getStringExtra("BOOK_NAME");

        viewFinder     = findViewById(R.id.viewFinder);
        tvBookTitle    = findViewById(R.id.tv_book_title);
        tvBack         = findViewById(R.id.tv_back);
        tvCount        = findViewById(R.id.tv_count);
        btnCapture     = findViewById(R.id.btn_capture);
        btnGallery     = findViewById(R.id.btn_gallery);
        btnDetect      = findViewById(R.id.btn_detect);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoading      = findViewById(R.id.tv_loading);

        if (bookName != null) tvBookTitle.setText(bookName);

        currentUserSetting = AppDatabase.getInstance(this).userDao().getUserSetting(currentUserId);
        if (currentUserSetting == null)
            currentUserSetting = new UserSetting(currentUserId);

        // ── ApiClient 싱글턴 — JWT 토큰 + ngrok 헤더 자동 첨부 ───────
        apiService = ApiClient.getService(this);

        if (allPermissionsGranted()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, 10);

        tvBack.setOnClickListener(v -> handleBackButton());
        btnCapture.setOnClickListener(v -> takePhoto());
        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        btnDetect.setOnClickListener(v -> requestDetectCorners());

        cameraExecutor = Executors.newSingleThreadExecutor();
        updateUI();
    }

    // ── 카메라 ──────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                provider.unbindAll();
                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 연결 실패", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        if (selectedFiles.size() >= MAX_IMAGES) { showMaxImagesDialog(); return; }

        File photoFile = new File(getCacheDir(),
                "temp_ocr_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        selectedFiles.add(photoFile);
                        updateUI();
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "촬영 실패: " + e.getMessage(), e);
                        Toast.makeText(CameraActivity.this, "촬영에 실패했어요.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── 꼭짓점 판별 요청 ─────────────────────────────────────────────
    private void requestDetectCorners() {
        if (selectedFiles.isEmpty()) return;
        showLoading(true, "영역 분석 중...");

        List<MultipartBody.Part> imageParts = new ArrayList<>();
        for (File f : selectedFiles) {
            RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), f);
            imageParts.add(MultipartBody.Part.createFormData("image", f.getName(), body));
        }

        apiService.detectCorners(imageParts)
                .enqueue(new Callback<DetectCornersResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DetectCornersResponse> call,
                                           @NonNull Response<DetectCornersResponse> response) {
                        showLoading(false, null);
                        // 401은 ApiClient 인터셉터가 자동 처리
                        if (response.code() == 401) return;

                        if (!response.isSuccessful() || response.body() == null
                                || !"success".equals(response.body().status)) {
                            launchReviewActivity(null);
                            return;
                        }
                        launchReviewActivity(response.body());
                    }

                    @Override
                    public void onFailure(@NonNull Call<DetectCornersResponse> call,
                                          @NonNull Throwable t) {
                        showLoading(false, null);
                        Toast.makeText(CameraActivity.this,
                                "네트워크 오류: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        launchReviewActivity(null);
                    }
                });
    }

    private void launchReviewActivity(DetectCornersResponse detectResult) {
        String[]  paths     = new String[selectedFiles.size()];
        String[]  corners   = new String[selectedFiles.size()];
        boolean[] autoFlags = new boolean[selectedFiles.size()];

        for (int i = 0; i < selectedFiles.size(); i++) {
            paths[i] = selectedFiles.get(i).getAbsolutePath();

            DetectCornersResponse.ImageResult r = null;
            if (detectResult != null && detectResult.results != null
                    && i < detectResult.results.size()) {
                r = detectResult.results.get(i);
            }

            if (r != null && r.auto_detected && r.corners != null && !r.corners.isEmpty()) {
                corners[i]   = r.corners;
                autoFlags[i] = true;
            } else {
                corners[i]   = (r != null && r.corners != null) ? r.corners : "";
                autoFlags[i] = false;
            }
        }

        Intent intent = new Intent(this, ReviewActivity.class);
        intent.putExtra(ReviewActivity.EXTRA_IMAGE_PATHS,  paths);
        intent.putExtra(ReviewActivity.EXTRA_CORNERS_LIST, corners);
        intent.putExtra(ReviewActivity.EXTRA_AUTO_FLAGS,   autoFlags);
        if (bookName != null) intent.putExtra("BOOK_NAME", bookName);
        startActivity(intent);
    }

    // ── UI 갱신 ─────────────────────────────────────────────────────
    private void updateUI() {
        int count = selectedFiles.size();

        if (count > 0) {
            tvCount.setVisibility(View.VISIBLE);
            tvCount.setText(count + " / " + MAX_IMAGES);
        } else {
            tvCount.setVisibility(View.GONE);
        }

        btnDetect.setVisibility(count > 0 ? View.VISIBLE : View.INVISIBLE);
        btnDetect.setText(count + "장 꼭짓점 판별");
        btnCapture.setAlpha(count >= MAX_IMAGES ? 0.4f : 1.0f);
        btnCapture.setEnabled(count < MAX_IMAGES);
    }

    private void handleBackButton() {
        if (!selectedFiles.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("촬영 취소")
                    .setMessage("선택한 사진이 모두 삭제됩니다. 나가시겠어요?")
                    .setPositiveButton("나가기", (d, w) -> finish())
                    .setNegativeButton("계속 촬영", null)
                    .show();
        } else {
            finish();
        }
    }

    private void showMaxImagesDialog() {
        new AlertDialog.Builder(this)
                .setTitle("최대 장수 초과")
                .setMessage("최대 " + MAX_IMAGES + "장까지 선택할 수 있어요.")
                .setPositiveButton("확인", null)
                .show();
    }

    private void showLoading(boolean show, String msg) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (msg != null) tvLoading.setText(msg);
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result != null ? result : "";
    }

    private File uriToFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            File file = new File(getCacheDir(), "gallery_" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            }
            is.close();
            return file;
        } catch (Exception e) {
            Log.e(TAG, "URI → File 변환 실패: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
