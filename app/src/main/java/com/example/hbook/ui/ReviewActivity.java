package com.example.hbook.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.ExifInterface;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.Book;
import com.example.hbook.model.CapturedItem;
import com.example.hbook.model.OcrResponse;
import com.example.hbook.model.Page;
import com.example.hbook.model.TtsRequest;
import com.example.hbook.model.TtsResponse;
import com.example.hbook.model.UserSetting;
import com.example.hbook.network.ApiService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * 2단계 화면: 꼭짓점 판별 결과 검토 및 스캔 요청
 *
 * 구성:
 *   - 상단: "< 다시찍기"  |  "결과 확인 (N장)"  |  "스캔 →"
 *   - 중앙: ViewPager2 — 이미지 카드 (좌우 스와이프)
 *       각 카드:
 *         · 이미지 프리뷰 (꼭짓점 선 오버레이)
 *         · 자동인식 / 직접지정 필요 뱃지
 *         · [꼭짓점 재지정] 버튼
 *         · [사진 다시 찍기] 버튼
 *   - 하단 도트 인디케이터
 *   - 로딩 오버레이 (스캔 요청 중)
 *
 * Intent extras (입력):
 *   EXTRA_IMAGE_PATHS   String[]   이미지 파일 경로
 *   EXTRA_CORNERS_LIST  String[]   각 이미지 corners 문자열
 *   EXTRA_AUTO_FLAGS    boolean[]  자동 감지 여부
 *   "BOOK_NAME"         String     책 이름
 *
 * Intent extras (출력 없음 — 스캔 완료 후 ViewerActivity 로 직접 이동)
 */
