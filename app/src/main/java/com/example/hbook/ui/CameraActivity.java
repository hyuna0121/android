package com.example.hbook.ui;

import android.Manifest;
import android.app.AlertDialog;
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

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.Book;
import com.example.hbook.model.CapturedItem;
import com.example.hbook.model.OcrResponse;
import com.example.hbook.model.Page;
import com.example.hbook.network.ApiService;
import com.example.hbook.R;
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

public class CameraActivity extends AppCompatActivity {

    private static final int MAX_IMAGES = 5;  // 한 번에 전송 가능한 최대 이미지 수

    private PreviewView  viewFinder;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private String bookName;
    private int    currentUserId = -1;

    // ── 핵심 변경: File 대신 CapturedItem 리스트 ─────────────────────
    private final List<CapturedItem> capturedItems = new ArrayList<>();

    // 갤러리에서 고른 Uri 들을 임시 보관 (CropActivity 처리 순서 관리용)
    private final List<File> pendingGalleryFiles = new ArrayList<>();
    private int pendingGalleryIndex = 0;

    private TextView btnSendMultiple;
    private TextView btnPreview;

    // ── 갤러리 런처 ──────────────────────────────────────────────────
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris == null || uris.isEmpty()) return;

                int remaining = MAX_IMAGES - capturedItems.size();
                if (remaining <= 0) {
                    Toast.makeText(this, "최대 " + MAX_IMAGES + "장까지 선택할 수 있습니다.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                // 파일 이름순 정렬 후 File 로 변환, 남은 슬롯만큼만 처리
                List<Uri> sorted = new ArrayList<>(uris);
                sorted.sort((u1, u2) -> getFileName(u1).compareTo(getFileName(u2)));
                if (sorted.size() > remaining) {
                    sorted = sorted.subList(0, remaining);
                    Toast.makeText(this, "최대 " + MAX_IMAGES + "장까지 가능해 앞의 "
                            + remaining + "장만 처리합니다.", Toast.LENGTH_SHORT).show();
                }

                pendingGalleryFiles.clear();
                pendingGalleryIndex = 0;

                for (Uri uri : sorted) {
                    File f = uriToFile(uri);
                    if (f != null) pendingGalleryFiles.add(f);
                }

                // 첫 번째 파일의 CropActivity 시작
                openNextCropForGallery();
            });

    // ── CropActivity 결과 런처 ────────────────────────────────────────
    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data     = result.getData();
                    String path     = data.getStringExtra(CropActivity.EXTRA_IMAGE_PATH);
                    String corners  = data.getStringExtra(CropActivity.EXTRA_CORNERS);

                    // corners 문자열 → float[]
                    float[] cornersArr = parseCornersString(corners);
                    capturedItems.add(new CapturedItem(new File(path), cornersArr));
                    updateSendButton();

                } else if (result.getResultCode() == RESULT_CANCELED) {
                    boolean retake = false;
                    if (result.getData() != null) {
                        retake = result.getData().getBooleanExtra("retake", false);
                    }
                    // retake=true 이면 카메라 화면을 그냥 유지 (아무것도 안 함)
                    // retake=false (취소) 이면 마찬가지로 유지
                }

                // 갤러리 처리 중이었으면 다음 파일로
                if (pendingGalleryIndex < pendingGalleryFiles.size()) {
                    openNextCropForGallery();
                }
            });

    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        setContentView(R.layout.activity_camera);

        bookName = getIntent().getStringExtra("BOOK_NAME");

        viewFinder      = findViewById(R.id.viewFinder);
        ImageView btnGallery = findViewById(R.id.btn_gallery);
        View      btnCapture = findViewById(R.id.btn_capture);
        TextView  tvBack     = findViewById(R.id.tv_back);
        TextView  tvBookTitle= findViewById(R.id.tv_book_title);
        btnSendMultiple = findViewById(R.id.btn_send_multiple);
        btnPreview      = findViewById(R.id.btn_preview);

        if (bookName != null) tvBookTitle.setText(bookName);

        // 카메라 권한
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 10);
        }

        tvBack.setOnClickListener(v -> handleBackButton());
        btnCapture.setOnClickListener(v -> takePhoto());
        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        btnSendMultiple.setOnClickListener(v -> {
            if (!capturedItems.isEmpty()) {
                Toast.makeText(this, capturedItems.size() + "장 변환을 시작합니다.",
                        Toast.LENGTH_SHORT).show();
                btnSendMultiple.setEnabled(false);
                btnSendMultiple.setText("변환 중...");
                uploadMultipleToServer(capturedItems);
            }
        });

        btnPreview.setOnClickListener(v -> {
            if (!capturedItems.isEmpty()) {
                openPreviewActivity();
            }
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // ─────────────────────────────────────────────────────────────────
    // CropActivity 호출 헬퍼
    // ─────────────────────────────────────────────────────────────────

    /** 카메라로 찍은 파일 1장을 CropActivity 로 보냄 */
    private void openCropActivity(File file) {
        // 뱃지: 현재까지 확정된 장수 + 1
        String label = (capturedItems.size() + 1) + " 번째 사진";
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, label);
        cropLauncher.launch(intent);
    }

    /** 갤러리 pending 리스트의 다음 파일을 CropActivity 로 보냄 */
    private void openNextCropForGallery() {
        if (pendingGalleryIndex >= pendingGalleryFiles.size()) return;
        File file = pendingGalleryFiles.get(pendingGalleryIndex++);
        String label = (capturedItems.size() + 1) + " / "
                + (capturedItems.size() + pendingGalleryFiles.size() - pendingGalleryIndex + 1 + capturedItems.size());
        // 단순하게: "현재번째 / 전체"
        int total = capturedItems.size() + pendingGalleryFiles.size();
        int current = capturedItems.size() + pendingGalleryIndex;
        label = current + " / " + total;

        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, label);
        cropLauncher.launch(intent);
    }

    // ─────────────────────────────────────────────────────────────────
    // 카메라
    // ─────────────────────────────────────────────────────────────────

    private void handleBackButton() {
        if (bookName != null) {
            new AlertDialog.Builder(this)
                    .setTitle("스캔 취소")
                    .setMessage("지금 돌아가면 '" + bookName + "' 추가가 취소됩니다. 돌아가시겠습니까?")
                    .setPositiveButton("예", (d, w) -> finish())
                    .setNegativeButton("아니요", (d, w) -> d.cancel())
                    .show();
        } else {
            finish();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "카메라 연결 실패", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        if (capturedItems.size() >= MAX_IMAGES) {
            Toast.makeText(this, "최대 " + MAX_IMAGES + "장까지 촬영할 수 있습니다.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String timeStamp = String.valueOf(System.currentTimeMillis());
        File photoFile   = new File(getCacheDir(), "temp_ocr_" + timeStamp + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        // 촬영 완료 → 바로 CropActivity 로 이동
                        openCropActivity(photoFile);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e("CameraX", "사진 저장 실패: " + e.getMessage(), e);
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────
    // UI 갱신
    // ─────────────────────────────────────────────────────────────────

    private void openPreviewActivity() {
        String[] paths   = new String[capturedItems.size()];
        String[] corners = new String[capturedItems.size()];
        for (int i = 0; i < capturedItems.size(); i++) {
            paths[i]   = capturedItems.get(i).file.getAbsolutePath();
            corners[i] = capturedItems.get(i).hasCorners()
                    ? capturedItems.get(i).cornersToString() : "";
        }
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_PATHS,  paths);
        intent.putExtra(PreviewActivity.EXTRA_CORNERS_LIST, corners);
        startActivity(intent);
    }

    private void updateSendButton() {
        int count = capturedItems.size();
        if (count > 0) {
            btnPreview.setVisibility(View.VISIBLE);
            btnPreview.setEnabled(true);
            btnSendMultiple.setText(count + "장 변환");
            btnSendMultiple.setVisibility(View.VISIBLE);
            btnSendMultiple.setEnabled(true);
        } else {
            btnSendMultiple.setVisibility(View.INVISIBLE);
            btnPreview.setVisibility(View.INVISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 서버 전송 (꼭짓점 좌표 포함)
    // ─────────────────────────────────────────────────────────────────

    private void uploadMultipleToServer(List<CapturedItem> items) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://egal-furcately-nydia.ngrok-free.dev/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        ApiService apiService = retrofit.create(ApiService.class);

        List<MultipartBody.Part> imageParts   = new ArrayList<>();
        List<MultipartBody.Part> cornersParts = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            CapturedItem item = items.get(i);

            // 이미지 파트
            RequestBody requestFile = RequestBody.create(
                    MediaType.parse("image/jpeg"), item.file);
            imageParts.add(MultipartBody.Part.createFormData(
                    "image", item.file.getName(), requestFile));

            // 꼭짓점 파트 (없으면 빈 문자열)
            String cornersStr = item.hasCorners() ? item.cornersToString() : "";
            cornersParts.add(MultipartBody.Part.createFormData(
                    "corners_" + i, cornersStr));
        }

        RequestBody pageNumBody = RequestBody.create(
                MediaType.parse("text/plain"), "1");

        Call<OcrResponse> call = apiService.uploadMultipleImages(
                imageParts, cornersParts, pageNumBody);

        call.enqueue(new Callback<OcrResponse>() {
            @Override
            public void onResponse(Call<OcrResponse> call, Response<OcrResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    OcrResponse ocrData = response.body();
                    if ("success".equals(ocrData.status)) {
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            AppDatabase db = AppDatabase.getInstance(CameraActivity.this);

                            String bookTitle = (bookName != null)
                                    ? bookName
                                    : "스캔 " + System.currentTimeMillis();

                            Book newBook = new Book(bookTitle, currentUserId);
                            long generatedBookId = db.libraryDao().insertBook(newBook);

                            if (ocrData.results != null && !ocrData.results.isEmpty()) {
                                for (int i = 0; i < ocrData.results.size(); i++) {
                                    OcrResponse.PageResult pageData = ocrData.results.get(i);
                                    if (pageData.extracted_text != null) {
                                        Page newPage = new Page(
                                                (int) generatedBookId,
                                                i + 1,
                                                pageData.extracted_text);
                                        if (pageData.sentiment != null) {
                                            newPage.emotionValence = pageData.sentiment.valence;
                                            newPage.emotionArousal = pageData.sentiment.arousal;
                                        }
                                        db.libraryDao().insertPage(newPage);
                                    }
                                }
                            }

                            runOnUiThread(() -> {
                                Toast.makeText(CameraActivity.this,
                                        "변환 완료!", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(
                                        CameraActivity.this, ViewerActivity.class);
                                intent.putExtra("BOOK_ID", (int) generatedBookId);
                                if (bookName != null) {
                                    intent.putExtra("BOOK_NAME", bookName);
                                }
                                startActivity(intent);
                                finish();
                            });
                        });
                    }
                }
            }

            @Override
            public void onFailure(Call<OcrResponse> call, Throwable t) {
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "서버 오류: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    btnSendMultiple.setEnabled(true);
                    btnSendMultiple.setText(capturedItems.size() + "장 변환");
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────────────────────────

    private File uriToFile(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            File tmp = new File(getCacheDir(),
                    "temp_ocr_" + UUID.randomUUID() + ".jpg");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.close();
            in.close();
            return tmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
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

    /** "x0,y0,x1,y1,...,x3,y3" 문자열을 float[] 로 변환 */
    private float[] parseCornersString(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] parts = s.split(",");
            if (parts.length != 8) return null;
            float[] arr = new float[8];
            for (int i = 0; i < 8; i++) arr[i] = Float.parseFloat(parts[i].trim());
            return arr;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
