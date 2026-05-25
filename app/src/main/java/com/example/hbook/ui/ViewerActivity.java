package com.example.hbook.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.data.LibraryDao;
import com.example.hbook.model.Page;
import com.example.hbook.model.TtsRequest;
import com.example.hbook.model.TtsResponse;
import com.example.hbook.model.UserSetting;
import com.example.hbook.network.ApiService;
import com.example.hbook.util.EmotionTtsHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ViewerActivity extends AppCompatActivity {

    private static final String TAG = "ViewerActivity";

    private boolean isTopBarVisible    = true;
    private boolean isBottomBarVisible = true;
    private boolean isPlaying          = false;

    private LibraryDao libraryDao;

    private View topBar;
    private View bottomBar;
    private ImageView btnTtsPlay;
    private ViewPager2 viewPager;

    private TextToSpeech androidTts;
    private boolean isAndroidTtsReady = false;

    private MediaPlayer mediaPlayer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 책 전체 페이지의 valence/arousal 평균값
    private float avgValence = 0f;
    private float avgArousal = 0f;

    private ApiService apiService;

    // 사용자 뷰 설정
    float userFontSize    = 20f;
    float userLineSpacing = 1.5f;
    int   userFontColor   = 0xFF000000;

    private static class WordToken {
        final String word;
        final int globalStart;
        final int globalEnd;

        WordToken(String word, int globalStart, int globalEnd) {
            this.word = word;
            this.globalStart = globalStart;
            this.globalEnd = globalEnd;
        }
    }

    private  List<WordToken> wordTokens = new ArrayList<>();
    private List<Integer> pageStartIndex = new ArrayList<>();
    private List<Page> dbPages = new ArrayList<>();
    private int currentWordIdx = 0;
    private PageAdapter pageAdapter = null;
    private String fullText = "";

    private final Handler mainHandelr = new Handler(Looper.getMainLooper());
    private UserSetting currentUserSetting;
    private int UserFontColor = 0xFF000000;
    private int currentUserId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        libraryDao = AppDatabase.getInstance(this).libraryDao();

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        currentUserSetting = AppDatabase.getInstance(this).userDao().getUserSetting(currentUserId);

        if (currentUserSetting == null) {
            currentUserSetting = new UserSetting(currentUserId);
        }

        viewPager  = findViewById(R.id.view_pager);
        viewPager.setBackgroundColor(Color.parseColor(currentUserSetting.backgroundColor));

        switch (currentUserSetting.backgroundColor) {
            case "#F5F5F5": userFontColor = Color.parseColor("#333333"); break;
            case "#E0E0E0": userFontColor = Color.parseColor("#000000"); break;
            case "#424242": userFontColor = Color.parseColor("#E0E0E0"); break;
            case "#000000": userFontColor = Color.parseColor("#FFFFFF"); break;
            case "#F6F1E5": userFontColor = Color.parseColor("#4E3726"); break;
            case "#233E3B": userFontColor = Color.parseColor("#BDD9B9"); break;
            default: userFontColor = Color.BLACK; break;
        }

        topBar     = findViewById(R.id.top_bar);
        bottomBar  = findViewById(R.id.bottom_bar);
        btnTtsPlay = findViewById(R.id.btn_tts_play);
        TextView tvBack      = findViewById(R.id.tv_back);
        TextView tvBookTitle = findViewById(R.id.tv_viewer_title);

        tvBack.setOnClickListener(v -> finish());

        // 데이터 받기
        int bookId      = getIntent().getIntExtra("BOOK_ID", -1);
        String bookName = getIntent().getStringExtra("BOOK_NAME");
        if (bookName != null) tvBookTitle.setText(bookName);

        // DB에서 페이지 목록 로드
        List<Page> dbPages = new ArrayList<>();
        if (bookId != -1) {
            dbPages = libraryDao.getPagesForBook(bookId);
        }

        // ── valence / arousal 평균 계산 ─────────────────────────────────────
        if (!dbPages.isEmpty()) {
            float sumValence = 0f;
            float sumArousal = 0f;
            for (Page p : dbPages) {
                sumValence += p.emotionValence;
                sumArousal += p.emotionArousal;
            }
            avgValence = sumValence / dbPages.size();
            avgArousal = sumArousal / dbPages.size();
        }
        // ────────────────────────────────────────────────────────────────────

        // 전체 텍스트 합치기
        StringBuilder fullTextBuilder = new StringBuilder();
        for (Page p : dbPages) {
            fullTextBuilder.append(p.extractedText).append("\n\n");
        }
        fullText = fullTextBuilder.toString().trim();
        if (fullText.isEmpty()) fullText = "저장된 내용이 없습니다.";

        buildWordTokens(fullText);

        // 화면 크기 확정 후 페이지 분할
        viewPager.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        viewPager.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        paginateTextAndSetAdapter(fullText, userFontColor);
                    }
                });

        // ── TTS 초기화 ───────────────────────────────────────────────────────
        androidTts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = androidTts.setLanguage(new Locale("ko", "KR"));
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    androidTts.setLanguage(Locale.getDefault());
                }

                // 읽기 완료 시 버튼 재생 아이콘으로 복귀
                androidTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}

                    @Override
                    public void onDone(String id) {
                        if (!isPlaying) return;

                        currentWordIdx++;
                        if (pageAdapter != null && currentWordIdx < pageAdapter.getPageCount()) {
                            mainHandelr.post(() -> {
                                viewPager.setCurrentItem(currentWordIdx, true);
                                speakNextWord();
                            });
                        } else {
                            mainHandelr.post(() -> {
                                setPlayingState(false);
                                currentWordIdx = 0;
                                if (pageAdapter != null) pageAdapter.clearHighlight();
                            });
                        }
                    }

                    @Override
                    public void onError(String id) {
                        mainHandler.post(() -> setPlayingState(false));
                    }

                    @Override
                    public void onRangeStart(String utteranceId, int start, int end, int frame) {
                        mainHandelr.post(() -> {
                            if (pageAdapter != null && isPlaying) {
                                pageAdapter.setHighlight(currentWordIdx, start, end);
                            }
                        });
                    }
                });

                isAndroidTtsReady = true;
            }
        });

        // ── Retrofit 초기화 (폴백 실시간 TTS 요청용) ─────────────────────────────
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
        apiService = new Retrofit.Builder()
                .baseUrl("https://egal-furcately-nydia.ngrok-free.dev/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService.class);

        // ── TTS 재생 버튼 ────────────────────────────────────────────────────
        btnTtsPlay.setOnClickListener(v -> {
            if (isPlaying) {
                stopPlayback();
            } else {
                setPlayingState(true);
                speakNextPage();
            }
        });

        // 화면 터치 시 상단·하단 바 토글
        viewPager.setOnClickListener(v -> toggleBars());
        // ────────────────────────────────────────────────────────────────────
    }

    private void speakNextPage() {
        if (!isPlaying || pageAdapter == null) return;
        if (currentWordIdx >= pageAdapter.getPageCount()) return;

        String pageText = pageAdapter.getPageText(currentWordIdx);
        if (pageText == null || pageText.isEmpty()) {
            advanceToNextPage();
            return;
        }

        // 현재 화면 페이지 인덱스를 DB 페이지와 매핑
        Page currentPage = getDbPageForViewerIndex(currentWordIdx);

        // ── 경로 1: 로컬 파일 재생 ──────────────────────────────
        if (currentPage != null
                && currentPage.audioFilePath != null
                && new File(currentPage.audioFilePath).exists()) {

            playLocalAudio(currentPage.audioFilePath);
            return;
        }

        // ── 경로 2: /api/tts 실시간 요청 ────────────────────────
        if (currentPage != null
                && currentPage.emotionLabel != null
                && !currentPage.emotionLabel.isEmpty()) {

            requestTtsFromServer(currentPage, pageText);
            return;
        }

        // ── 경로 3: Android 기본 TTS 폴백 ───────────────────────
        speakWithAndroidTts(pageText);
    }

    private void playLocalAudio(String filePath) {
        try {
            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> mainHandler.post(this::advanceToNextPage));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 오류: what=" + what);
                mainHandler.post(() -> speakWithAndroidTts(
                        pageAdapter != null ? pageAdapter.getPageText(currentWordIdx) : ""));
                return true;
            });
            mediaPlayer.start();
            Log.d(TAG, "로컬 재생: " + new File(filePath).getName());
        } catch (Exception e) {
            Log.e(TAG, "로컬 재생 실패: " + e.getMessage());
            // 파일 깨짐 → 실시간 요청으로 강등
            Page p = getDbPageForViewerIndex(currentWordIdx);
            if (p != null && p.extractedText != null) requestTtsFromServer(p, p.extractedText);
            else speakWithAndroidTts(pageAdapter != null ? pageAdapter.getPageText(currentWordIdx) : "");
        }
    }

    private void requestTtsFromServer(Page page, String pageText) {
        // tts_instruction 이 DB 에 없으면 감정 레이블로 기본 지시문 생성
        String instruction = buildFallbackInstruction(page.emotionLabel);

        TtsRequest req = new TtsRequest(pageText, instruction, page.pageId);
        apiService.generateTts(req).enqueue(new Callback<TtsResponse>() {
            @Override
            public void onResponse(@NonNull Call<TtsResponse> call,
                                   @NonNull Response<TtsResponse> response) {
                if (!isPlaying) return;

                if (response.isSuccessful()
                        && response.body() != null
                        && response.body().audio_base64 != null) {

                    byte[] audioBytes = Base64.decode(response.body().audio_base64, Base64.DEFAULT);

                    // 받은 오디오를 filesDir 에 캐싱하고 DB 업데이트
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        try {
                            File audioFile = new File(
                                    getFilesDir(),
                                    "tts_book" + page.bookId + "_page" + page.pageNumber + ".wav"
                            );
                            try (FileOutputStream fos = new FileOutputStream(audioFile)) {
                                fos.write(audioBytes);
                            }
                            AppDatabase.getInstance(ViewerActivity.this)
                                    .libraryDao()
                                    .updateAudioFilePath(page.pageId, audioFile.getAbsolutePath());

                            // 파일 저장 완료 후 재생
                            mainHandler.post(() -> playLocalAudio(audioFile.getAbsolutePath()));

                        } catch (Exception e) {
                            Log.e(TAG, "TTS 캐싱 실패: " + e.getMessage());
                            mainHandler.post(() -> speakWithAndroidTts(pageText));
                        }
                    });

                } else {
                    // 서버 오류 → Android TTS 로 폴백
                    mainHandler.post(() -> speakWithAndroidTts(pageText));
                }
            }

            @Override
            public void onFailure(@NonNull Call<TtsResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "TTS 서버 요청 실패: " + t.getMessage());
                mainHandler.post(() -> speakWithAndroidTts(pageText));
            }
        });
    }

    private void speakWithAndroidTts(String text) {
        if (!isAndroidTtsReady || androidTts == null || text.isEmpty()) return;

        // 기존 EmotionTtsHelper 로직 인라인 적용
        float arousalRange = (1.6f - 0.6f) / 2f;
        float speechRate   = 1.0f + (avgArousal * arousalRange);
        if (avgValence < -0.5f) speechRate -= (-avgValence - 0.5f) * 0.3f;
        speechRate = Math.max(0.6f, Math.min(1.6f, speechRate));

        float pitchRange = (1.4f - 0.7f) / 2f;
        float pitch      = 1.0f + (avgValence * pitchRange);
        if (avgArousal > 0.5f && avgValence > 0f) pitch += avgArousal * 0.1f;
        pitch = Math.max(0.7f, Math.min(1.4f, pitch));

        androidTts.setSpeechRate(speechRate);
        androidTts.setPitch(pitch);
        androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "page_" + currentWordIdx);
    }

    private void advanceToNextPage() {
        if (!isPlaying) return;
        currentWordIdx++;
        if (pageAdapter != null && currentWordIdx < pageAdapter.getPageCount()) {
            viewPager.setCurrentItem(currentWordIdx, true);
            speakNextPage();
        } else {
            setPlayingState(false);
            currentWordIdx = 0;
            if (pageAdapter != null) pageAdapter.clearHighlight();
        }
    }

    private void stopPlayback() {
        setPlayingState(false);
        releaseMediaPlayer();
        if (androidTts != null) androidTts.stop();
        if (pageAdapter != null) pageAdapter.clearHighlight();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private void buildWordTokens(String text) {
        wordTokens.clear();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length()) break;
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            wordTokens.add(new WordToken(
                    text.substring(start, i), start, i));
        }
    }

    private void speakNextWord() {
        if (!isPlaying || pageAdapter == null) return;
        if (currentWordIdx >= pageAdapter.getPageCount()) return;

        String pageText = pageAdapter.getPageText(currentWordIdx);
        if (pageText.isEmpty()) return;

        EmotionTtsHelper.speakWithEmotion(
                androidTts,
                pageText,
                avgValence,
                avgArousal,
                "word_" + currentWordIdx
        );
    }

    private int getPageIndexForGlobalOffset(int globalOffset) {
        int page = 0;

        for (int i = 0; i < pageStartIndex.size(); i++) {
            if (pageStartIndex.get(i) <= globalOffset) {
                page = i;
            } else {
                break;
            }
        }

        return page;
    }

    /** isPlaying 상태에 따라 버튼 아이콘 변경 */
    private void setPlayingState(boolean playing) {
        isPlaying = playing;
        btnTtsPlay.setImageResource(
                playing ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play
        );
    }

    /** 화면 터치 시 상단·하단 바 함께 토글 */
    private void toggleBars() {
        if (isTopBarVisible) {
            // 숨기기
            topBar.animate()
                    .translationY(-topBar.getHeight())
                    .alpha(0f).setDuration(200)
                    .withEndAction(() -> topBar.setVisibility(View.GONE));
            bottomBar.animate()
                    .translationY(bottomBar.getHeight())
                    .alpha(0f).setDuration(200)
                    .withEndAction(() -> bottomBar.setVisibility(View.GONE));
            isTopBarVisible    = false;
            isBottomBarVisible = false;
        } else {
            // 보이기
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().translationY(0).alpha(1f).setDuration(200);
            bottomBar.setVisibility(View.VISIBLE);
            bottomBar.animate().translationY(0).alpha(1f).setDuration(200);
            isTopBarVisible    = true;
            isBottomBarVisible = true;
        }
    }

    private void paginateTextAndSetAdapter(String fullText, int fontColor) {
        TextPaint paint = new TextPaint();

        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, currentUserSetting.fontSize, getResources().getDisplayMetrics()));
        paint.setColor(fontColor);
        paint.setAntiAlias(true);

        paint.setLetterSpacing(currentUserSetting.letterSpacing);

        Typeface baseFace = Typeface.DEFAULT;
        try {
            if ("RIDIBATANG".equals(currentUserSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.ridibatang);
            else if ("KOPUB_BATANG".equals(currentUserSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.kopub_batang);
            else if ("NANUM_BARUN".equals(currentUserSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.nanum_barun_gothic);
            else if ("NANUM_ROUND".equals(currentUserSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.nanum_square_round);
            else if ("MARU".equals(currentUserSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.maruburi);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUserSetting.isBold) {
            paint.setTypeface(Typeface.create(baseFace, Typeface.BOLD));
        } else {
            paint.setTypeface(Typeface.create(baseFace, Typeface.NORMAL));
        }

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, getResources().getDisplayMetrics());
        int availableWidth  = viewPager.getWidth()  - (padding * 2);
        int availableHeight = viewPager.getHeight() - (padding * 2);

        StaticLayout layout = StaticLayout.Builder
                .obtain(fullText, 0, fullText.length(), paint, availableWidth)
                .setLineSpacing(0, currentUserSetting.lineSpacing)
                .build();

        List<String> paginatedStrings = new ArrayList<>();
        pageStartIndex.clear();

        int lineCount   = layout.getLineCount();
        int currentLine = 0;

        while (currentLine < lineCount) {
            int startLine  = currentLine;
            int pageHeight = 0;

            while (currentLine < lineCount) {
                int lineHeight = layout.getLineBottom(currentLine) - layout.getLineTop(currentLine);
                if (pageHeight + lineHeight > availableHeight) break;
                pageHeight += lineHeight;
                currentLine++;
            }

            int startOffset = layout.getLineStart(startLine);
            int endOffset   = layout.getLineEnd(currentLine - 1);

            pageStartIndex.add(startOffset);
            paginatedStrings.add(fullText.substring(startOffset, endOffset));
        }

        // toggleBars 로 변경
        pageAdapter = new PageAdapter(
                paginatedStrings, currentUserSetting, fontColor, this::toggleBars);
        viewPager.setAdapter(pageAdapter);
    }

    private Page getDbPageForViewerIndex(int viewerIdx) {
        if (dbPages.isEmpty() || pageAdapter == null) return null;
        // pageAdapter 의 페이지 텍스트 시작 오프셋으로 DB 페이지 역추적
        int globalOffset = pageStartIndex.size() > viewerIdx
                ? pageStartIndex.get(viewerIdx) : 0;
        int charCount = 0;
        for (Page p : dbPages) {
            charCount += p.extractedText != null ? p.extractedText.length() + 2 : 2; // +2 = "\n\n"
            if (charCount > globalOffset) return p;
        }
        return dbPages.get(dbPages.size() - 1);
    }

    private String buildFallbackInstruction(String label) {
        if (label == null || label.isEmpty()) return "자연스럽고 차분한 목소리로 읽어주세요.";
        return label + "의 감정이 담긴 목소리로 읽어주세요.";
    }

    @Override
    protected void onPause() {
        if (androidTts != null && androidTts.isSpeaking()) androidTts.stop();
        setPlayingState(false);
        if (pageAdapter != null) pageAdapter.clearHighlight();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        if (androidTts != null) {
            androidTts.stop();
            androidTts.shutdown();
        }
    }
}