public class ReviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATHS  = "image_paths";
    public static final String EXTRA_CORNERS_LIST = "corners_list";
    public static final String EXTRA_AUTO_FLAGS   = "auto_flags";

    private static final String TAG = "ReviewActivity";

    // ── 데이터 ───────────────────────────────────────────────────────
    private String[]  imagePaths;
    private String[]  cornersList;   // 수정 가능 — mutable copy
    private boolean[] autoFlags;
    private String    bookName;

    // 현재 CropActivity 로 수정 중인 인덱스
    private int editingIndex = -1;

    // ── UI ───────────────────────────────────────────────────────────
    private ViewPager2    viewPager;
    private LinearLayout  dotIndicator;
    private TextView      tvTitle;
    private TextView      btnScan;
    private View          loadingOverlay;
    private TextView      tvLoading;

    private ReviewAdapter adapter;

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

    private UserSetting currentUserSetting;
    private int currentUserId = -1;

    private final ApiService apiService = new Retrofit.Builder()
            .baseUrl("https://perish-impure-hatred.ngrok-free.dev/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService.class);

    // ── 꼭짓점 재지정 런처 ──────────────────────────────────────────
    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK
                        && result.getData() != null
                        && editingIndex >= 0) {
                    String newCorners = result.getData().getStringExtra(CropActivity.EXTRA_CORNERS);
                    if (newCorners != null) {
                        cornersList[editingIndex] = newCorners;
                        autoFlags[editingIndex]   = false;
                        adapter.notifyItemChanged(editingIndex);
                        updateDots();
                    }
                }
                editingIndex = -1;
            });

    // ── 사진 다시 찍기 런처 ─────────────────────────────────────────
    private final ActivityResultLauncher<Intent> retakeLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK
                        && result.getData() != null
                        && editingIndex >= 0) {
                    // RetakeActivity 가 새 파일 경로와 corners 를 돌려줌
                    String newPath    = result.getData().getStringExtra("new_image_path");
                    String newCorners = result.getData().getStringExtra(CropActivity.EXTRA_CORNERS);
                    boolean newAuto   = result.getData().getBooleanExtra("auto_detected", false);
                    if (newPath != null) {
                        imagePaths[editingIndex]  = newPath;
                        cornersList[editingIndex] = newCorners != null ? newCorners : "";
                        autoFlags[editingIndex]   = newAuto;
                        adapter.notifyItemChanged(editingIndex);
                        updateDots();
                    }
                }
                editingIndex = -1;
            });

    // ── onCreate ────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        android.content.SharedPreferences prefs =
                getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);
        currentUserSetting = AppDatabase.getInstance(this).userDao().getUserSetting(currentUserId);
        if (currentUserSetting == null)
            currentUserSetting = new UserSetting(currentUserId);

        // Intent 데이터 수신
        imagePaths  = getIntent().getStringArrayExtra(EXTRA_IMAGE_PATHS);
        String[] inputCorners = getIntent().getStringArrayExtra(EXTRA_CORNERS_LIST);
        boolean[] inputFlags  = getIntent().getBooleanArrayExtra(EXTRA_AUTO_FLAGS);
        bookName    = getIntent().getStringExtra("BOOK_NAME");

        if (imagePaths == null || imagePaths.length == 0) {
            Toast.makeText(this, "이미지가 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // mutable copy
        cornersList = inputCorners != null
                ? Arrays.copyOf(inputCorners, imagePaths.length) : new String[imagePaths.length];
        autoFlags   = inputFlags != null
                ? Arrays.copyOf(inputFlags, imagePaths.length) : new boolean[imagePaths.length];

        // UI 바인딩
        viewPager      = findViewById(R.id.view_pager);
        dotIndicator   = findViewById(R.id.dot_indicator);
        tvTitle        = findViewById(R.id.tv_title);
        btnScan        = findViewById(R.id.btn_scan);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoading      = findViewById(R.id.tv_loading);
        TextView tvBack = findViewById(R.id.tv_back);

        tvTitle.setText("결과 확인 (" + imagePaths.length + "장)");

        // ViewPager2 어댑터
        adapter = new ReviewAdapter();
        viewPager.setAdapter(adapter);

        // 양옆 카드 미리보기 (peek)
        viewPager.setOffscreenPageLimit(3);
        int peek = dpToPx(24);
        viewPager.setPadding(peek, 0, peek, 0);
        viewPager.setClipToPadding(false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots();
            }
        });

        buildDots();

        tvBack.setOnClickListener(v -> finish());
        btnScan.setOnClickListener(v -> requestScan());
    }

    // ── ViewPager2 어댑터 ────────────────────────────────────────────

    private class ReviewAdapter extends RecyclerView.Adapter<ReviewAdapter.CardHolder> {

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_review_card, parent, false);
            return new CardHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() { return imagePaths.length; }

        class CardHolder extends RecyclerView.ViewHolder {
            ImageView imgPreview;
            TextView  tvStatus;
            TextView  tvPageNum;
            TextView  btnResetCorners;
            TextView  btnRetake;

            CardHolder(@NonNull View v) {
                super(v);
                imgPreview     = v.findViewById(R.id.img_preview);
                tvStatus       = v.findViewById(R.id.tv_status);
                tvPageNum      = v.findViewById(R.id.tv_page_num);
                btnResetCorners= v.findViewById(R.id.btn_reset_corners);
                btnRetake      = v.findViewById(R.id.btn_retake);
            }

            void bind(int pos) {
                tvPageNum.setText((pos + 1) + " / " + imagePaths.length);

                // 자동/수동 뱃지
                if (autoFlags[pos]) {
                    tvStatus.setText("✓ 자동 인식됨");
                    tvStatus.setTextColor(0xFF4CAF50);
                } else if (cornersList[pos] != null && !cornersList[pos].isEmpty()) {
                    tvStatus.setText("✎ 직접 지정됨");
                    tvStatus.setTextColor(0xFFFFAA00);
                } else {
                    tvStatus.setText("⚠ 영역 지정 필요");
                    tvStatus.setTextColor(0xFFFF5555);
                }

                // 이미지 + 꼭짓점 오버레이 비동기 로드
                loadPreviewWithCorners(imagePaths[pos], cornersList[pos], imgPreview);

                // 꼭짓점 재지정
                btnResetCorners.setOnClickListener(v -> {
                    editingIndex = pos;
                    Intent intent = new Intent(ReviewActivity.this, CropActivity.class);
                    intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, imagePaths[pos]);
                    intent.putExtra(CropActivity.EXTRA_PAGE_LABEL,
                            (pos + 1) + " / " + imagePaths.length + "  꼭짓점 재지정");
                    if (cornersList[pos] != null && !cornersList[pos].isEmpty()) {
                        intent.putExtra(CropActivity.EXTRA_AUTO_CORNERS, cornersList[pos]);
                    }
                    cropLauncher.launch(intent);
                });

                // 사진 다시 찍기
                btnRetake.setOnClickListener(v -> {
                    editingIndex = pos;
                    Intent intent = new Intent(ReviewActivity.this, RetakeActivity.class);
                    intent.putExtra("position", pos);
                    retakeLauncher.launch(intent);
                });
            }
        }
    }

    // ── 이미지 + 꼭짓점 오버레이 렌더링 ────────────────────────────

    /**
     * 이미지를 비동기로 로드하고, corners 가 있으면 사각형 선을 오버레이로 그립니다.
     */
    private void loadPreviewWithCorners(String path, String cornersStr, ImageView target) {
        new Thread(() -> {
            try {
                Bitmap bmp = decodeSampledBitmap(path, 600, 800);
                if (bmp == null) return;

                float[] corners = CapturedItem.parseCorners(cornersStr);
                if (corners != null) {
                    bmp = drawCornersOverlay(bmp, corners, path);
                }

                final Bitmap finalBmp = bmp;
                runOnUiThread(() -> target.setImageBitmap(finalBmp));
            } catch (Exception e) {
                android.util.Log.e(TAG, "프리뷰 로드 실패", e);
            }
        }).start();
    }

    /**
     * 원본 픽셀 기준 corners 를 bitmap 비율에 맞게 변환하여 선을 그립니다.
     */
    private Bitmap drawCornersOverlay(Bitmap bmp, float[] corners, String imagePath) {
        // 원본 이미지 크기 (EXIF 포함)
        int origW, origH;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, opts);
            int rotation = getExifRotation(imagePath);
            if (rotation == 90 || rotation == 270) {
                origW = opts.outHeight; origH = opts.outWidth;
            } else {
                origW = opts.outWidth; origH = opts.outHeight;
            }
        } catch (Exception e) {
            return bmp;
        }

        Bitmap mutable = bmp.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas  = new Canvas(mutable);
        float  scaleX  = (float) mutable.getWidth()  / origW;
        float  scaleY  = (float) mutable.getHeight() / origH;

        // 꼭짓점 좌표 변환 (TL→TR→BR→BL)
        float[] vx = { corners[0]*scaleX, corners[2]*scaleX,
                        corners[4]*scaleX, corners[6]*scaleX };
        float[] vy = { corners[1]*scaleY, corners[3]*scaleY,
                        corners[5]*scaleY, corners[7]*scaleY };

        // 반투명 내부 채우기
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(0x334F8EF7);
        fillPaint.setStyle(Paint.Style.FILL);
        Path fillPath = new Path();
        fillPath.moveTo(vx[0], vy[0]);
        fillPath.lineTo(vx[1], vy[1]);
        fillPath.lineTo(vx[2], vy[2]);
        fillPath.lineTo(vx[3], vy[3]);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // 테두리 선
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0xFF4F8EF7);
        linePaint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
        linePaint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(fillPath, linePaint);

        // 꼭짓점 원
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xFFFF5252);
        dotPaint.setStyle(Paint.Style.FILL);
        float r = 6f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < 4; i++) canvas.drawCircle(vx[i], vy[i], r, dotPaint);

        return mutable;
    }

    // ── 도트 인디케이터 ──────────────────────────────────────────────

    private void buildDots() {
        dotIndicator.removeAllViews();
        for (int i = 0; i < imagePaths.length; i++) {
            View dot = new View(this);
            int size = dpToPx(8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.dot_inactive);
            dotIndicator.addView(dot);
        }
        updateDots();
    }

    private void updateDots() {
        int current = viewPager.getCurrentItem();
        for (int i = 0; i < dotIndicator.getChildCount(); i++) {
            View dot = dotIndicator.getChildAt(i);
            dot.setBackgroundResource(i == current
                    ? R.drawable.dot_active : R.drawable.dot_inactive);
        }
    }

    // ── 스캔 요청 ────────────────────────────────────────────────────

    /**
     * "스캔" 버튼 클릭 시:
     * 꼭짓점 지정이 필요한 항목이 있으면 경고 → 확인 후 /api/scan 요청
     */
    private void requestScan() {
        // 지정 안 된 항목 수 체크
        int missing = 0;
        for (int i = 0; i < imagePaths.length; i++) {
            if (cornersList[i] == null || cornersList[i].isEmpty()) missing++;
        }

        if (missing > 0) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("영역 미지정 항목")
                    .setMessage(missing + "장의 영역이 지정되지 않았어요.\n"
                            + "그대로 스캔하면 전체 이미지가 처리돼요. 계속할까요?")
                    .setPositiveButton("계속", (d, w) -> uploadToServer())
                    .setNegativeButton("취소", null)
                    .show();
        } else {
            uploadToServer();
        }
    }

    private void uploadToServer() {
        showLoading(true, "스캔 중...");

        List<MultipartBody.Part> imageParts   = new ArrayList<>();
        List<MultipartBody.Part> cornersParts = new ArrayList<>();

        for (int i = 0; i < imagePaths.length; i++) {
            File f = new File(imagePaths[i]);
            RequestBody body = RequestBody.create(MediaType.parse("image/jpeg"), f);
            imageParts.add(MultipartBody.Part.createFormData("image", f.getName(), body));
            String c = (cornersList[i] != null) ? cornersList[i] : "";
            cornersParts.add(MultipartBody.Part.createFormData("corners_" + i, c));
        }

        RequestBody pageNum = RequestBody.create(MediaType.parse("text/plain"), "1");

        apiService.uploadMultipleImages(imageParts, cornersParts, pageNum)
                .enqueue(new Callback<OcrResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<OcrResponse> call,
                                           @NonNull Response<OcrResponse> response) {
                        showLoading(false, null);

                        if (!response.isSuccessful() || response.body() == null
                                || !"success".equals(response.body().status)) {
                            Toast.makeText(ReviewActivity.this,
                                    "서버 응답 오류", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // DB 저장 + TTS + ViewerActivity 이동
                        saveAndNavigate(response.body());
                    }

                    @Override
                    public void onFailure(@NonNull Call<OcrResponse> call, @NonNull Throwable t) {
                        showLoading(false, null);
                        Toast.makeText(ReviewActivity.this,
                                "연결 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** DB 저장 후 ViewerActivity 로 이동 (기존 CameraActivity 로직 그대로) */
    private void saveAndNavigate(OcrResponse ocrData) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            AppDatabase db        = AppDatabase.getInstance(this);
            String      title     = (bookName != null) ? bookName : "무제";
            Book        book      = new Book(title, currentUserId);
            long        bookId    = db.libraryDao().insertBook(book);

            List<Page>   saved    = new ArrayList<>();
            List<String> instrs   = new ArrayList<>();

            if (ocrData.results != null) {
                for (int i = 0; i < ocrData.results.size(); i++) {
                    OcrResponse.PageResult pd = ocrData.results.get(i);
                    if (pd.extracted_text == null) continue;
                    Page p = new Page((int) bookId, i + 1, pd.extracted_text);
                    if (pd.sentiment != null) {
                        p.emotionValence = pd.sentiment.valence;
                        p.emotionArousal = pd.sentiment.arousal;
                        p.emotionLabel   = pd.sentiment.label != null ? pd.sentiment.label : "";
                    }
                    db.libraryDao().insertPage(p);
                    saved.add(p);
                    instrs.add(pd.sentiment != null && pd.sentiment.tts_instruction != null
                            ? pd.sentiment.tts_instruction : "자연스럽고 차분한 목소리로 읽어주세요.");
                }
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "변환 완료!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, ViewerActivity.class);
                intent.putExtra("BOOK_ID", (int) bookId);
                if (bookName != null) intent.putExtra("BOOK_NAME", bookName);
                startActivity(intent);
                finish();
            });

            // TTS 백그라운드 생성
            generateTts(saved, instrs, db);
        });
    }

    private void generateTts(List<Page> pages, List<String> instrs, AppDatabase db) {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.extractedText == null || p.extractedText.isEmpty()) continue;
            TtsRequest req = new TtsRequest(p.extractedText, instrs.get(i), p.pageId,
                    currentUserSetting != null ? currentUserSetting.ttsVoice : "Cherry");
            try {
                Response<TtsResponse> r = apiService.generateTts(req).execute();
                if (r.isSuccessful() && r.body() != null && r.body().audio_base64 != null) {
                    byte[] bytes = Base64.decode(r.body().audio_base64, Base64.DEFAULT);
                    String voice = currentUserSetting != null ? currentUserSetting.ttsVoice : "Cherry";
                    File   af    = new File(getFilesDir(),
                            "tts_book" + p.bookId + "_page" + p.pageNumber + "_" + voice + ".wav");
                    try (FileOutputStream fos = new FileOutputStream(af)) { fos.write(bytes); }
                    db.libraryDao().updateAudioFilePath(p.pageId, af.getAbsolutePath());
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "TTS 실패 page " + p.pageNumber, e);
            }
        }
    }

    // ── 유틸 ────────────────────────────────────────────────────────

    private void showLoading(boolean show, String msg) {
        runOnUiThread(() -> {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            if (msg != null) tvLoading.setText(msg);
            btnScan.setEnabled(!show);
        });
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        if (opts.outHeight > reqH || opts.outWidth > reqW)
            sample = Math.max(Math.round((float)opts.outHeight/reqH),
                              Math.round((float)opts.outWidth/reqW));
        int rotation = getExifRotation(path);
        opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) return null;
        if (rotation != 0) {
            Matrix m = new Matrix(); m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            bmp.recycle(); return r;
        }
        return bmp;
    }

    private static int getExifRotation(String path) {
        try {
            ExifInterface e = new ExifInterface(path);
            int ori = e.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90)  return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {}
        return 0;
    }
}
