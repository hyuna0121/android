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
    private TextView btnSendMultiple;
    private int currentUserId = -1;

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
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris != null &&!uris.isEmpty()) {
                    List<Uri> selectedUris = new ArrayList<>(uris);

                    selectedUris.sort((uri1, uri2) -> {
                        String name1 = getFileName(uri1);
                        String name2 = getFileName(uri2);
                        return name1.compareTo(name2);
                    });

                    for (Uri uri : selectedUris) {
                        File tempFile = uriToFile(uri);
                        if (tempFile != null) {
                            capturedFiles.add(tempFile);
                        }
                    }

                    btnSendMultiple.setText(capturedFiles.size() + "장 변환");
                    btnSendMultiple.setVisibility(View.VISIBLE);
                    Toast.makeText(this, capturedFiles.size() + "장의 사진을 가져왔습니다.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        setContentView(R.layout.activity_camera);

        // 이전 화면에서 넘겨준 책 이름 있는지 확인
        bookName = getIntent().getStringExtra("BOOK_NAME");

        viewFinder = findViewById(R.id.viewFinder);
        ImageView btnGallery = findViewById(R.id.btn_gallery);
        View btnCapture = findViewById(R.id.btn_capture);
        TextView tvBack = findViewById(R.id.tv_back);
        TextView tvBookTitle = findViewById(R.id.tv_book_title);
        btnSendMultiple = findViewById(R.id.btn_send_multiple);

        if (bookName != null) {
            tvBookTitle.setText(bookName);
        }

        // 1. 카메라 권한이 있는지 확인
        if (allPermissionsGranted()) {
            startCamera(); // 권한이 있으면 카메라 켜기
        } else {
            // 권한이 없으면 사용자에게 권한 요청 팝업 띄우기
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        // 2. 뒤로가기 로직
        tvBack.setOnClickListener(v -> handleBackButton());

        // 3. 촬영 로직
        btnCapture.setOnClickListener(v -> takePhoto());

        // 4. 갤러리 로직
        btnGallery.setOnClickListener(v -> {
            galleryLauncher.launch("image/*");
        });

        // 5. 변환하기 로직
        btnSendMultiple.setOnClickListener(v -> {
            if (!capturedFiles.isEmpty()) {
                Toast.makeText(this, capturedFiles.size() +  "장 변환을 시작합니다.", Toast.LENGTH_SHORT).show();

                btnSendMultiple.setEnabled(false);
                btnSendMultiple.setText("변환 중...");

                uploadMultipleToServer(capturedFiles);
            }
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // 뒤로가기 눌렀을 때 동작 처리
    private void handleBackButton() {
        if (bookName != null) {
            // 메인화면에서 넘어온 경우
            new AlertDialog.Builder(this)
                    .setTitle("스캔 취소")
                    .setMessage("지금 돌아가면 '" + bookName + "' 추가가 취소됩니다. 돌아가시겠습니까?")
                    .setPositiveButton("예", (dialog, which) -> finish())
                    .setNegativeButton("아니요", (dialog, which) -> dialog.cancel())
                    .show();

        } else {
            finish();
        }
    }

    // 안드로이드 사진첩 데이터를 실제 파일로 복사
    private File uriToFile(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            File tempFile = new File(getCacheDir(), "temp_ocr_" + UUID.randomUUID().toString() + ".jpg");
            FileOutputStream out = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.close();
            in.close();
            return tempFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 카메라 화면을 띄우는 함수
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 뷰파인더(미리보기) 설정
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // 사진 캡처 설정
                imageCapture = new ImageCapture.Builder().build();

                // 후면 카메라 기본 선택
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 기존에 연결된 카메라가 있다면 해제하고 새로 바인딩
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "카메라 연결 실패", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // 사진을 찍고 저장하는 함수
    private void takePhoto() {
        if (imageCapture == null) return;

        // 사진이 저장될 폴더와 파일 이름 만들기 (임시 캐시 폴더에 저장)
        String timeStamp = String.valueOf(System.currentTimeMillis());  // 파일 덮어쓰기 방지
        File photoFile = new File(getCacheDir(), "temp_ocr_" + timeStamp + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // 사진 찍음
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                capturedFiles.add(photoFile);

                btnSendMultiple.setText(capturedFiles.size() + "장 변환");
                btnSendMultiple.setVisibility(View.VISIBLE);

                Toast.makeText(CameraActivity.this, "사진 촬영됨 (" + capturedFiles.size() + "장)", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e("CameraX", "사진 저장 실패: " + exception.getMessage(), exception);
            }
        });
    }

    // 권한 확인용 유틸리티 함수
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // 서버로 사진들 전송하는 함수
    private void uploadMultipleToServer(List<File> fileList) {
        List<MultipartBody.Part> parts = new ArrayList<>();
        for (File file : fileList) {
            RequestBody requestFile = RequestBody.create(MediaType.parse("image/jpeg"), file);
            parts.add(MultipartBody.Part.createFormData("image", file.getName(), requestFile));
        }
        RequestBody pageNumBody = RequestBody.create(MediaType.parse("text/plain"), "1");

        apiService.uploadMultipleImages(parts, pageNumBody).enqueue(new Callback<OcrResponse>() {
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
            public void onFailure(@NonNull Call<OcrResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "스캔 요청 실패", t);
                showToast("서버 연결 실패");
                resetButton();
                cleanupFiles(fileList);
            }
        });
    }

    // 원본 파일 이름 알아내는 함수
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result ==  null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }

        return result != null ? result : "unknown";
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
        cameraExecutor.shutdown();
    }
}