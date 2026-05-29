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
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
import com.example.hbook.model.TtsRequest;
import com.example.hbook.model.TtsResponse;
import com.example.hbook.model.UserSetting;
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

    private static final String TAG        = "CameraActivity";
    private static final int    MAX_IMAGES = 5;

    // ── UI ──────────────────────────────────────────────────────────
    private PreviewView     viewFinder;
    private ImageCapture    imageCapture;
    private ExecutorService cameraExecutor;
    private TextView        btnSendMultiple;
    private TextView        btnPreview;

    private UserSetting     currentUserSetting;

    // ── 데이터 ───────────────────────────────────────────────────────
    private String bookName;
    private int    currentUserId = -1;

    // 확정된 촬영/갤러리 아이템 (파일 + corners)
    private final List<CapturedItem> capturedItems = new ArrayList<>();

    // 갤러리에서 고른 파일 순차 처리용
    private final List<File> pendingGalleryFiles = new ArrayList<>();
    private int pendingGalleryIndex = 0;

    // ── Retrofit 클라이언트 (scan + tts 공통) ───────────────────────
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(
                    chain.request().newBuilder()
                            .addHeader("ngrok-skip-browser-warning", "true")
                            .build()
            ))
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
                pendingGalleryIndex = 0;
                for (Uri uri : sorted) {
                    File f = uriToFile(uri);
                    if (f != null) pendingGalleryFiles.add(f);
                }
                openNextCropForGallery();
            });

    // ── CropActivity 결과 런처 ───────────────────────────────────────
    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data    = result.getData();
                    String path    = data.getStringExtra(CropActivity.EXTRA_IMAGE_PATH);
                    String corners = data.getStringExtra(CropActivity.EXTRA_CORNERS);

                    float[] cornersArr = parseCornersString(corners);
                    capturedItems.add(new CapturedItem(new File(path), cornersArr));
                    updateSendButton();
                }
                // 갤러리 처리 중이었으면 다음 파일로
                if (pendingGalleryIndex < pendingGalleryFiles.size()) {
                    openNextCropForGallery();
                }
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

        btnPreview.setOnClickListener(v -> {
            if (!capturedItems.isEmpty()) openPreviewActivity();
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // ── CropActivity 호출 헬퍼 ──────────────────────────────────────

    private void openCropActivity(File file) {
        String label = (capturedItems.size() + 1) + " 번째 사진";
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, label);
        cropLauncher.launch(intent);
    }

    private void openNextCropForGallery() {
        if (pendingGalleryIndex >= pendingGalleryFiles.size()) return;
        File file    = pendingGalleryFiles.get(pendingGalleryIndex++);
        int  total   = capturedItems.size() + pendingGalleryFiles.size();
        int  current = capturedItems.size() + pendingGalleryIndex;
        String label = current + " / " + total;
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PAGE_LABEL, label);
        cropLauncher.launch(intent);
    }

    // ── 카메라 ──────────────────────────────────────────────────────

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
                        openCropActivity(photoFile);
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "사진 저장 실패: " + e.getMessage(), e);
                    }
                });
    }

    // ── UI 갱신 ─────────────────────────────────────────────────────

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

            // corners 없으면 빈 문자열 전송 (서버에서 원근 보정 스킵)
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

                        // DB 저장 + TTS 생성은 백그라운드에서
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        executor.execute(() -> {
                            AppDatabase db = AppDatabase.getInstance(CameraActivity.this);
                            String bookTitle = (bookName != null) ? bookName : "무제";
                            Book newBook = new Book(bookTitle, currentUserId);
                            long generatedBookId = db.libraryDao().insertBook(newBook);

                            // ── 1단계: 페이지 DB 저장 ──────────────────────────
                            List<Page>   savedPages      = new ArrayList<>();
                            List<String> ttsInstructions = new ArrayList<>();

                            if (ocrData.results != null && !ocrData.results.isEmpty()) {
                                for (int i = 0; i < ocrData.results.size(); i++) {
                                    OcrResponse.PageResult pageData = ocrData.results.get(i);
                                    if (pageData.extracted_text == null) continue;

                                    Page newPage = new Page(
                                            (int) generatedBookId, i + 1, pageData.extracted_text);
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
                            } else if (ocrData.extracted_text != null) {
                                Page newPage = new Page(
                                        (int) generatedBookId, 1, ocrData.extracted_text);
                                if (ocrData.sentiment != null) {
                                    newPage.emotionValence = ocrData.sentiment.valence;
                                    newPage.emotionArousal = ocrData.sentiment.arousal;
                                    newPage.emotionLabel   = ocrData.sentiment.label != null
                                            ? ocrData.sentiment.label : "";
                                }
                                db.libraryDao().insertPage(newPage);
                                savedPages.add(newPage);

                                String instr = (ocrData.sentiment != null
                                        && ocrData.sentiment.tts_instruction != null)
                                        ? ocrData.sentiment.tts_instruction
                                        : "자연스럽고 차분한 목소리로 읽어주세요.";
                                ttsInstructions.add(instr);
                            }

                            // ── 2단계: ViewerActivity 먼저 이동 ────────────────
                            runOnUiThread(() -> {
                                showToast("변환 완료");
                                Intent intent = new Intent(CameraActivity.this, ViewerActivity.class);
                                intent.putExtra("BOOK_ID", (int) generatedBookId);
                                if (bookName != null) intent.putExtra("BOOK_NAME", bookName);
                                startActivity(intent);
                                finish();
                            });

                            // ── 3단계: 백그라운드에서 TTS 생성 + 저장 ──────────
                            generateTtsForPages(savedPages, ttsInstructions, db);
                        });
                    }

                    @Override
                    public void onFailure(@NonNull Call<OcrResponse> call, @NonNull Throwable t) {
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

            TtsRequest ttsReq = new TtsRequest(page.extractedText, instr, page.pageId,
                    currentUserSetting != null ? currentUserSetting.ttsVoice : "Cherry");
            try {
                Response<TtsResponse> ttsResp = apiService.generateTts(ttsReq).execute();

                if (ttsResp.isSuccessful()
                        && ttsResp.body() != null
                        && ttsResp.body().audio_base64 != null) {

                    byte[] audioBytes = Base64.decode(
                            ttsResp.body().audio_base64, Base64.DEFAULT);

                    String voice = (currentUserSetting != null && currentUserSetting.ttsVoice != null)
                            ? currentUserSetting.ttsVoice : "Cherry";

                    File audioFile = new File(getFilesDir(),
                            "tts_book" + page.bookId + "_page" + page.pageNumber + "_" + voice + ".wav");

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
            InputStream in  = getContentResolver().openInputStream(uri);
            File tmp        = new File(getCacheDir(),
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