package com.example.hbook.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.UserSetting;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class ViewerSettingActivity extends AppCompatActivity {

    private UserSetting currentSetting;
    private int currentUserId;

    private TextView tvPreview, tvFontSizeLabel, tvLineSpacingLabel, tvLetterSpacingLabel;
    private View previewContainer;
    private RadioGroup rgFontFamily;
    private SwitchMaterial switchBold;
    private SeekBar sbFontSize, sbLineSpacing, sbLetterSpacing;
    private Button btnThemeWhite, btnThemeLightGray, btnThemeDarkGray, btnThemeBlack, btnThemeBeige, btnThemeGreen, btnSaveSettings;
    private RadioButton rbFontDefault, rbFontRidi, rbFontKopub, rbFontNanumBarun, rbFontNanumRound, rbFontMaru;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer_setting);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        if (currentUserId == -1) {
            Toast.makeText(this, "유저 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentSetting = AppDatabase.getInstance(this).userDao().getUserSetting(currentUserId);
        if (currentSetting == null) {
            currentSetting = new UserSetting(currentUserId);
        }

        initViews();
        mapSettingToUI();
        setupListeners();
        updatePreview();
    }

    private void initViews() {
        tvPreview = findViewById(R.id.tv_preview);
        previewContainer = (View) tvPreview.getParent();

        tvFontSizeLabel = findViewById(R.id.tv_font_size_label);
        tvLineSpacingLabel = findViewById(R.id.tv_line_spacing_label);
        tvLetterSpacingLabel = findViewById(R.id.tv_letter_spacing_label);

        switchBold = findViewById(R.id.switch_bold);

        sbFontSize = findViewById(R.id.sb_font_size);
        sbLineSpacing = findViewById(R.id.sb_line_spacing);
        sbLetterSpacing = findViewById(R.id.sb_letter_spacing);

        btnThemeWhite = findViewById(R.id.btn_theme_white);
        btnThemeLightGray = findViewById(R.id.btn_theme_light_gray);
        btnThemeDarkGray = findViewById(R.id.btn_theme_dark_gray);
        btnThemeBlack = findViewById(R.id.btn_theme_black);
        btnThemeBeige = findViewById(R.id.btn_theme_beige);
        btnThemeGreen = findViewById(R.id.btn_theme_green);
        btnSaveSettings = findViewById(R.id.btn_save_settings);

        rgFontFamily = findViewById(R.id.rg_font_family);
        rbFontDefault = findViewById(R.id.rb_font_default);
        rbFontRidi = findViewById(R.id.rb_font_ridi);
        rbFontKopub = findViewById(R.id.rb_font_kopub);
        rbFontNanumBarun = findViewById(R.id.rb_font_nanum_barun);
        rbFontMaru = findViewById(R.id.rb_font_maru);
        rbFontNanumRound = findViewById(R.id.rb_font_nanum_round);
    }

    // DB 값을 UI에 맞게 초기 세팅
    private void mapSettingToUI() {
        // 폰트 크기: 최소 12sp + SeekBar 값 (0~30)
        sbFontSize.setProgress((int) (currentSetting.fontSize -12f));

        // 줄 간격: 최소 1.0 + (SeekBar 값 * 0.1)
        sbLineSpacing.setProgress((int) ((currentSetting.lineSpacing - 1.0f) / 0.1f));

        // 자간: 0.0 + (SeekBar 값 * 0.02)
        sbLetterSpacing.setProgress((int) (currentSetting.letterSpacing / 0.02f));

        // 폰트 종류
        if ("RIDIBATANG".equals(currentSetting.fontFamily)) rbFontRidi.setChecked(true);
        else if ("KOPUB_BATANG".equals(currentSetting.fontFamily)) rbFontKopub.setChecked(true);
        else if ("NANUM_BARUN".equals(currentSetting.fontFamily)) rbFontNanumBarun.setChecked(true);
        else if ("NANUM_ROUND".equals(currentSetting.fontFamily)) rbFontNanumRound.setChecked(true);
        else if ("MARU".equals(currentSetting.fontFamily)) rbFontMaru.setChecked(true);
        else rbFontDefault.setChecked(true);

        // 글씨 굵기
        switchBold.setChecked(currentSetting.isBold);
    }

    private void setupListeners() {
        // 배경색 버튼
        View.OnClickListener themeListener = v -> {
            if (v.getId() == R.id.btn_theme_white) currentSetting.backgroundColor = "#F5F5F5";
            else if (v.getId() == R.id.btn_theme_light_gray) currentSetting.backgroundColor = "#E0E0E0";
            else if (v.getId() == R.id.btn_theme_dark_gray) currentSetting.backgroundColor = "#424242";
            else if (v.getId() == R.id.btn_theme_black) currentSetting.backgroundColor = "#000000";
            else if (v.getId() == R.id.btn_theme_beige) currentSetting.backgroundColor = "#F6F1E5";
            else if (v.getId() == R.id.btn_theme_green) currentSetting.backgroundColor = "#233E3B";
            updatePreview();
        };
        btnThemeWhite.setOnClickListener(themeListener);
        btnThemeLightGray.setOnClickListener(themeListener);
        btnThemeDarkGray.setOnClickListener(themeListener);
        btnThemeBlack.setOnClickListener(themeListener);
        btnThemeBeige.setOnClickListener(themeListener);
        btnThemeGreen.setOnClickListener(themeListener);

        // 글꼴 라디오 그룹
        rgFontFamily.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_font_ridi) currentSetting.fontFamily = "RIDIBATANG";
            else if (checkedId == R.id.rb_font_kopub) currentSetting.fontFamily = "KOPUB_BATANG";
            else if (checkedId == R.id.rb_font_nanum_barun) currentSetting.fontFamily = "NANUM_BARUN";
            else if (checkedId == R.id.rb_font_nanum_round) currentSetting.fontFamily = "NANUM_ROUND";
            else if (checkedId == R.id.rb_font_maru) currentSetting.fontFamily = "MARU";
            else currentSetting.fontFamily = "DEFAULT";
            updatePreview();
        });

        // 글씨 굵기 스위치
        switchBold.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentSetting.isBold = isChecked;
            updatePreview();
        });

        // 글씨 크기 슬라이드
        sbFontSize.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSetting.fontSize = 12f + progress;
                updatePreview();
            }
        });

        // 줄 간격 슬라이드
        sbLineSpacing.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSetting.lineSpacing = 1.0f + (progress * 0.1f);
                updatePreview();
            }
        });

        // 자간 슬라이드
        sbLetterSpacing.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSetting.letterSpacing = progress * 0.02f;
                updatePreview();
            }
        });

        // 저장 버튼
        btnSaveSettings.setOnClickListener(v -> {
            AppDatabase.getInstance(this).userDao().updateUserSetting(currentSetting);
            Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    // 변경된 설정값을 미리보기 텍스트 뷰에 반영
    private void updatePreview() {
        tvFontSizeLabel.setText(String.format("글자 크기 (%dsp)", (int) currentSetting.fontSize));
        tvLineSpacingLabel.setText(String.format("줄 간격 (%.1fx)", currentSetting.lineSpacing));
        tvLetterSpacingLabel.setText(String.format("자간 넓게 (%.2f)", currentSetting.letterSpacing));

        tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSetting.fontSize);
        tvPreview.setLineSpacing(0, currentSetting.lineSpacing);
        tvPreview.setLetterSpacing(currentSetting.letterSpacing);

        Typeface baseFace = Typeface.DEFAULT;
        try {
            if ("RIDIBATANG".equals(currentSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.ridibatang);
            else if ("KOPUB_BATANG".equals(currentSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.kopub_batang);
            else if ("NANUM_BARUN".equals(currentSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.nanum_barun_gothic);
            else if ("NANUM_ROUND".equals(currentSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.nanum_square_round);
            else if ("MARU".equals(currentSetting.fontFamily)) baseFace = ResourcesCompat.getFont(this, R.font.maruburi);
        } catch (Exception e) {
            e.printStackTrace();
        }

        tvPreview.setTypeface(baseFace, currentSetting.isBold ? Typeface.BOLD : Typeface.NORMAL);

        previewContainer.setBackgroundColor(Color.parseColor(currentSetting.backgroundColor));
        int textColor = Color.BLACK;
        switch (currentSetting.backgroundColor) {
            case "#F5F5F5": textColor = Color.parseColor("#333333"); break;
            case "#E0E0E0": textColor = Color.parseColor("#000000"); break;
            case "#424242": textColor = Color.parseColor("#E0E0E0"); break;
            case "#000000": textColor = Color.parseColor("#FFFFFF"); break;
            case "#F6F1E5": textColor = Color.parseColor("#4E3726"); break;
            case "#233E3B": textColor = Color.parseColor("#BDD9B9"); break;
        }
        tvPreview.setTextColor(textColor);
    }

    private abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}