package com.example.hbook.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.data.LibraryDao;
import com.example.hbook.model.Page;
import com.example.hbook.model.UserSetting;
import com.example.hbook.util.EmotionTtsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class ViewerActivity extends AppCompatActivity {

    private boolean isTopBarVisible    = true;
    private boolean isBottomBarVisible = true;
    private boolean isPlaying          = false;

    private LibraryDao libraryDao;

    private View topBar;
    private View bottomBar;
    private ImageView btnTtsPlay;
    private ViewPager2 viewPager;

    private TextToSpeech tts;
    private boolean isTtsReady = false;

    // 책 전체 페이지의 valence/arousal 평균값
    private float avgValence = 0f;
    private float avgArousal = 0f;

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
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ko", "KR"));
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }

                // 읽기 완료 시 버튼 재생 아이콘으로 복귀
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
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
                        runOnUiThread(() -> setPlayingState(false));
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

                isTtsReady = true;
            }
        });
        // ────────────────────────────────────────────────────────────────────

        // ── TTS 재생 버튼 ────────────────────────────────────────────────────
        btnTtsPlay.setOnClickListener(v -> {
            if (!isTtsReady) return;

            if (isPlaying) {
                // 재생 중 → 정지
                tts.stop();
                setPlayingState(false);
                if (pageAdapter != null) pageAdapter.clearHighlight();
            } else {
                // 정지 중 → 현재 페이지 텍스트 읽기
                setPlayingState(true);
                speakNextWord();
            }
        });
        // ────────────────────────────────────────────────────────────────────
    }
    private void buildWordTokens(String text) {
        wordTokens.clear();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length()) break;

            int start = i;
            while(i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            int end = i;

            wordTokens.add(new WordToken(text.substring(start, end), start, end));
        }
    }

    private void speakNextWord() {
        if (!isPlaying || pageAdapter == null) return;
        if (currentWordIdx >= pageAdapter.getPageCount()) return;

        String pageText = pageAdapter.getPageText(currentWordIdx);
        if (pageText.isEmpty()) return;

        EmotionTtsHelper.speakWithEmotion(
                tts,
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

    @Override
    protected void onPause() {
        if (tts != null && tts.isSpeaking()) tts.stop();
        setPlayingState(false);
        if (pageAdapter != null) pageAdapter.clearHighlight();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
