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
import com.example.hbook.model.TimestampEntry;
import com.example.hbook.model.TtsRequest;
import com.example.hbook.model.TtsResponse;
import com.example.hbook.model.UserSetting;
import com.example.hbook.network.ApiService;
import com.example.hbook.util.EmotionTtsHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

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

    // UI
    private View topBar;
    private View bottomBar;
    private ImageView btnTtsPlay;
    private ViewPager2 viewPager;

    private boolean isTopBarVisible = true;
    private boolean isBottomBarVisible = true;

    // 데이터
    private LibraryDao libraryDao;
    private List<Page> dbPages = new ArrayList<>();

    private UserSetting currentUserSetting;
    private int currentUserId = -1;
    private int userFontColor = 0xFF000000;
    private String fullText = "";

    private final List<Integer> viewerToDb = new ArrayList<>();
    private final List<Integer> pageStartIndex = new ArrayList<>();
    private PageAdapter pageAdapter = null;

    // TTS 상태
    private int currentDbIdx = 0;
    private int currentViewerIdx = 0;
    private boolean isPlaying = false;

    private TextToSpeech androidTts;
    private boolean isAndroidTtsReady = false;
    private MediaPlayer mediaPlayer;

    private float avgValence = 0f;
    private float avgArousal = 0f;

    private ApiService apiService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 하이라이팅
    private List<TimestampEntry> currentTimestamps = new ArrayList<>();
    private final Handler  highlightHandler  = new Handler(Looper.getMainLooper());
    private       Runnable highlightRunnable = null;

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

    private List<WordToken> wordTokens = new ArrayList<>();
    private int currentWordIdx = 0;

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

        viewPager = findViewById(R.id.view_pager);
        viewPager.setBackgroundColor(Color.parseColor(currentUserSetting.backgroundColor));

        switch (currentUserSetting.backgroundColor) {
            case "#F5F5F5":
                userFontColor = Color.parseColor("#333333");
                break;
            case "#E0E0E0":
                userFontColor = Color.parseColor("#000000");
                break;
            case "#424242":
                userFontColor = Color.parseColor("#E0E0E0");
                break;
            case "#000000":
                userFontColor = Color.parseColor("#FFFFFF");
                break;
            case "#F6F1E5":
                userFontColor = Color.parseColor("#4E3726");
                break;
            case "#233E3B":
                userFontColor = Color.parseColor("#BDD9B9");
                break;
            default:
                userFontColor = Color.BLACK;
                break;
        }

        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        btnTtsPlay = findViewById(R.id.btn_tts_play);
        TextView tvBack = findViewById(R.id.tv_back);
        TextView tvBookTitle = findViewById(R.id.tv_viewer_title);

        tvBack.setOnClickListener(v -> finish());

        // 데이터 받기
        int bookId = getIntent().getIntExtra("BOOK_ID", -1);
        String bookName = getIntent().getStringExtra("BOOK_NAME");
        if (bookName != null) tvBookTitle.setText(bookName);

        // DB에서 페이지 목록 로드
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
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        if (!isPlaying) return;
                        mainHandler.post(() -> advanceToNextDbPage());
                    }

                    @Override
                    public void onError(String id) {
                        mainHandler.post(() -> setPlayingState(false));
                    }

                    @Override
                    public void onRangeStart(String utteranceId, int start, int end, int frame) {
                        mainHandler.post(() -> {
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
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                .addHeader("ngrok-skip-browser-warning", "true")
                                .build()))
                .build();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(
                        new TypeToken<List<TimestampEntry>>(){}.getType(),
                        (JsonDeserializer<List<TimestampEntry>>) (json, typeOfT, context) -> {
                            List<TimestampEntry> list = new ArrayList<>();
                            for (JsonElement el : json.getAsJsonArray()) {
                                list.add(context.deserialize(el, TimestampEntry.class));
                            }
                            return list;
                        }
                )
                .create();

        apiService = new Retrofit.Builder()
                .baseUrl("https://perish-impure-hatred.ngrok-free.dev/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ApiService.class);

        // ── TTS 재생 버튼 ────────────────────────────────────────────────────
        btnTtsPlay.setOnClickListener(v -> {
            if (isPlaying) {
                stopPlayback();
            } else {
                // 현재 화면 페이지에서 재생 시작
                currentViewerIdx = viewPager.getCurrentItem();
                currentDbIdx = viewerToDb.isEmpty() ? 0 : viewerToDb.get(Math.min(currentViewerIdx, viewerToDb.size() - 1));
                setPlayingState(true);
                speakCurrentDbPage();
            }
        });

        // 화면 터치 시 상단·하단 바 토글
        viewPager.setOnClickListener(v -> toggleBars());
        // ────────────────────────────────────────────────────────────────────
    }

    private void speakCurrentDbPage() {
        if (!isPlaying) return;
        if (currentDbIdx >= dbPages.size()) {
            // 모든 DB 페이지 재생 완료
            setPlayingState(false);
            currentDbIdx     = 0;
            currentViewerIdx = 0;
            if (pageAdapter != null) pageAdapter.clearHighlight();
            return;
        }

        Page page = dbPages.get(currentDbIdx);

        // 이 DB 페이지에 해당하는 첫 번째 앱 화면 페이지로 이동
        int targetViewerIdx = getFirstViewerIdxForDbIdx(currentDbIdx);
        if (targetViewerIdx >= 0 && targetViewerIdx != viewPager.getCurrentItem()) {
            currentViewerIdx = targetViewerIdx;
            viewPager.setCurrentItem(targetViewerIdx, true);
        }

        String voice = (currentUserSetting != null && currentUserSetting.ttsVoice != null)
                ? currentUserSetting.ttsVoice : "Cherry";
        String expectedPath = getFilesDir().getAbsolutePath()
                + "/tts_book" + page.bookId
                + "_page" + page.pageNumber
                + "_" + voice + ".wav";

        Log.d(TAG, "재생 DB페이지[" + currentDbIdx + "] voice=" + voice
                + " expectedPath=" + expectedPath + " label=" + page.emotionLabel);

        // 경로 1: 로컬 파일
        if (new File(expectedPath).exists()) {
            File tsFile = new File(expectedPath.replace(".wav", ".json"));
            List<TimestampEntry> cachedTs = new ArrayList<>();
            if (tsFile.exists()) {
                try {
                    StringBuilder sb = new StringBuilder();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    new java.io.FileInputStream(tsFile)));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    cachedTs = new Gson().fromJson(sb.toString(),
                            new TypeToken<List<TimestampEntry>>(){}.getType());
                } catch (Exception e) {
                    Log.e(TAG, "timestamps 로드 실패: " + e.getMessage());
                }
            }
            playLocalAudioWithHighlight(expectedPath, cachedTs);
            return;
        }

        // 경로 2: 서버 실시간 요청
        if (page.emotionLabel != null && !page.emotionLabel.isEmpty()) {
            // DB 페이지 전체 텍스트를 TTS 요청
            String text = page.extractedText != null ? page.extractedText : "";
            requestTtsFromServer(page, text);
            return;
        }

        // 경로 3: Android TTS 폴백
        String text = page.extractedText != null ? page.extractedText : "";
        speakWithAndroidTts(text);
    }

    private void advanceToNextDbPage() {
        if (!isPlaying) return;
        currentDbIdx++;
        speakCurrentDbPage();
    }

    private void playLocalAudio(String filePath) {
        stopHighlightPolling();
        currentTimestamps.clear();

        try {
            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp ->
                    mainHandler.post(this::advanceToNextDbPage));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 오류: what=" + what);
                // 파일 오류 → 폴백
                Page page = currentDbIdx < dbPages.size() ? dbPages.get(currentDbIdx) : null;
                if (page != null) {
                    mainHandler.post(() -> speakWithAndroidTts(
                            page.extractedText != null ? page.extractedText : ""));
                }
                return true;
            });
            mediaPlayer.start();
            Log.d(TAG, "로컬 재생: " + new File(filePath).getName());
        } catch (Exception e) {
            Log.e(TAG, "로컬 재생 실패: " + e.getMessage());
            Page page = currentDbIdx < dbPages.size() ? dbPages.get(currentDbIdx) : null;
            if (page != null) speakWithAndroidTts(
                    page.extractedText != null ? page.extractedText : "");
        }
    }

    private void playLocalAudioWithHighlight(String filePath, List<TimestampEntry> timestamps) {
        currentTimestamps = (timestamps != null) ? timestamps : new ArrayList<>();

        File f = new File(filePath);
        if (!f.exists() || f.length() == 0) {
            Log.e(TAG, "재생 실패: 파일 없음 또는 크기 0 → " + filePath);
            // 500ms 후 재시도 1회
            mainHandler.postDelayed(() -> playLocalAudioWithHighlight(filePath, currentTimestamps), 300);
            return;
        }

        try {
            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                mainHandler.post(() -> {
                    stopHighlightPolling();
                    if (pageAdapter != null) pageAdapter.clearHighlight();
                    advanceToNextDbPage();
                });
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 오류: what=" + what);
                stopHighlightPolling();
                Page page = currentDbIdx < dbPages.size() ? dbPages.get(currentDbIdx) : null;
                if (page != null) {
                    mainHandler.post(() -> speakWithAndroidTts(
                            page.extractedText != null ? page.extractedText : ""));
                }
                return true;
            });
            mediaPlayer.start();
            Log.d(TAG, "서버 재생 (하이라이팅): " + new File(filePath).getName()
                    + " / timestamps=" + currentTimestamps.size() + "개");

            // timestamps 가 있을 때만 폴링 시작
            if (!currentTimestamps.isEmpty()) {
                startHighlightPolling();
            }

        } catch (Exception e) {
            Log.e(TAG, "서버 재생 실패: " + e.getMessage());
            stopHighlightPolling();
            Page page = currentDbIdx < dbPages.size() ? dbPages.get(currentDbIdx) : null;
            if (page != null) speakWithAndroidTts(
                    page.extractedText != null ? page.extractedText : "");
        }
    }

    private void requestTtsFromServer(Page page, String text) {
        String instruction = buildFallbackInstruction(page.emotionLabel);
        TtsRequest req = new TtsRequest(text, instruction, page.pageId,
                currentUserSetting != null ? currentUserSetting.ttsVoice : "Cherry");

        apiService.generateTts(req).enqueue(new Callback<TtsResponse>() {
            @Override
            public void onResponse(@NonNull Call<TtsResponse> call,
                                   @NonNull Response<TtsResponse> response) {
                if (!isPlaying) return;

                if (response.isSuccessful()
                        && response.body() != null
                        && response.body().audio_base64 != null) {

                    byte[] audioBytes = Base64.decode(
                            response.body().audio_base64, Base64.DEFAULT);
                    List<TimestampEntry> timestamps = response.body().timestamps;

                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        try {
                            String voice = (currentUserSetting != null
                                    && currentUserSetting.ttsVoice != null)
                                    ? currentUserSetting.ttsVoice : "Cherry";

                            File audioFile = new File(getFilesDir(),
                                    "tts_book" + page.bookId
                                            + "_page" + page.pageNumber
                                            + "_" + voice + ".wav");

                            // flush + close 명시적으로
                            FileOutputStream fos = new FileOutputStream(audioFile);
                            fos.write(audioBytes);
                            fos.flush();   // ← 추가
                            fos.close();   // ← try-with-resources 대신 명시적 close

                            // WAV 저장 완료 확인
                            if (!audioFile.exists() || audioFile.length() == 0) {
                                Log.e(TAG, "WAV 파일 저장 실패 또는 크기 0");
                                mainHandler.post(() -> speakWithAndroidTts(text));
                                return;
                            }

                            // timestamps JSON 저장
                            File tsFile = new File(getFilesDir(),
                                    "tts_book" + page.bookId
                                            + "_page" + page.pageNumber
                                            + "_" + voice + ".json");
                            FileOutputStream tsFos = new FileOutputStream(tsFile);
                            tsFos.write(new Gson().toJson(timestamps).getBytes());
                            tsFos.flush();
                            tsFos.close();

                            AppDatabase.getInstance(ViewerActivity.this)
                                    .libraryDao()
                                    .updateAudioFilePath(page.pageId, audioFile.getAbsolutePath());
                            page.audioFilePath = audioFile.getAbsolutePath();

                            Log.d(TAG, "WAV 저장 완료: " + audioFile.getName()
                                    + " / 크기: " + audioFile.length() + "bytes");

                            mainHandler.post(() ->
                                    playLocalAudioWithHighlight(audioFile.getAbsolutePath(), timestamps));

                        } catch (Exception e) {
                            Log.e(TAG, "TTS 캐싱 실패: " + e.getMessage());
                            mainHandler.post(() -> speakWithAndroidTts(text));
                        }
                    });
                } else {
                    mainHandler.post(() -> speakWithAndroidTts(text));
                }
            }

            @Override
            public void onFailure(@NonNull Call<TtsResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "TTS 서버 요청 실패: " + t.getMessage());
                mainHandler.post(() -> speakWithAndroidTts(text));
            }
        });
    }

    private void speakWithAndroidTts(String text) {
        if (!isAndroidTtsReady || androidTts == null || text.isEmpty()) return;

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
        androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "db_" + currentDbIdx);
    }

    private void startHighlightPolling() {
        stopHighlightPolling();

        highlightRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer == null
                        || !mediaPlayer.isPlaying()
                        || currentTimestamps.isEmpty()) return;

                float currentSec = mediaPlayer.getCurrentPosition() / 1000f;

                for (TimestampEntry ts : currentTimestamps) {
                    if (currentSec >= ts.start && currentSec < ts.end) {
                        if (pageAdapter != null) {
                            pageAdapter.setHighlightByText(ts.text, currentViewerIdx);
                        }
                        break;
                    }
                }

                highlightHandler.postDelayed(this, 50);
            }
        };

        highlightHandler.post(highlightRunnable);
    }

    private void stopHighlightPolling() {
        if (highlightRunnable != null) {
            highlightHandler.removeCallbacks(highlightRunnable);
            highlightRunnable = null;
        }
    }

    private void stopPlayback() {
        setPlayingState(false);
        stopHighlightPolling();
        currentTimestamps.clear();
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

    private void setPlayingState(boolean playing) {
        isPlaying = playing;
        btnTtsPlay.setImageResource(
                playing ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
    }

    private int getFirstViewerIdxForDbIdx(int dbIdx) {
        for (int i = 0; i < viewerToDb.size(); i++) {
            if (viewerToDb.get(i) == dbIdx) return i;
        }
        return 0;
    }

    private String buildFallbackInstruction(String label) {
        if (label == null || label.isEmpty())
            return "Read naturally and calmly.";
        return "Read with the emotion of " + label + ".";
    }

    private void paginateTextAndSetAdapter(String text, int fontColor) {
        TextPaint paint = new TextPaint();
        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, currentUserSetting.fontSize,
                getResources().getDisplayMetrics()));
        paint.setColor(fontColor);
        paint.setAntiAlias(true);
        paint.setLetterSpacing(currentUserSetting.letterSpacing);

        Typeface baseFace = Typeface.DEFAULT;
        try {
            if ("RIDIBATANG".equals(currentUserSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(this, R.font.ridibatang);
            else if ("KOPUB_BATANG".equals(currentUserSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(this, R.font.kopub_batang);
            else if ("NANUM_BARUN".equals(currentUserSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(this, R.font.nanum_barun_gothic);
            else if ("NANUM_ROUND".equals(currentUserSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(this, R.font.nanum_square_round);
            else if ("MARU".equals(currentUserSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(this, R.font.maruburi);
        } catch (Exception e) { e.printStackTrace(); }

        paint.setTypeface(Typeface.create(baseFace,
                currentUserSetting.isBold ? Typeface.BOLD : Typeface.NORMAL));

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, getResources().getDisplayMetrics());
        int availableWidth  = viewPager.getWidth()  - (padding * 2);
        int availableHeight = viewPager.getHeight() - (padding * 2);

        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, availableWidth)
                .setLineSpacing(0, currentUserSetting.lineSpacing)
                .build();

        List<String> pages = new ArrayList<>();
        pageStartIndex.clear();
        viewerToDb.clear();

        // DB 페이지별 시작 오프셋 미리 계산
        // dbPageOffsets.get(i) = i번 DB 페이지가 fullText 에서 시작하는 오프셋
        List<Integer> dbPageOffsets = new ArrayList<>();
        int acc = 0;
        for (Page p : dbPages) {
            dbPageOffsets.add(acc);
            acc += (p.extractedText != null ? p.extractedText.length() : 0) + 2; // +2 = "\n\n"
        }

        int lineCount   = layout.getLineCount();
        int currentLine = 0;

        while (currentLine < lineCount) {
            int startLine  = currentLine;
            int pageHeight = 0;

            while (currentLine < lineCount) {
                int lineH = layout.getLineBottom(currentLine) - layout.getLineTop(currentLine);
                if (pageHeight + lineH > availableHeight) break;
                pageHeight += lineH;
                currentLine++;
            }

            int startOffset = layout.getLineStart(startLine);
            int endOffset   = layout.getLineEnd(currentLine - 1);

            pageStartIndex.add(startOffset);
            pages.add(text.substring(startOffset, endOffset));

            // 이 앱 화면 페이지가 몇 번째 DB 페이지에 속하는지 매핑
            int dbIdx = dbPages.size() - 1; // 기본값: 마지막 DB 페이지
            for (int d = 0; d < dbPageOffsets.size(); d++) {
                int nextOffset = (d + 1 < dbPageOffsets.size())
                        ? dbPageOffsets.get(d + 1) : Integer.MAX_VALUE;
                if (startOffset >= dbPageOffsets.get(d) && startOffset < nextOffset) {
                    dbIdx = d;
                    break;
                }
            }
            viewerToDb.add(dbIdx);
        }

        pageAdapter = new PageAdapter(pages, currentUserSetting, fontColor, this::toggleBars);
        viewPager.setAdapter(pageAdapter);

        Log.d(TAG, "페이지 분할 완료: 앱화면=" + pages.size() + "페이지, DB=" + dbPages.size() + "페이지");
        for (int i = 0; i < viewerToDb.size(); i++) {
            Log.d(TAG, "  앱화면[" + i + "] → DB[" + viewerToDb.get(i) + "]");
        }
    }

    private void toggleBars() {
        if (isTopBarVisible) {
            topBar.animate().translationY(-topBar.getHeight()).alpha(0f).setDuration(200)
                    .withEndAction(() -> topBar.setVisibility(View.GONE));
            bottomBar.animate().translationY(bottomBar.getHeight()).alpha(0f).setDuration(200)
                    .withEndAction(() -> bottomBar.setVisibility(View.GONE));
            isTopBarVisible = isBottomBarVisible = false;
        } else {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().translationY(0).alpha(1f).setDuration(200);
            bottomBar.setVisibility(View.VISIBLE);
            bottomBar.animate().translationY(0).alpha(1f).setDuration(200);
            isTopBarVisible = isBottomBarVisible = true;
        }
    }

    private void speakNextPage() {
        if (!isPlaying || pageAdapter == null) return;
        if (currentWordIdx >= pageAdapter.getPageCount()) return;

        Page currentPage = getDbPageForViewerIndex(currentWordIdx);

        String pageText = pageAdapter.getPageText(currentWordIdx);
        if (pageText == null || pageText.isEmpty()) {
            advanceToNextPage();
            return;
        }

        // 현재 화면 페이지 인덱스를 DB 페이지와 매핑
        // Page currentPage = getDbPageForViewerIndex(currentWordIdx);

        // ── 경로 1: 로컬 파일 재생 ──────────────────────────────
        if (currentPage != null) {
            String voice = (currentUserSetting != null && currentUserSetting.ttsVoice != null)
                    ? currentUserSetting.ttsVoice : "Cherry";
            String expectedPath = getFilesDir().getAbsolutePath()
                    + "/tts_book" + currentPage.bookId
                    + "_page" + currentPage.pageNumber
                    + "_" + voice + ".wav";

            if (new File(expectedPath).exists()) {
                playLocalAudio(expectedPath);
                return;
            }
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

    @Override
    protected void onPause() {
        if (androidTts != null && androidTts.isSpeaking()) androidTts.stop();
        setPlayingState(false);
        stopHighlightPolling();
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
