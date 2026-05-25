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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
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
import com.example.hbook.network.ApiService;
import com.example.hbook.model.OcrResponse;
import com.example.hbook.R;
import com.google.common.util.concurrent.ListenableFuture;

import org.w3c.dom.Text;

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

    private static final String TAG = "CameraActivity";

    private PreviewView viewFinder;
    private ImageCapture imageCapture; // 사진을 캡처하는 역할
    private ExecutorService cameraExecutor; // 카메라 작업을 처리할 별도의 스레드
    private String bookName;  // 이전 화면에서 넘겨받은 책 이름
    private List<File> capturedFiles = new ArrayList<>();  // 찍은 사진들 모아둘 리스트
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

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    private final Retrofit retrofit = new Retrofit.Builder()
            .baseUrl("https://egal-furcately-nydia.ngrok-free.dev/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build();

    private final ApiService apiService = retrofit.create(ApiService.class);

    // 갤러리에서 사진을 골랐을 때 결과 받아옴
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

        // 이전 화면에서 넘겨준 책 이름 있는지 확인
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

    // 서버로 사진들 전송하는 함수
    private void uploadMultipleToServer(List<File> fileList) {
        List<MultipartBody.Part> parts = new ArrayList<>();
        for (File file : fileList) {
            RequestBody requestFile = RequestBody.create(MediaType.parse("image/jpeg"), file);
            parts.add(MultipartBody.Part.createFormData("image", file.getName(), requestFile));
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
        RequestBody pageNumBody = RequestBody.create(MediaType.parse("text/plain"), "1");

        RequestBody pageNumBody = RequestBody.create(
                MediaType.parse("text/plain"), "1");

        apiService.uploadMultipleImages(parts, pageNumBody).enqueue(new Callback<OcrResponse>() {
        Call<OcrResponse> call = apiService.uploadMultipleImages(
                imageParts, cornersParts, pageNumBody);

        call.enqueue(new Callback<OcrResponse>() {
            @Override
            public void onResponse(@NonNull Call<OcrResponse> call,
                                   @NonNull Response<OcrResponse> response) {

                if (!response.isSuccessful() || response.body() == null) {
                    showToast("서버 응답 오류 (JSON 파싱 에러)");
                    resetButton();
                    cleanupFiles(capturedFiles);
                    return;
                }

                OcrResponse ocrData = response.body();
                if (!"success".equals(ocrData.status)) {
                    showToast("서버 응답 오류");
                    resetButton();
                    cleanupFiles(capturedFiles);
                    return;
                }

                // DB 저장은 백그라운드에서
                ExecutorService executor = Executors.newSingleThreadExecutor();
                executor.execute(() -> {
                    AppDatabase db       = AppDatabase.getInstance(CameraActivity.this);
                    String bookTitle     = (bookName != null) ? bookName : "무제";
                    Book newBook         = new Book(bookTitle, currentUserId);
                    long generatedBookId = db.libraryDao().insertBook(newBook);
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

                    // ── 1단계: 페이지를 DB 에 모두 저장 ─────────────────
                    List<Page>   savedPages      = new ArrayList<>();
                    List<String> ttsInstructions = new ArrayList<>();

                    if (ocrData.results != null && !ocrData.results.isEmpty()) {
                        for (int i = 0; i < ocrData.results.size(); i++) {
                            OcrResponse.PageResult pageData = ocrData.results.get(i);
                            if (pageData.extracted_text == null) continue;

                            Page newPage = new Page((int) generatedBookId, i + 1, pageData.extracted_text);
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
                        // 단일 페이지 응답
                        Page newPage = new Page((int) generatedBookId, 1, ocrData.extracted_text);
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
                    // ── 2단계: 먼저 ViewerActivity 로 이동 ──────────────
                    // TTS 생성은 오래 걸리므로 화면 전환을 먼저 하고
                    // 이 스레드가 계속 백그라운드에서 TTS 를 생성합니다.
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

                cleanupFiles(capturedFiles);
                capturedFiles.clear();
            }

            @Override
            public void onFailure(Call<OcrResponse> call, Throwable t) {
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "서버 오류: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    btnSendMultiple.setEnabled(true);
                    btnSendMultiple.setText(capturedItems.size() + "장 변환");
                });
            public void onFailure(@NonNull Call<OcrResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "스캔 요청 실패", t);
                showToast("서버 연결 실패");
                resetButton();
                cleanupFiles(fileList);
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

    private void generateTtsForPages(List<Page> pages,
                                     List<String> instructions,
                                     AppDatabase db) {
        for (int i = 0; i < pages.size(); i++) {
            Page   page  = pages.get(i);
            String instr = instructions.get(i);

            if (page.extractedText == null || page.extractedText.isEmpty()) continue;

            TtsRequest ttsReq = new TtsRequest(page.extractedText, instr, page.pageId);

            try {
                // .execute() 로 동기 호출 (이미 백그라운드 스레드이므로 안전)
                Response<TtsResponse> ttsResp = apiService.generateTts(ttsReq).execute();

                if (ttsResp.isSuccessful()
                        && ttsResp.body() != null
                        && ttsResp.body().audio_base64 != null) {

                    byte[] audioBytes = Base64.decode(ttsResp.body().audio_base64, Base64.DEFAULT);

                    // filesDir: 앱 삭제 전까지 보존되는 내부 저장소
                    File audioFile = new File(
                            getFilesDir(),
                            "tts_book" + page.bookId + "_page" + page.pageNumber + ".wav"
                    );

                    try (FileOutputStream fos = new FileOutputStream(audioFile)) {
                        fos.write(audioBytes);
                    }

                    // DB 에 경로 기록
                    db.libraryDao().updateAudioFilePath(page.pageId, audioFile.getAbsolutePath());
                    Log.d(TAG, "TTS 저장 완료: " + audioFile.getName());

                } else {
                    Log.w(TAG, "TTS 응답 오류 — page " + page.pageNumber);
                }

            } catch (Exception e) {
                // TTS 실패해도 나머지 페이지는 계속 처리
                // ViewerActivity 폴백이 null 경로를 감지해 실시간 요청으로 전환
                Log.e(TAG, "TTS 예외 — page " + page.pageNumber + ": " + e.getMessage());
            }
        }

        Log.d(TAG, "전체 페이지 TTS 생성 완료 (" + pages.size() + "페이지)");
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(CameraActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private void resetButton() {
        runOnUiThread(() -> {
            btnSendMultiple.setEnabled(true);
            btnSendMultiple.setText(capturedFiles.size() + "장 변환");
        });
    }

    private void cleanupFiles(List<File> files) {
        for (File f : files) { if (f != null && f.exists()) f.delete(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
