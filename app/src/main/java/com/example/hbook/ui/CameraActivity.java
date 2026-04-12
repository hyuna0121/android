package com.example.hbook.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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

    private PreviewView viewFinder;
    private ImageCapture imageCapture; // 사진을 캡처하는 역할
    private ExecutorService cameraExecutor; // 카메라 작업을 처리할 별도의 스레드
    private String bookNameFromIntent;  // 이전 화면에서 넘겨받은 책 이름
    private List<File> capturedFiles = new ArrayList<>();  // 찍은 사진들 모아둘 리스트
    private TextView btnSendMultiple;

    // 갤러리에서 사진을 골랐을 때 결과 받아옴
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris != null &&!uris.isEmpty()) {
                    Toast.makeText(this, uris.size() + "장의 사진을 불러왔습니다.", Toast.LENGTH_SHORT).show();

                    List<File> fileList = new ArrayList<>();
                    for (Uri uri : uris) {
                        File file = uriToFile(uri);
                        if (file != null) fileList.add(file);
                    }

                    uploadMultipleToServer(fileList);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // 이전 화면에서 넘겨준 책 이름 있는지 확인
        bookNameFromIntent = getIntent().getStringExtra("BOOK_NAME");

        viewFinder = findViewById(R.id.viewFinder);
        ImageView btnGallery = findViewById(R.id.btn_gallery);
        View btnCapture = findViewById(R.id.btn_capture);
        TextView tvBack = findViewById(R.id.tv_back);
        TextView tvBookTitle = findViewById(R.id.tv_book_title);
        btnSendMultiple = findViewById(R.id.btn_send_multiple);

        if (bookNameFromIntent != null) {
            tvBookTitle.setText(bookNameFromIntent);
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
        if (bookNameFromIntent != null) {
            // 메인화면에서 넘어온 경우
            new AlertDialog.Builder(this)
                    .setTitle("스캔 취소")
                    .setMessage("지금 돌아가면 '" + bookNameFromIntent + "' 추가가 취소됩니다. 돌아가시겠습니까?")
                    .setPositiveButton("예", (dialog, which) -> finish())
                    .setNegativeButton("아니요", (dialog, which) -> dialog.cancel())
                    .show();

        } else {
            finish();
        }
    }

    // 안드로이드 사진첨 데이터를 실제 파일로 복사
    private File uriToFile(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            File tempFile = new File(getCacheDir(), "temp_gallery_ocr.jpg");
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

        // 여러 개의 파일 담을 리스트
        List<MultipartBody.Part> parts = new ArrayList<>();

        for (File file : fileList) {
            RequestBody requestFile = RequestBody.create(MediaType.parse("image/jpeg"), file);
            MultipartBody.Part body = MultipartBody.Part.createFormData("image", file.getName(), requestFile);
            parts.add(body);
        }

        RequestBody pageNumBody = RequestBody.create(MediaType.parse("text/plain"), "1");

        // 리스트 전송
        Call<OcrResponse> call = apiService.uploadMultipleImages(parts, pageNumBody);
        call.enqueue(new Callback<OcrResponse>() {
            @Override
            public void onResponse(Call<OcrResponse> call, Response<OcrResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    OcrResponse ocrData = response.body();

                    if ("success".equals(ocrData.status) && ocrData.results != null) {
                        StringBuilder fullText = new StringBuilder();

                        if (ocrData.results != null && !ocrData.results.isEmpty()) {
                            for (OcrResponse.PageResult page : ocrData.results) {
                                if (page.extracted_text != null) {
                                    fullText.append(page.extracted_text).append("\n\n");
                                }
                            }
                        } else if (ocrData.extracted_text != null) {
                            fullText.append(ocrData.extracted_text);
                        }

                        Toast.makeText(CameraActivity.this, "변환 완료", Toast.LENGTH_SHORT).show();

                        // 1. 텍스트뷰로 이동하기 위한 인텐트 생성
                        Intent intent = new Intent(CameraActivity.this, ViewerActivity.class);

                        // 2. 합친 텍스트와 책 제목을 함께 보내기
                        intent.putExtra("OCR_TEXT", fullText.toString().trim());
                        if (bookNameFromIntent != null) {
                            intent.putExtra("BOOK_NAME", bookNameFromIntent);
                        }

                        // 3. 텍스트뷰로 이동
                        startActivity(intent);
                        finish();
                    } else {
                        Toast.makeText(CameraActivity.this, "서버 응답 오류", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(CameraActivity.this, "서버 응답 오류 (JSON 파싱 에러)", Toast.LENGTH_SHORT).show();
                }

                for (File f : capturedFiles) { if (f.exists()) f.delete(); }
                capturedFiles.clear();
            }

            @Override
            public void onFailure(Call<OcrResponse> call, Throwable t) {
                for (File f : fileList) { if (f.exists()) f.delete(); }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}