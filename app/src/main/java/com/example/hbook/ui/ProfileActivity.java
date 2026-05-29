package com.example.hbook.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.UserSetting;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class ProfileActivity extends AppCompatActivity {

    private int currentUserId = -1;
    private TextView tvCurrentVoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        LinearLayout btnViewerSettings = findViewById(R.id.btn_viewer_settings);
        btnViewerSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, ViewerSettingActivity.class));
        });

        LinearLayout btnTtsVoice = findViewById(R.id.btn_tts_voice);
        btnTtsVoice.setOnClickListener(v -> {
            startActivity(new Intent(this, TtsVoiceActivity.class));
        });

        LinearLayout btnLogout = findViewById(R.id.btn_logout);
        btnLogout.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.clear();
            editor.apply();
            Toast.makeText(this, "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        });

        tvCurrentVoice = findViewById(R.id.tv_current_voice);

        ImageView ivHome = findViewById(R.id.iv_home);
        ivHome.setOnClickListener(v -> {
            Intent intent = new Intent(ProfileActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showNameInputDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // TtsVoiceActivity 에서 돌아왔을 때 현재 음성 표시 갱신
        UserSetting setting = AppDatabase.getInstance(this)
                .userDao().getUserSetting(currentUserId);
        if (setting != null && tvCurrentVoice != null) {
            tvCurrentVoice.setText(setting.ttsVoice != null ? setting.ttsVoice : "Cherry");
        }
    }

    private void showNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("새로운 책 추가");
        builder.setMessage("저장할 책의 이름을 입력해 주세요.");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = 64;
        params.rightMargin = 64;
        input.setLayoutParams(params);

        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("확인", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String bookName = input.getText().toString();

                Intent intent = new Intent(ProfileActivity.this, CameraActivity.class);
                intent.putExtra("BOOK_NAME", bookName);
                intent.putExtra("USER_ID", currentUserId);
                startActivity(intent);
            }
        });

        builder.setNegativeButton("취소", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }
}