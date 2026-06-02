package com.example.hbook.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
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
import com.example.hbook.model.Book;
import com.example.hbook.model.CapturedItem;
import com.example.hbook.model.DetectCornersResponse;
import com.example.hbook.model.OcrResponse;
import com.example.hbook.model.Page;
import com.example.hbook.model.TtsRequest;
import com.example.hbook.model.TtsResponse;
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

public class CameraActivity extends AppCompatActivity {

    private static final String TAG        = "CameraActivity";
    private static final int    MAX_IMAGES = 5;

    // ── UI ──────────────────────────────────────────────────────────
    private PreviewView  viewFinder;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private TextView     btnSendMultiple;
    private TextView     btnPreview;

    // ── 데이터 ───────────────────────────────────────────────────────
    private String      bookName;
    private int         currentUserId = -1;
    private UserSetting currentUserSetting;

    /** 최종 확정된 아이템 리스트 (파일 + corners + autoDetected) */
    private final List<CapturedItem> capturedItems = new ArrayList<>();

    /** 갤러리에서 선택된 파일 (detect-corners 요청 전 임시 보관) */
    private final List<File> pendingGalleryFiles = new ArrayList<>();

    /**
     * 자동 감지 후 수동 확인이 필요한 항목 큐.
     * processDetectResult() 에서 채워지고, openNextPendingCrop() 에서 소비됩니다.
     */
    private final List<PendingCrop> pendingCropQueue = new ArrayList<>();
    private int pendingCropIndex = 0;

    /** 자동 감지 진행 중 플래그 (중복 호출 방지) */
    private boolean isDetecting = false;

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

