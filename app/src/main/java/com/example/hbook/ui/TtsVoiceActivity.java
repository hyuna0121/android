package com.example.hbook.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.UserSetting;

import java.util.HashMap;
import java.util.Map;

public class TtsVoiceActivity extends AppCompatActivity {

    private int currentUserId = -1;
    private String selectedVoice = "Cherry";

    // 카드 → RadioButton 매핑
    private final Map<String, RadioButton> radioButtons = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tts_voice);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        // ── 뷰 바인딩 ──────────────────────────────────────────────
        TextView tvBack       = findViewById(R.id.tv_back);
        LinearLayout cardCherry  = findViewById(R.id.card_cherry);
        LinearLayout cardSerena  = findViewById(R.id.card_serena);
        LinearLayout cardEthan   = findViewById(R.id.card_ethan);
        LinearLayout cardChelsie = findViewById(R.id.card_chelsie);
        RadioButton rbCherry  = findViewById(R.id.rb_cherry);
        RadioButton rbSerena  = findViewById(R.id.rb_serena);
        RadioButton rbEthan   = findViewById(R.id.rb_ethan);
        RadioButton rbChelsie = findViewById(R.id.rb_chelsie);
        Button btnSave        = findViewById(R.id.btn_save);

        radioButtons.put("Cherry",  rbCherry);
        radioButtons.put("Serena",  rbSerena);
        radioButtons.put("Ethan",   rbEthan);
        radioButtons.put("Chelsie", rbChelsie);

        // ── 현재 저장된 음성으로 초기 선택 ────────────────────────
        UserSetting setting = AppDatabase.getInstance(this)
                .userDao().getUserSetting(currentUserId);
        if (setting != null && setting.ttsVoice != null) {
            selectedVoice = setting.ttsVoice;
        }
        updateSelection(selectedVoice);

        // ── 카드 클릭 시 선택 ──────────────────────────────────────
        cardCherry.setOnClickListener(v  -> updateSelection("Cherry"));
        cardSerena.setOnClickListener(v  -> updateSelection("Serena"));
        cardEthan.setOnClickListener(v   -> updateSelection("Ethan"));
        cardChelsie.setOnClickListener(v -> updateSelection("Chelsie"));

        // RadioButton 직접 클릭도 처리
        rbCherry.setOnClickListener(v  -> updateSelection("Cherry"));
        rbSerena.setOnClickListener(v  -> updateSelection("Serena"));
        rbEthan.setOnClickListener(v   -> updateSelection("Ethan"));
        rbChelsie.setOnClickListener(v -> updateSelection("Chelsie"));

        // ── 저장 ──────────────────────────────────────────────────
        btnSave.setOnClickListener(v -> {
            if (setting != null) {
                setting.ttsVoice = selectedVoice;
                AppDatabase.getInstance(this).userDao().updateUserSetting(setting);
            }
            Toast.makeText(this, selectedVoice + " 음성으로 저장되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
        });

        tvBack.setOnClickListener(v -> finish());
    }

    // 선택된 음성만 RadioButton 체크, 나머지 해제
    private void updateSelection(String voice) {
        selectedVoice = voice;
        for (Map.Entry<String, RadioButton> entry : radioButtons.entrySet()) {
            entry.getValue().setChecked(entry.getKey().equals(voice));
        }
    }
}