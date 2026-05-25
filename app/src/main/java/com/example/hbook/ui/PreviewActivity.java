package com.example.hbook.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hbook.R;
import com.example.hbook.network.ApiService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 실험용 전처리 결과 확인 화면.
 * CameraActivity 에서 CapturedItem 리스트를 받아 /api/preview-crop 으로 전송하고,
 * 응답 HTML 을 WebView 로 표시합니다.
 *
 * Intent extras (입력):
 *   EXTRA_IMAGE_PATHS  (String[]) - 이미지 파일 경로 배열
 *   EXTRA_CORNERS_LIST (String[]) - 각 이미지의 corners 문자열 배열
 */
public class PreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATHS  = "image_paths";
    public static final String EXTRA_CORNERS_LIST = "corners_list";

    private WebView      webView;
    private LinearLayout loadingView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        webView     = findViewById(R.id.web_view);
        loadingView = findViewById(R.id.loading_view);
        TextView tvClose = findViewById(R.id.tv_close);

        tvClose.setOnClickListener(v -> finish());

        // WebView 설정 - 이미지 저장을 위해 JS 활성화
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loadingView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
        });

        // Intent 에서 데이터 수신
        String[] imagePaths  = getIntent().getStringArrayExtra(EXTRA_IMAGE_PATHS);
        String[] cornersList = getIntent().getStringArrayExtra(EXTRA_CORNERS_LIST);

        if (imagePaths == null || cornersList == null || imagePaths.length == 0) {
            Toast.makeText(this, "전달된 이미지가 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        sendPreviewRequest(imagePaths, cornersList);
    }

    private void sendPreviewRequest(String[] imagePaths, String[] cornersList) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://egal-furcately-nydia.ngrok-free.dev/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        ApiService apiService = retrofit.create(ApiService.class);

        List<MultipartBody.Part> imageParts  = new ArrayList<>();
        List<MultipartBody.Part> cornersParts = new ArrayList<>();

        for (int i = 0; i < imagePaths.length; i++) {
            // 이미지 파트
            File file = new File(imagePaths[i]);
            RequestBody requestFile = RequestBody.create(MediaType.parse("image/jpeg"), file);
            imageParts.add(MultipartBody.Part.createFormData("image", file.getName(), requestFile));

            // corners 파트
            String cornersStr = (i < cornersList.length && cornersList[i] != null)
                    ? cornersList[i] : "";
            cornersParts.add(MultipartBody.Part.createFormData("corners_" + i, cornersStr));
        }

        apiService.previewCrop(imageParts, cornersParts).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String html = response.body().string();
                        // base64 이미지가 포함된 HTML 이므로 loadDataWithBaseURL 사용
                        webView.loadDataWithBaseURL(
                                null, html, "text/html", "UTF-8", null);
                    } catch (Exception e) {
                        showError("HTML 파싱 실패: " + e.getMessage());
                    }
                } else {
                    showError("서버 응답 오류: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                showError("요청 실패: " + t.getMessage());
            }
        });
    }

    private void showError(String msg) {
        runOnUiThread(() -> {
            loadingView.setVisibility(View.GONE);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }
}