    private final Retrofit retrofit = new Retrofit.Builder()
            .baseUrl("https://perish-impure-hatred.ngrok-free.dev/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build();

    private final ApiService apiService = retrofit.create(ApiService.class);

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

                List<Uri> sorted = new ArrayList<>(uris);
                sorted.sort((u1, u2) -> getFileName(u1).compareTo(getFileName(u2)));
                if (sorted.size() > remaining) {
                    sorted = sorted.subList(0, remaining);
                    Toast.makeText(this, "최대 " + MAX_IMAGES + "장까지 가능해 앞의 "
                            + remaining + "장만 처리합니다.", Toast.LENGTH_SHORT).show();
                }

                pendingGalleryFiles.clear();
                for (Uri uri : sorted) {
                    File f = uriToFile(uri);
                    if (f != null) pendingGalleryFiles.add(f);
                }

                // 갤러리 파일 전체를 한꺼번에 자동 감지 요청
                handleGalleryFilesReady();
            });

    // ── CropActivity 결과 런처 ───────────────────────────────────────
    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data    = result.getData();
                    String path    = data.getStringExtra(CropActivity.EXTRA_IMAGE_PATH);
                    String corners = data.getStringExtra(CropActivity.EXTRA_CORNERS);

                    float[] cornersArr = CapturedItem.parseCorners(corners);
                    // 사용자가 직접 지정했으므로 autoDetected = false
                    capturedItems.add(new CapturedItem(new File(path), cornersArr, false));
                    updateSendButton();

                } else if (result.getData() != null
                        && result.getData().getBooleanExtra("retake", false)) {
                    // 재촬영 요청 — 아무것도 추가하지 않음
                }

                // 큐에 남은 수동 지정 대상이 있으면 이어서 처리
                openNextPendingCrop();
            });

    /**
     * PreviewActivity 에서 "이대로 스캔하기" 결과를 받는 런처.
     * 사용자가 미리보기에서 수정한 corners 를 반영합니다.
     */
    private final ActivityResultLauncher<Intent> previewLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String[] updatedCorners = result.getData()
                            .getStringArrayExtra(PreviewActivity.EXTRA_UPDATED_CORNERS);

                    if (updatedCorners != null
                            && updatedCorners.length == capturedItems.size()) {
                        List<CapturedItem> rebuilt = new ArrayList<>();
                        for (int i = 0; i < capturedItems.size(); i++) {
                            CapturedItem old       = capturedItems.get(i);
                            float[]      newCorners = CapturedItem.parseCorners(updatedCorners[i]);
                            // 기존 corners 와 다르면 수동 수정으로 플래그 변경
                            boolean stillAuto = old.autoDetected
                                    && updatedCorners[i].equals(old.cornersToString());
                            rebuilt.add(new CapturedItem(old.file, newCorners, stillAuto));
                        }
                        capturedItems.clear();
                        capturedItems.addAll(rebuilt);
                    }
                }
                // PreviewActivity 에서 취소했어도 capturedItems 는 그대로 유지
            });

    // ── onCreate ────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        setContentView(R.layout.activity_camera);
        bookName = getIntent().getStringExtra("BOOK_NAME");

        viewFinder           = findViewById(R.id.viewFinder);
        ImageView btnGallery = findViewById(R.id.btn_gallery);
        View      btnCapture = findViewById(R.id.btn_capture);
        TextView  tvBack     = findViewById(R.id.tv_back);
        TextView  tvBookTitle= findViewById(R.id.tv_book_title);
        btnSendMultiple      = findViewById(R.id.btn_send_multiple);
        btnPreview           = findViewById(R.id.btn_preview);

        if (bookName != null) tvBookTitle.setText(bookName);

        currentUserSetting = AppDatabase.getInstance(this).userDao().getUserSetting(currentUserId);
        if (currentUserSetting == null) {
            currentUserSetting = new UserSetting(currentUserId);
        }

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

        // 미리보기: previewLauncher 로 열어서 수정 결과를 받음
        btnPreview.setOnClickListener(v -> {
            if (!capturedItems.isEmpty()) openPreviewActivityForEdit();
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // ── 카메라 촬영 ─────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                provider.unbindAll();
                provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 연결 실패", e);
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
        File photoFile = new File(getCacheDir(),
                "temp_ocr_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        // 기존: openCropActivity(photoFile)
                        // 변경: 자동 감지 먼저 시도
                        List<File> files = new ArrayList<>();
                        files.add(photoFile);
                        runAutoDetectThenCrop(files);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "사진 저장 실패: " + e.getMessage(), e);
                    }
                });
    }

    // ── 자동 감지 핵심 로직 ─────────────────────────────────────────

    /**
     * 이미지 파일 목록을 서버에 보내 꼭짓점 자동 감지를 요청합니다.
     * 결과에 따라 자동 확정 or 수동 지정 큐로 분기합니다.
     *
     * @param files 촬영/갤러리에서 준비된 이미지 파일 목록
     */
    private void runAutoDetectThenCrop(List<File> files) {
        if (files == null || files.isEmpty() || isDetecting) return;
        isDetecting = true;
        showDetectingProgress(true);

        List<MultipartBody.Part> imageParts = new ArrayList<>();
        for (File f : files) {
            RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), f);
            imageParts.add(MultipartBody.Part.createFormData("image", f.getName(), body));
        }

        apiService.detectCorners(imageParts)
                .enqueue(new Callback<DetectCornersResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DetectCornersResponse> call,
                                           @NonNull Response<DetectCornersResponse> response) {
                        isDetecting = false;
                        showDetectingProgress(false);

                        if (!response.isSuccessful()
                                || response.body() == null
                                || !"success".equals(response.body().status)) {
                            Log.w(TAG, "detect-corners 서버 오류 → 수동 fallback");
                            fallbackToManualCrop(files);
                            return;
                        }
                        processDetectResult(files, response.body().results);
                    }

                    @Override
                    public void onFailure(@NonNull Call<DetectCornersResponse> call,
                                          @NonNull Throwable t) {
                        isDetecting = false;
                        showDetectingProgress(false);
                        Log.w(TAG, "detect-corners 네트워크 오류 → 수동 fallback", t);
                        fallbackToManualCrop(files);
                    }
                });
    }

    /**
     * 서버 자동 감지 결과를 처리합니다.
     *
     * - auto_detected == true  → capturedItems 에 즉시 추가
     * - auto_detected == false → pendingCropQueue 에 적재 후 CropActivity 순차 실행
     */
    private void processDetectResult(List<File> files,
                                     List<DetectCornersResponse.ImageResult> results) {
        pendingCropQueue.clear();
        pendingCropIndex = 0;

        int autoCount   = 0;
        int manualCount = 0;

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            DetectCornersResponse.ImageResult r =
                    (results != null && i < results.size()) ? results.get(i) : null;

            boolean autoOk = (r != null
                    && r.auto_detected
                    && r.corners != null
                    && !r.corners.isEmpty());

            if (autoOk) {
                // ✅ 자동 확정
                float[] corners = CapturedItem.parseCorners(r.corners);
                capturedItems.add(new CapturedItem(file, corners, true));
                autoCount++;
            } else {
                // ⚠️ 수동 지정 필요 — 감지된 corners 가 있으면 힌트로 활용
                String hint = (r != null && r.corners != null) ? r.corners : "";
                pendingCropQueue.add(new PendingCrop(file, hint));
                manualCount++;
            }
        }

        updateSendButton();

        // ── 결과 안내 ──────────────────────────────────────────────
        if (manualCount == 0) {
            // 전부 자동 감지 성공
            Toast.makeText(this,
                    autoCount + "장 모두 자동으로 인식됐어요.",
                    Toast.LENGTH_SHORT).show();

        } else if (autoCount == 0) {
            // 전부 수동 필요
            Toast.makeText(this,
                    manualCount + "장 모두 영역을 직접 지정해주세요.",
                    Toast.LENGTH_SHORT).show();
            openNextPendingCrop();

        } else {
            // 혼합 케이스 → 다이얼로그
            showMixedResultDialog(autoCount, manualCount);
        }
    }

    /**
     * 혼합 케이스 다이얼로그:
     * "N장 자동 인식, M장 직접 지정 필요"
     * "M장 지정하기" or "전체 다시 지정" 선택
     */
    private void showMixedResultDialog(int autoCount, int manualCount) {
        new AlertDialog.Builder(this)
                .setTitle("영역 인식 결과")
                .setMessage(autoCount + "장은 자동으로 인식됐어요.\n"
                        + manualCount + "장은 영역을 직접 지정해주세요.")
                .setPositiveButton(manualCount + "장 지정하기",
                        (d, w) -> openNextPendingCrop())
                .setNeutralButton("전체 다시 지정",
                        (d, w) -> {
                            revertAutoToManual();
                            openNextPendingCrop();
                        })
                .setCancelable(false)
                .show();
    }

    /**
     * "전체 다시 지정" 선택 시:
     * capturedItems 에 들어간 autoDetected 항목을 pendingCropQueue 앞으로 되돌립니다.
     */
    private void revertAutoToManual() {
        List<PendingCrop> reverted = new ArrayList<>();
        List<CapturedItem> keep   = new ArrayList<>();

        for (CapturedItem item : capturedItems) {
            if (item.autoDetected) {
                reverted.add(new PendingCrop(item.file, item.cornersToString()));
            } else {
                keep.add(item);
            }
        }
        capturedItems.clear();
        capturedItems.addAll(keep);

        // 자동 감지분을 큐 맨 앞에 삽입 (원래 순서 유지)
        pendingCropQueue.addAll(0, reverted);
        pendingCropIndex = 0;
    }

    // ── 수동 지정 큐 처리 ───────────────────────────────────────────

    /**
     * 큐에서 다음 수동 지정 대상을 꺼내 CropActivity 를 실행합니다.
     * 큐가 비어있으면 아무것도 하지 않습니다.
     */
    private void openNextPendingCrop() {
        if (pendingCropIndex >= pendingCropQueue.size()) return;

        PendingCrop pending      = pendingCropQueue.get(pendingCropIndex++);
        int         totalManual  = pendingCropQueue.size();
        int         currentOrder = pendingCropIndex; // 1-based

        // "전체 중 몇 번째 / 전체 몇 장" 형태의 레이블
        int overallIndex = capturedItems.size() + currentOrder;
        int overallTotal = capturedItems.size() + totalManual;
        String label = overallIndex + " / " + overallTotal + "  •  영역 확인 필요";

        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, pending.file.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, label);

        // 서버가 감지한 corners 가 있으면 CropView 초기값 힌트로 전달
        if (!pending.autoCornersStr.isEmpty()) {
            intent.putExtra(CropActivity.EXTRA_AUTO_CORNERS, pending.autoCornersStr);
        }
        cropLauncher.launch(intent);
    }

    // ── 갤러리 처리 ─────────────────────────────────────────────────

    /**
     * 갤러리 선택 완료 후 호출.
     * pendingGalleryFiles 전체를 한 번에 자동 감지 요청합니다.
     */
    private void handleGalleryFilesReady() {
        if (pendingGalleryFiles.isEmpty()) return;
        List<File> files = new ArrayList<>(pendingGalleryFiles);
        pendingGalleryFiles.clear();
        runAutoDetectThenCrop(files);
    }

    // ── 오류 fallback ───────────────────────────────────────────────

    /**
     * 서버 오류 / 네트워크 오류 시 모든 파일을 수동 지정 큐에 넣습니다.
     */
    private void fallbackToManualCrop(List<File> files) {
        pendingCropQueue.clear();
        pendingCropIndex = 0;
        for (File f : files) {
            pendingCropQueue.add(new PendingCrop(f, ""));
        }
        Toast.makeText(this, "영역을 직접 지정해주세요.", Toast.LENGTH_SHORT).show();
        openNextPendingCrop();
    }

    // ── 미리보기 화면 (수정 가능) ────────────────────────────────────

    /**
     * PreviewActivity 를 previewLauncher 로 실행합니다.
     * 사용자가 미리보기에서 수정한 corners 는 previewLauncher 결과로 반영됩니다.
     */
    private void openPreviewActivityForEdit() {
        String[]  paths     = new String[capturedItems.size()];
        String[]  corners   = new String[capturedItems.size()];
        boolean[] autoFlags = new boolean[capturedItems.size()];

        for (int i = 0; i < capturedItems.size(); i++) {
            CapturedItem item = capturedItems.get(i);
            paths[i]     = item.file.getAbsolutePath();
            corners[i]   = item.hasCorners() ? item.cornersToString() : "";
            autoFlags[i] = item.autoDetected;
        }

        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_PATHS,  paths);
        intent.putExtra(PreviewActivity.EXTRA_CORNERS_LIST, corners);
        intent.putExtra(PreviewActivity.EXTRA_AUTO_FLAGS,   autoFlags);
        previewLauncher.launch(intent);
    }

    // ── UI 갱신 ─────────────────────────────────────────────────────

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

    private void showDetectingProgress(boolean show) {
        runOnUiThread(() -> {
            if (show) {
                btnSendMultiple.setEnabled(false);
                btnSendMultiple.setText("영역 분석 중...");
            } else {
                updateSendButton();
            }
        });
    }

    // ── 서버 전송 ──────────────────────────────────────────────────

    private void uploadMultipleToServer(List<CapturedItem> items) {
        List<MultipartBody.Part> imageParts   = new ArrayList<>();
        List<MultipartBody.Part> cornersParts = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            CapturedItem item = items.get(i);
            RequestBody requestFile = RequestBody.create(
                    MediaType.parse("image/jpeg"), item.file);
            imageParts.add(MultipartBody.Part.createFormData(
                    "image", item.file.getName(), requestFile));

            String cornersStr = item.hasCorners() ? item.cornersToString() : "";
            cornersParts.add(MultipartBody.Part.createFormData(
                    "corners_" + i, cornersStr));
        }

        RequestBody pageNumBody = RequestBody.create(
                MediaType.parse("text/plain"), "1");

        apiService.uploadMultipleImages(imageParts, cornersParts, pageNumBody)
                .enqueue(new Callback<OcrResponse>() {

                    @Override
                    public void onResponse(@NonNull Call<OcrResponse> call,
                                           @NonNull Response<OcrResponse> response) {

                        if (!response.isSuccessful() || response.body() == null) {
                            showToast("서버 응답 오류 (JSON 파싱 에러)");
                            resetButton();
                            return;
                        }

                        OcrResponse ocrData = response.body();
                        if (!"success".equals(ocrData.status)) {
                            showToast("서버 응답 오류");
                            resetButton();
                            return;
                        }

                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            AppDatabase db        = AppDatabase.getInstance(CameraActivity.this);
                            String      bookTitle = (bookName != null) ? bookName : "무제";
                            Book        newBook   = new Book(bookTitle, currentUserId);
                            long generatedBookId  = db.libraryDao().insertBook(newBook);

                            // ── 1단계: 페이지 DB 저장 ──────────────────────────
                            List<Page>   savedPages      = new ArrayList<>();
                            List<String> ttsInstructions = new ArrayList<>();

                            if (ocrData.results != null && !ocrData.results.isEmpty()) {
                                for (int i = 0; i < ocrData.results.size(); i++) {
                                    OcrResponse.PageResult pageData = ocrData.results.get(i);
                                    if (pageData.extracted_text == null) continue;

                                    Page newPage = new Page(
                                            (int) generatedBookId, i + 1,
                                            pageData.extracted_text);
                                    if (pageData.sentiment != null) {
                                        newPage.emotionValence = pageData.sentiment.valence;
                                        newPage.emotionArousal = pageData.sentiment.arousal;
                                        newPage.emotionLabel   = pageData.sentiment.label != null
                                                ? pageData.sentiment.label : "";
                                    }
                                    db.libraryDao().insertPage(newPage);
                                    savedPages.add(newPage);

                                    String instr = (pageData.sentiment != null
                                            && pageData.sentiment.tts_instruction != null)
                                            ? pageData.sentiment.tts_instruction
                                            : "자연스럽고 차분한 목소리로 읽어주세요.";
                                    ttsInstructions.add(instr);
                                }
                            }

                            // ── 2단계: ViewerActivity 로 이동 ──────────────────
                            runOnUiThread(() -> {
                                showToast("변환 완료");
                                Intent intent = new Intent(CameraActivity.this,
                                        ViewerActivity.class);
                                intent.putExtra("BOOK_ID", (int) generatedBookId);
                                if (bookName != null) intent.putExtra("BOOK_NAME", bookName);
                                startActivity(intent);
                                finish();
                            });

                            // ── 3단계: TTS 백그라운드 생성 ─────────────────────
                            generateTtsForPages(savedPages, ttsInstructions, db);
                        });
                    }

                    @Override
                    public void onFailure(@NonNull Call<OcrResponse> call,
                                          @NonNull Throwable t) {
                        Log.e(TAG, "스캔 요청 실패", t);
                        showToast("서버 연결 실패: " + t.getMessage());
                        resetButton();
                    }
                });
    }

    // ── TTS 생성 + 파일 저장 ─────────────────────────────────────────

    private void generateTtsForPages(List<Page> pages,
                                     List<String> instructions,
                                     AppDatabase db) {
        for (int i = 0; i < pages.size(); i++) {
            Page   page  = pages.get(i);
            String instr = instructions.get(i);
            if (page.extractedText == null || page.extractedText.isEmpty()) continue;

            TtsRequest ttsReq = new TtsRequest(
                    page.extractedText, instr, page.pageId,
                    currentUserSetting != null ? currentUserSetting.ttsVoice : "Cherry");
            try {
                Response<TtsResponse> ttsResp = apiService.generateTts(ttsReq).execute();

                if (ttsResp.isSuccessful()
                        && ttsResp.body() != null
                        && ttsResp.body().audio_base64 != null) {

                    byte[] audioBytes = Base64.decode(
                            ttsResp.body().audio_base64, Base64.DEFAULT);

                    String voice = (currentUserSetting != null
                            && currentUserSetting.ttsVoice != null)
                            ? currentUserSetting.ttsVoice : "Cherry";

                    File audioFile = new File(getFilesDir(),
                            "tts_book" + page.bookId
                                    + "_page" + page.pageNumber
                                    + "_" + voice + ".wav");

                    try (FileOutputStream fos = new FileOutputStream(audioFile)) {
                        fos.write(audioBytes);
                    }
                    db.libraryDao().updateAudioFilePath(
                            page.pageId, audioFile.getAbsolutePath());
                    Log.d(TAG, "TTS 저장 완료: " + audioFile.getName());

                } else {
                    Log.w(TAG, "TTS 응답 오류 — page " + page.pageNumber);
                }
            } catch (Exception e) {
                Log.e(TAG, "TTS 예외 — page " + page.pageNumber + ": " + e.getMessage());
            }
        }
        Log.d(TAG, "전체 TTS 완료 (" + pages.size() + "페이지)");
    }

    // ── 유틸리티 ────────────────────────────────────────────────────

    private void handleBackButton() {
        if (bookName != null) {
            new AlertDialog.Builder(this)
                    .setTitle("스캔 취소")
                    .setMessage("지금 돌아가면 '" + bookName + "' 추가가 취소됩니다. 돌아가시겠습니까?")
                    .setPositiveButton("예",    (d, w) -> finish())
                    .setNegativeButton("아니요", (d, w) -> d.cancel())
                    .show();
        } else {
            finish();
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() ->
                Toast.makeText(CameraActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private void resetButton() {
        runOnUiThread(() -> {
            btnSendMultiple.setEnabled(true);
            btnSendMultiple.setText(capturedItems.size() + "장 변환");
        });
    }

    private File uriToFile(Uri uri) {
        try {
            InputStream  in  = getContentResolver().openInputStream(uri);
            File         tmp = new File(getCacheDir(),
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    // ── 내부 데이터 클래스 ──────────────────────────────────────────

    /**
     * 수동 지정 대기 큐의 한 항목.
     * autoCornersStr: 서버가 감지했지만 신뢰도가 낮아 수동 지정이 필요한 corners
     *                 → CropView 초기값 힌트로 사용
     */
    private static class PendingCrop {
        final File   file;
        final String autoCornersStr;

        PendingCrop(File file, String autoCornersStr) {
            this.file           = file;
            this.autoCornersStr = autoCornersStr != null ? autoCornersStr : "";
        }
    }
}
