package com.example.hbook.ui;

import android.app.Dialog;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.example.hbook.network.ApiClient;
import com.example.hbook.network.ApiService;
import com.example.hbook.util.EmotionTtsHelper;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
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

    // 음성 모델 정의
    private static final String VOICE_ANDROID = "android";

    private static class VoiceOption {
        final String id;       // ttsVoice 저장값 ("android", "Cherry", ...)
        final String name;     // 표시 이름
        final boolean isAi;   // AI 뱃지 표시 여부
        final String tags;    // 특징 태그 (AI만)

        VoiceOption(String id, String name, boolean isAi, String tags) {
            this.id = id;
            this.name = name;
            this.isAi = isAi;
            this.tags = tags;
        }
    }

    private static final List<VoiceOption> VOICE_OPTIONS = new ArrayList<VoiceOption>() {{
        add(new VoiceOption(VOICE_ANDROID, "Android TTS", false, ""));
        add(new VoiceOption("Cherry", "Cherry", true, "#차분한 #부드러운 #여성"));
        add(new VoiceOption("Ethan", "Ethan", true, "#낮은 #안정적인 #남성"));
        add(new VoiceOption("Serena", "Serena", true, "#밝은 #명확한 #여성"));
        add(new VoiceOption("Chelsie", "Chelsie", true, "#따뜻한 #자연스러운 #여성"));
    }};

    // UI
    private View topBar;
    private View bottomBar;
    private ImageView btnTtsPlay;
    private ViewPager2 viewPager;

    private View ttsPlayerSheet;
    private TextView tvTtsTitle;
    private TextView tvCurrentVoice;
    private TextView tvTtsSpeed;
    private LinearLayout btnVoiceSelect;
    private LinearLayout btnSpeedSelect;
    private ImageButton btnTtsPlaySheet;
    private ImageButton btnTtsPrev;
    private ImageButton btnTtsNext;
    private TextView btnTtsClose;
    private TextView tvPageIndicator;
    private TextView tvTtsPage;
    private LinearLayout btnViewerSetting;


    private boolean isTtsPlayerMode = false;
    private boolean isTopBarVisible = true;
    private boolean isBottomBarVisible = true;

    // 데이터
    private LibraryDao libraryDao;
    private List<Page> dbPages = new ArrayList<>();

    private UserSetting currentUserSetting;
    private int currentUserId = -1;
    private int userFontColor = 0xFF000000;
    private String fullText = "";
    private String currentBookName = "";

    private final List<Integer> viewerToDb = new ArrayList<>();
    private final List<Integer> pageStartIndex = new ArrayList<>();
    private PageAdapter pageAdapter = null;

    // TTS 상태
    private int currentDbIdx = 0;
    private int currentViewerIdx = 0;
    private boolean isPlaying = false;
    private float ttsSpeed = 1.0f;

    private TextToSpeech androidTts;
    private boolean isAndroidTtsReady = false;
    private MediaPlayer mediaPlayer;

    private float avgValence = 0f;
    private float avgArousal = 0f;
    private float avgDominance = 0f;

    private ApiService apiService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Call<TtsResponse> pendingTtsCall = null;
    private boolean boldBeforeHighContrast = false;
    private boolean isLoadingTts = false;
    private View loadingOverlay = null;

    // 하이라이팅
    private List<TimestampEntry> currentTimestamps = new ArrayList<>();
    private final Handler highlightHandler = new Handler(Looper.getMainLooper());
    private Runnable highlightRunnable = null;

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
        ttsPlayerSheet = findViewById(R.id.tts_player_sheet);
        tvTtsTitle = findViewById(R.id.tv_tts_title);
        tvCurrentVoice = findViewById(R.id.tv_current_voice);
        tvTtsSpeed = findViewById(R.id.tv_tts_speed);
        btnVoiceSelect = findViewById(R.id.btn_voice_select);
        btnSpeedSelect = findViewById(R.id.btn_speed_select);
        btnTtsPlaySheet = findViewById(R.id.btn_tts_play_sheet);
        btnTtsPrev = findViewById(R.id.btn_tts_prev);
        btnTtsNext = findViewById(R.id.btn_tts_next);
        btnTtsClose = findViewById(R.id.btn_tts_close);
        loadingOverlay    = findViewById(R.id.loading_overlay);
        tvPageIndicator   = findViewById(R.id.tv_page_indicator);
        tvTtsPage         = findViewById(R.id.tv_tts_page);
        btnViewerSetting  = findViewById(R.id.btn_viewer_setting);
        TextView tvBookTitle = findViewById(R.id.tv_viewer_title);

        currentUserSetting.ttsVoice = VOICE_ANDROID;

        tvBack.setOnClickListener(v -> finish());

        // 데이터 받기
        int bookId = getIntent().getIntExtra("BOOK_ID", -1);
        currentBookName = getIntent().getStringExtra("BOOK_NAME") != null
                ? getIntent().getStringExtra("BOOK_NAME") : "";
        if (!currentBookName.isEmpty()) tvBookTitle.setText(currentBookName);
        tvTtsTitle.setText(currentBookName);

        // DB에서 페이지 목록 로드
        if (bookId != -1) {
            dbPages = libraryDao.getPagesForBook(bookId);
        }

        // ── valence / arousal 평균 계산 ─────────────────────────────────────
        if (!dbPages.isEmpty()) {
            float sumValence = 0f;
            float sumArousal = 0f;
            float sumDominance = 0f;
            for (Page p : dbPages) {
                sumValence += p.emotionValence;
                sumArousal += p.emotionArousal;
                sumDominance += p.emotionDominance;
            }
            avgValence = sumValence / dbPages.size();
            avgArousal = sumArousal / dbPages.size();
            avgDominance = sumDominance / dbPages.size();
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

        apiService = ApiClient.getService(this);

        // ── TTS 재생 버튼 ────────────────────────────────────────────────────
        btnTtsPlay.setOnClickListener(v -> {
            if (isTtsPlayerMode) {
                if (isPlaying) {
                    stopPlayback();
                } else {
                    currentViewerIdx = viewPager.getCurrentItem();
                    currentDbIdx = viewerToDb.isEmpty() ? 0
                            : viewerToDb.get(Math.min(currentViewerIdx, viewerToDb.size() - 1));
                    setPlayingState(true);
                    speakCurrentDbPage();
                }
            } else {
                enterTtsPlayerMode();
            }
        });

        // TTS 플레이어 시트 버튼들
        btnTtsClose.setOnClickListener(v -> exitTtsPlayerMode());

        btnTtsPlaySheet.setOnClickListener(v -> {
            if (isPlaying) {
                stopPlayback();
            } else {
                currentViewerIdx = viewPager.getCurrentItem();
                currentDbIdx = viewerToDb.isEmpty() ? 0
                        : viewerToDb.get(Math.min(currentViewerIdx, viewerToDb.size() - 1));
                setPlayingState(true);
                speakCurrentDbPage();
            }
        });

        btnTtsPrev.setOnClickListener(v -> {
            if (currentDbIdx > 0) {
                currentDbIdx--;
                if (isPlaying) speakCurrentDbPage();
                else {
                    int targetViewer = getFirstViewerIdxForDbIdx(currentDbIdx);
                    viewPager.setCurrentItem(targetViewer, true);
                }
            }
        });

        btnTtsNext.setOnClickListener(v -> {
            if (currentDbIdx < dbPages.size() - 1) {
                currentDbIdx++;
                if (isPlaying) speakCurrentDbPage();
                else {
                    int targetViewer = getFirstViewerIdxForDbIdx(currentDbIdx);
                    viewPager.setCurrentItem(targetViewer, true);
                }
            }
        });

        // 음성 선택 버튼
        btnVoiceSelect.setOnClickListener(v -> showVoiceSelectDialog());

        // 속도 선택 버튼
        btnSpeedSelect.setOnClickListener(v -> showSpeedSelectDialog());

        if (btnViewerSetting != null) {
            btnViewerSetting.setOnClickListener(v -> showViewerSettingSheet());
        }

        // 현재 저장된 목소리로 표시 초기화
        updateVoiceLabel();
    }

    private void enterTtsPlayerMode() {
        isTtsPlayerMode = true;

        // 기존 바 숨기기
        topBar.animate().translationY(-topBar.getHeight()).alpha(0f).setDuration(200)
                .withEndAction(() -> topBar.setVisibility(View.GONE));
        bottomBar.animate().translationY(bottomBar.getHeight()).alpha(0f).setDuration(200)
                .withEndAction(() -> bottomBar.setVisibility(View.GONE));
        isTopBarVisible = isBottomBarVisible = false;

        // TTS 플레이어 시트 표시
        ttsPlayerSheet.setVisibility(View.VISIBLE);
        ttsPlayerSheet.post(() -> {
            int sheetH = ttsPlayerSheet.getHeight();
            ttsPlayerSheet.setTranslationY(sheetH);
            ttsPlayerSheet.animate().translationY(0).alpha(1f).setDuration(250).start();
        });

        // 현재 페이지부터 바로 재생
        currentViewerIdx = viewPager.getCurrentItem();
        currentDbIdx = viewerToDb.isEmpty() ? 0
                : viewerToDb.get(Math.min(currentViewerIdx, viewerToDb.size() - 1));
        setPlayingState(true);
        speakCurrentDbPage();
    }

    private boolean isTtsSheetVisible = true;

    private void toggleTtsSheet() {
        int sheetH = ttsPlayerSheet.getHeight();
        if (sheetH == 0) sheetH = ttsPlayerSheet.getMeasuredHeight();
        if (sheetH == 0) sheetH = 300; // dp가 아닌 px 최소 fallback

        if (isTtsSheetVisible) {
            final int h = sheetH;
            ttsPlayerSheet.animate().translationY(h)
                    .alpha(0f).setDuration(200)
                    .withEndAction(() -> ttsPlayerSheet.setVisibility(View.INVISIBLE))
                    .start();
            isTtsSheetVisible = false;
        } else {
            ttsPlayerSheet.setVisibility(View.VISIBLE);
            ttsPlayerSheet.animate().translationY(0).alpha(1f).setDuration(200).start();
            isTtsSheetVisible = true;
        }
    }

    private void exitTtsPlayerMode() {
        isTtsPlayerMode = false;
        isTtsSheetVisible = true;
        stopPlayback();
        if (pageAdapter != null) pageAdapter.clearHighlight();

        ttsPlayerSheet.animate().translationY(ttsPlayerSheet.getHeight()).alpha(0f)
                .setDuration(200).withEndAction(() -> ttsPlayerSheet.setVisibility(View.GONE)).start();

        // 기존 바 복원
        topBar.setVisibility(View.VISIBLE);
        topBar.animate().translationY(0).alpha(1f).setDuration(200);
        bottomBar.setVisibility(View.VISIBLE);
        bottomBar.animate().translationY(0).alpha(1f).setDuration(200);
        isTopBarVisible = isBottomBarVisible = true;
    }

    private void showVoiceSelectDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_voice_select);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ListView lv = dialog.findViewById(R.id.lv_voices);
        TextView btnCancel = dialog.findViewById(R.id.btn_voice_cancel);
        TextView btnConfirm = dialog.findViewById(R.id.btn_voice_confirm);

        String currentVoiceId = (currentUserSetting.ttsVoice != null)
                ? currentUserSetting.ttsVoice : "Cherry";
        final String[] selectedId = {currentVoiceId};

        VoiceAdapter adapter = new VoiceAdapter(selectedId, currentVoiceId);
        lv.setAdapter(adapter);

        lv.setOnItemClickListener((parent, view, position, id) -> {
            selectedId[0] = VOICE_OPTIONS.get(position).id;
            adapter.notifyDataSetChanged();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String chosen = selectedId[0];
            if (!chosen.equals(currentUserSetting.ttsVoice)) {
                // 진행 중인 Qwen 서버 요청 즉시 취소
                cancelPendingTtsCall();
                // 재생 중이었으면 완전히 정지
                stopPlayback();

                currentUserSetting.ttsVoice = chosen;
                AppDatabase.getInstance(this).userDao().updateUserSetting(currentUserSetting);
                updateVoiceLabel();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private class VoiceAdapter extends ArrayAdapter<VoiceOption> {
        private final String[] selectedId;

        VoiceAdapter(String[] selectedId, String currentId) {
            super(ViewerActivity.this, R.layout.item_voice, VOICE_OPTIONS);
            this.selectedId = selectedId;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_voice, parent, false);

            VoiceOption opt = VOICE_OPTIONS.get(position);
            TextView tvName = convertView.findViewById(R.id.tv_voice_name);
            TextView tvTags = convertView.findViewById(R.id.tv_voice_tags);
            TextView tvBadge = convertView.findViewById(R.id.tv_ai_badge);
            ImageView ivCheck = convertView.findViewById(R.id.iv_voice_check);

            tvName.setText(opt.name);

            if (opt.isAi) {
                tvBadge.setVisibility(View.VISIBLE);
                tvTags.setVisibility(View.VISIBLE);
                tvTags.setText(opt.tags);
            } else {
                tvBadge.setVisibility(View.GONE);
                tvTags.setVisibility(View.GONE);
            }

            boolean isSelected = opt.id.equals(selectedId[0]);
            ivCheck.setVisibility(View.VISIBLE);
            ivCheck.setImageResource(isSelected ? R.drawable.ic_check_on : R.drawable.ic_check_off);

            // 선택된 항목 배경 강조
            convertView.setBackgroundColor(
                    isSelected ? 0xFFF0EDFF : 0xFFFFFFFF);

            return convertView;
        }
    }

    private void updateVoiceLabel() {
        String voiceId = (currentUserSetting != null && currentUserSetting.ttsVoice != null)
                ? currentUserSetting.ttsVoice : "Cherry";
        String displayName = voiceId;
        for (VoiceOption opt : VOICE_OPTIONS) {
            if (opt.id.equals(voiceId)) {
                displayName = opt.name;
                break;
            }
        }
        if (tvCurrentVoice != null) tvCurrentVoice.setText(displayName);
    }

    private void showSpeedSelectDialog() {
        float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        String[] labels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};

        new android.app.AlertDialog.Builder(this)
                .setTitle("재생 속도")
                .setItems(labels, (dlg, which) -> {
                    ttsSpeed = speeds[which];
                    tvTtsSpeed.setText(labels[which]);

                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        try {
                            mediaPlayer.setPlaybackParams(
                                    mediaPlayer.getPlaybackParams().setSpeed(ttsSpeed));
                        } catch (Exception e) {
                            Log.e(TAG, "MediaPlayer 배속 적용 실패: " + e.getMessage());
                        }
                    }

                    if (androidTts != null && androidTts.isSpeaking() && isPlaying) {
                        androidTts.stop();
                        if (currentDbIdx < dbPages.size()) {
                            String text = dbPages.get(currentDbIdx).extractedText;
                            if (text != null && !text.isEmpty()) {
                                speakWithAndroidTts(text);
                            }
                        }
                    }
                })
                .show();
    }

    private void speakCurrentDbPage() {
        if (!isPlaying) return;
        if (currentDbIdx >= dbPages.size()) {
            // 모든 DB 페이지 재생 완료
            setPlayingState(false);
            currentDbIdx = 0;
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

        if (VOICE_ANDROID.equals(voice)) {
            String text = page.extractedText != null ? page.extractedText : "";
            speakWithAndroidTts(text);
            return;
        }

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
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    new FileInputStream(tsFile)));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    cachedTs = new Gson().fromJson(sb.toString(),
                            new TypeToken<List<TimestampEntry>>() {
                            }.getType());
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
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(ttsSpeed));
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
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(ttsSpeed));
            mediaPlayer.setOnCompletionListener(mp -> mainHandler.post(() -> {
                stopHighlightPolling();
                if (pageAdapter != null) pageAdapter.clearHighlight();
                advanceToNextDbPage();
            }));
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

        setLoadingState(true);

        pendingTtsCall = apiService.generateTts(req);
        pendingTtsCall.enqueue(new Callback<TtsResponse>() {
            @Override
            public void onResponse(@NonNull Call<TtsResponse> call,
                                   @NonNull Response<TtsResponse> response) {
                if (call.isCanceled()) return;
                setLoadingState(false);

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
                            fos.flush();
                            fos.close();

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
                if (call.isCanceled()) return;
                setLoadingState(false);
                mainHandler.post(() -> speakWithAndroidTts(text));
            }
        });
    }

    private void speakWithAndroidTts(String text) {
        if (!isAndroidTtsReady || androidTts == null || text.isEmpty()) return;

        // arousal → speechRate
        float emotionRate = 1.0f + (avgArousal * 0.5f);
        if (avgValence < -0.5f) emotionRate -= (-avgValence - 0.5f) * 0.3f;
        emotionRate = Math.max(0.6f, Math.min(1.6f, emotionRate));

        float clampedEmotion = Math.max(0.8f, Math.min(1.2f, emotionRate));
        float finalRate = Math.max(0.5f, Math.min(2.5f, ttsSpeed * clampedEmotion));

        // valence → pitch
        float pitch = 1.0f + (avgValence * 0.35f);
        if (avgArousal > 0.5f && avgValence > 0f) pitch += avgArousal * 0.1f;
        pitch = Math.max(0.7f, Math.min(1.4f, pitch));

        // dominance → volume
        float volume = 0.75f + (avgDominance * 0.25f);
        volume = Math.max(0.5f, Math.min(1.0f, volume));

        androidTts.setSpeechRate(finalRate);
        androidTts.setPitch(pitch);

        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);

        androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "db_" + currentDbIdx);
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
        cancelPendingTtsCall();
        setLoadingState(false);
        stopHighlightPolling();
        currentTimestamps.clear();
        releaseMediaPlayer();
        if (androidTts != null) androidTts.stop();
    }

    private void cancelPendingTtsCall() {
        if (pendingTtsCall != null && !pendingTtsCall.isCanceled()) {
            pendingTtsCall.cancel();
            Log.d(TAG, "진행 중인 TTS 요청 취소");
        }
        pendingTtsCall = null;
    }

    private void setLoadingState(boolean loading) {
        isLoadingTts = loading;
        mainHandler.post(() -> {
            if (loadingOverlay != null)
                loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        });
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
        int icon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        if (btnTtsPlay != null) btnTtsPlay.setImageResource(icon);
        if (btnTtsPlaySheet != null) btnTtsPlaySheet.setImageResource(icon);
    }

    private void updatePageIndicator() {
        if (pageAdapter == null) return;
        int total = pageAdapter.getPageCount();
        int current = viewPager.getCurrentItem() + 1;
        String text = current + " / " + total;
        if (tvPageIndicator != null) tvPageIndicator.setText(text);
        if (tvTtsPage != null) tvTtsPage.setText(text);
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
        } catch (Exception e) {
            e.printStackTrace();
        }

        paint.setTypeface(Typeface.create(baseFace,
                currentUserSetting.isBold ? Typeface.BOLD : Typeface.NORMAL));

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, getResources().getDisplayMetrics());
        int availableWidth = viewPager.getWidth() - (padding * 2);
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

        int lineCount = layout.getLineCount();
        int currentLine = 0;

        while (currentLine < lineCount) {
            int startLine = currentLine;
            int pageHeight = 0;

            while (currentLine < lineCount) {
                int lineH = layout.getLineBottom(currentLine) - layout.getLineTop(currentLine);
                if (pageHeight + lineH > availableHeight) break;
                pageHeight += lineH;
                currentLine++;
            }

            int startOffset = layout.getLineStart(startLine);
            int endOffset = layout.getLineEnd(currentLine - 1);

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

        pageAdapter = new PageAdapter(pages, currentUserSetting, fontColor, () -> {
            if (isTtsPlayerMode) {
                toggleTtsSheet();
            } else {
                toggleBars();
            }
        });
        viewPager.setAdapter(pageAdapter);

        Log.d(TAG, "페이지 분할 완료: 앱화면=" + pages.size() + "페이지, DB=" + dbPages.size() + "페이지");
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
            String voice = currentUserSetting.ttsVoice != null ? currentUserSetting.ttsVoice : "Cherry";
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

    private void showViewerSettingSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_viewer_setting, null);
        sheet.setContentView(v);
        final SwitchMaterial switchBold = v.findViewById(R.id.bs_switch_bold);
        if (switchBold != null) {
            syncBoldSwitch(switchBold, currentUserSetting.isBold,
                    "#000000".equals(currentUserSetting.backgroundColor));
        }

        View.OnClickListener themeClick = btn -> {
            int id = btn.getId();
            boolean wasHighContrast = "#000000".equals(currentUserSetting.backgroundColor);

            if (id == R.id.bs_btn_theme_black) {
                boldBeforeHighContrast             = currentUserSetting.isBold;
                currentUserSetting.backgroundColor = "#000000";
                currentUserSetting.isBold          = true;
                if (currentUserSetting.fontSize    < 16f)  currentUserSetting.fontSize    = 16f;
                if (currentUserSetting.lineSpacing < 1.6f) currentUserSetting.lineSpacing = 1.6f;
                if (switchBold != null) syncBoldSwitch(switchBold, true, true);
            } else {
                if (wasHighContrast) {
                    currentUserSetting.isBold = boldBeforeHighContrast;
                    if (switchBold != null) syncBoldSwitch(switchBold, boldBeforeHighContrast, false);
                }
                if      (id == R.id.bs_btn_theme_white)     currentUserSetting.backgroundColor = "#F5F5F5";
                else if (id == R.id.bs_btn_theme_gray)      currentUserSetting.backgroundColor = "#E0E0E0";
                else if (id == R.id.bs_btn_theme_dark_gray) currentUserSetting.backgroundColor = "#424242";
                else if (id == R.id.bs_btn_theme_beige)     currentUserSetting.backgroundColor = "#F6F1E5";
                else if (id == R.id.bs_btn_theme_green)     currentUserSetting.backgroundColor = "#233E3B";
            }
            applyViewerSetting();
            if (switchBold != null) {
                switchBold.setOnCheckedChangeListener((b2, c2) -> {
                    if (!c2 && "#000000".equals(currentUserSetting.backgroundColor)) return;
                    currentUserSetting.isBold = c2;
                    applyViewerSetting();
                });
            }
        };

        int[] themeIds = {R.id.bs_btn_theme_white, R.id.bs_btn_theme_gray,
                R.id.bs_btn_theme_dark_gray, R.id.bs_btn_theme_black,
                R.id.bs_btn_theme_beige, R.id.bs_btn_theme_green};
        for (int id : themeIds) {
            View btn = v.findViewById(id);
            if (btn != null) btn.setOnClickListener(themeClick);
        }

        // switchBold 초기 리스너 등록
        if (switchBold != null) {
            switchBold.setOnCheckedChangeListener((b, checked) -> {
                if (!checked && "#000000".equals(currentUserSetting.backgroundColor)) return;
                currentUserSetting.isBold = checked;
                applyViewerSetting();
            });
        }

        TextView tvLineSpacing = v.findViewById(R.id.bs_tv_line_spacing);
        SeekBar sbLineSpacing  = v.findViewById(R.id.bs_sb_line_spacing);
        if (sbLineSpacing != null) {
            sbLineSpacing.setProgress((int)((currentUserSetting.lineSpacing - 1.0f) / 0.1f));
            if (tvLineSpacing != null) tvLineSpacing.setText(String.format("%.1fx", currentUserSetting.lineSpacing));
            sbLineSpacing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    currentUserSetting.lineSpacing = 1.0f + (p * 0.1f);
                    if (tvLineSpacing != null) tvLineSpacing.setText(String.format("%.1fx", currentUserSetting.lineSpacing));
                    applyViewerSetting();
                }
            });
        }

        String[] fontNames = {"시스템","나눔바른고딕","나눔스퀘어라운드","리디바탕","KoPub 바탕","마루부리"};
        String[] fontKeys  = {"DEFAULT","NANUM_BARUN","NANUM_ROUND","RIDIBATANG","KOPUB_BATANG","MARU"};
        TextView tvFontName = v.findViewById(R.id.bs_tv_font_name);
        if (tvFontName != null) {
            for (int i = 0; i < fontKeys.length; i++)
                if (fontKeys[i].equals(currentUserSetting.fontFamily)) { tvFontName.setText(fontNames[i]); break; }
        }

        View fontRow = v.findViewById(R.id.bs_font_row);
        View.OnClickListener fontClickListener = btn -> {
            new android.app.AlertDialog.Builder(ViewerActivity.this)
                    .setTitle("글꼴 선택")
                    .setItems(fontNames, (d, which) -> {
                        currentUserSetting.fontFamily = fontKeys[which];
                        if (tvFontName != null) tvFontName.setText(fontNames[which]);
                        applyViewerSetting();
                    })
                    .show();
        };
        if (fontRow != null) fontRow.setOnClickListener(fontClickListener);
        View btnFontSelect = v.findViewById(R.id.bs_btn_font_select);
        if (btnFontSelect != null) btnFontSelect.setOnClickListener(fontClickListener);

        sheet.setOnDismissListener(d -> new Thread(() ->
                AppDatabase.getInstance(this).userDao().updateUserSetting(currentUserSetting)).start());

        sheet.show();
    }

    private void syncBoldSwitch(SwitchMaterial sw, boolean checked, boolean isHighContrast) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setEnabled(!isHighContrast);
        sw.setAlpha(isHighContrast ? 0.5f : 1.0f);
    }

    private void applyViewerSetting() {
        viewPager.setBackgroundColor(Color.parseColor(currentUserSetting.backgroundColor));
        switch (currentUserSetting.backgroundColor) {
            case "#F5F5F5": userFontColor = Color.parseColor("#333333"); break;
            case "#E0E0E0": userFontColor = Color.parseColor("#000000"); break;
            case "#424242": userFontColor = Color.parseColor("#E0E0E0"); break;
            case "#000000": userFontColor = Color.parseColor("#FFFFFF"); break;
            case "#F6F1E5": userFontColor = Color.parseColor("#4E3726"); break;
            case "#233E3B": userFontColor = Color.parseColor("#BDD9B9"); break;
            default:        userFontColor = Color.BLACK; break;
        }
        if (!fullText.isEmpty()) paginateTextAndSetAdapter(fullText, userFontColor);
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