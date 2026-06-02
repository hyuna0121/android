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
import android.widget.LinearLayout;
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
import com.example.hbook.model.CapturedItem;
import com.example.hbook.model.DetectCornersResponse;
import com.example.hbook.model.UserSetting;
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
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 1단계 화면: 카메라 촬영 / 갤러리 선택
 *
 * 역할:
 *   - 사진 촬영 또는 갤러리 선택 (최대 MAX_IMAGES장)
 *   - 5장 초과 시 안내 다이얼로그
 *   - 하단 썸네일 스트립으로 선택된 사진 수 표시
 *   - "꼭짓점 판별" 버튼 → /api/detect-corners 요청 → 로딩 표시
 *   - 결과 수신 후 ReviewActivity 로 이동
 */
public class CameraActivity extends AppCompatActivity {

    private static final String TAG        = "CameraActivity";
    private static final int    MAX_IMAGES = 5;

    // ── UI ──────────────────────────────────────────────────────────
    private PreviewView  viewFinder;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private TextView      tvBookTitle;
    private TextView      tvBack;
    private TextView      tvCount;          // "2 / 5" 표시
    private View          btnCapture;
    private ImageView     btnGallery;
    private TextView      btnDetect;        // "꼭짓점 판별" 버튼
    private View          loadingOverlay;   // 로딩 오버레이
    private TextView      tvLoading;        // "로딩 중..." 텍스트

    // ── 데이터 ───────────────────────────────────────────────────────
    private String      bookName;
    private int         currentUserId     = -1;
    private UserSetting currentUserSetting;

    /** 촬영/선택된 파일 목록 (꼭짓점 판별 전 단계) */
    private final List<File> selectedFiles = new ArrayList<>();

    // ── Retrofit ────────────────────────────────────────────────────
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(
                    chain.request().newBuilder()
                            .addHeader("ngrok-skip-browser-warning", "true")
                            .build()))
            .build();

    private final ApiService apiService = new Retrofit.Builder()
            .baseUrl("https://perish-impure-hatred.ngrok-free.dev/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService.class);

    // ── 갤러리 런처 ──────────────────────────────────────────────────
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris == null || uris.isEmpty()) return;

                int remaining = MAX_IMAGES - selectedFiles.size();

                // 5장 초과 안내
                if (remaining <= 0) {
                    showMaxImagesDialog();
                    return;
                }

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
        if (currentUserSetting == null) currentUserSetting = new com.example.hbook.model.UserSetting(currentUserId);

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

        // 5장 초과 체크
        if (selectedFiles.size() >= MAX_IMAGES) {
            showMaxImagesDialog();
            return;
        }

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

    // ── 꼭짓점 판별 요청 ────────────────────────────────────────────

    /**
     * "꼭짓점 판별" 버튼 클릭 시 호출.
     * 선택된 파일들을 /api/detect-corners 로 전송하고
     * 결과를 ReviewActivity 로 전달합니다.
     */
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

                        if (!response.isSuccessful()
                                || response.body() == null
                                || !"success".equals(response.body().status)) {
                            // 서버 오류 → 모두 수동으로 ReviewActivity 이동
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
                        // 오류 시에도 ReviewActivity 로 이동 (모두 수동)
                        launchReviewActivity(null);
                    }
                });
    }

    /**
     * ReviewActivity 로 데이터를 전달하며 이동합니다.
     *
     * @param detectResult /api/detect-corners 응답 (null 이면 모두 수동)
     */
    private void launchReviewActivity(DetectCornersResponse detectResult) {
        String[] paths      = new String[selectedFiles.size()];
        String[] corners    = new String[selectedFiles.size()];
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

    /**
     * 선택된 파일 수에 따라 카운트 표시와 "꼭짓점 판별" 버튼 가시성 갱신
     */
    private void updateUI() {
        int count = selectedFiles.size();

        // 카운트 뱃지
        if (count > 0) {
            tvCount.setVisibility(View.VISIBLE);
            tvCount.setText(count + " / " + MAX_IMAGES);
        } else {
            tvCount.setVisibility(View.GONE);
        }

        // 꼭짓점 판별 버튼
        btnDetect.setVisibility(count > 0 ? View.VISIBLE : View.INVISIBLE);
        btnDetect.setText(count + "장 꼭짓점 판별");

        // 촬영 버튼 비활성 (5장 다 찼을 때)
        btnCapture.setAlpha(count >= MAX_IMAGES ? 0.4f : 1.0f);
        btnCapture.setEnabled(count < MAX_IMAGES);
    }

    private void showLoading(boolean show, String message) {
        runOnUiThread(() -> {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            if (message != null) tvLoading.setText(message);
            btnDetect.setEnabled(!show);
        });
    }

    private void showMaxImagesDialog() {
        new AlertDialog.Builder(this)
                .setTitle("사진 수 제한")
                .setMessage("최대 " + MAX_IMAGES + "장까지 선택할 수 있어요.")
                .setPositiveButton("확인", null)
                .show();
    }

    // ── 유틸 ────────────────────────────────────────────────────────

    private void handleBackButton() {
        if (bookName != null && !selectedFiles.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("스캔 취소")
                    .setMessage("지금 돌아가면 선택한 사진이 모두 사라져요.")
                    .setPositiveButton("나가기", (d, w) -> finish())
                    .setNegativeButton("계속하기", null)
                    .show();
        } else {
            finish();
        }
    }

    private File uriToFile(Uri uri) {
        try {
            InputStream      in  = getContentResolver().openInputStream(uri);
            File             tmp = new File(getCacheDir(), "temp_ocr_" + UUID.randomUUID() + ".jpg");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[4096]; int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.close(); in.close();
            return tmp;
        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    private String getFileName(Uri uri) {
        String name = "";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx);
            cursor.close();
        }
        return name;
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}