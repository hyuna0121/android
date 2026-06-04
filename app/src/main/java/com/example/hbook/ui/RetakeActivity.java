package com.example.hbook.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.hbook.R;
import com.example.hbook.model.DetectCornersResponse;
import com.example.hbook.network.ApiClient;
import com.example.hbook.network.ApiService;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
 * ReviewActivity 에서 "다시 찍기" 버튼을 눌렀을 때 열리는 화면.
 *
 * 촬영 → /api/detect-corners 자동 감지 → 결과를 ReviewActivity 로 반환.
 * 자동 감지 실패 시 CropActivity 로 이동해 수동 지정 후 반환.
 */
public class RetakeActivity extends AppCompatActivity {

    private static final String TAG = "RetakeActivity";

    private PreviewView     viewFinder;
    private ImageCapture    imageCapture;
    private ExecutorService cameraExecutor;
    private View            loadingOverlay;
    private TextView        tvLoading;

    private File newPhotoFile;

    // ── ApiService — ApiClient 싱글턴으로 초기화 ──────────────────────
    // 기존: final 필드로 OkHttpClient + Retrofit 블록 직접 선언
    // 변경: onCreate 에서 ApiClient.getService(this) 한 줄로 교체
    private ApiService apiService;

    // ── CropActivity 결과 런처 ───────────────────────────────────────
    private final androidx.activity.result.ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null
                                && newPhotoFile != null) {
                            String corners = result.getData().getStringExtra(CropActivity.EXTRA_CORNERS);
                            returnResult(newPhotoFile.getAbsolutePath(),
                                    corners != null ? corners : "", false);
                        }
                    });

    // ── onCreate ────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_retake);

        viewFinder     = findViewById(R.id.viewFinder);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoading      = findViewById(R.id.tv_loading);
        View     btnCapture = findViewById(R.id.btn_capture);
        TextView tvCancel   = findViewById(R.id.tv_cancel);

        // ── ApiClient 싱글턴 — JWT 토큰 + ngrok 헤더 자동 첨부 ───────
        apiService = ApiClient.getService(this);

        tvCancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
        btnCapture.setOnClickListener(v -> takePhoto());

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
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

        newPhotoFile = new File(getCacheDir(),
                "retake_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(newPhotoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        autoDetectCorners(newPhotoFile);
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Toast.makeText(RetakeActivity.this, "촬영 실패", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── 꼭짓점 자동 감지 ─────────────────────────────────────────────
    private void autoDetectCorners(File file) {
        showLoading(true, "영역 분석 중...");

        RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), file);
        List<MultipartBody.Part> parts = new ArrayList<>();
        parts.add(MultipartBody.Part.createFormData("image", file.getName(), body));

        apiService.detectCorners(parts)
                .enqueue(new Callback<DetectCornersResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DetectCornersResponse> call,
                                           @NonNull Response<DetectCornersResponse> response) {
                        showLoading(false, null);
                        // 401은 ApiClient 인터셉터가 자동 처리
                        if (response.code() == 401) return;

                        DetectCornersResponse.ImageResult r = null;
                        if (response.isSuccessful() && response.body() != null
                                && response.body().results != null
                                && !response.body().results.isEmpty()) {
                            r = response.body().results.get(0);
                        }

                        if (r != null && r.auto_detected
                                && r.corners != null && !r.corners.isEmpty()) {
                            returnResult(file.getAbsolutePath(), r.corners, true);
                        } else {
                            openCropActivity(file, r != null ? r.corners : "");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<DetectCornersResponse> call,
                                          @NonNull Throwable t) {
                        showLoading(false, null);
                        openCropActivity(file, "");
                    }
                });
    }

    private void openCropActivity(File file, String hintCorners) {
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, "꼭짓점 직접 지정");
        if (hintCorners != null && !hintCorners.isEmpty()) {
            intent.putExtra(CropActivity.EXTRA_AUTO_CORNERS, hintCorners);
        }
        cropLauncher.launch(intent);
    }

    private void returnResult(String path, String corners, boolean autoDetected) {
        Intent result = new Intent();
        result.putExtra("new_image_path", path);
        result.putExtra(CropActivity.EXTRA_CORNERS, corners);
        result.putExtra("auto_detected", autoDetected);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void showLoading(boolean show, String msg) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (msg != null) tvLoading.setText(msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
