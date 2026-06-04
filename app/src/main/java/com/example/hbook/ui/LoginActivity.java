package com.example.hbook.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.UserSetting;
import com.example.hbook.model.auth.LoginRequest;
import com.example.hbook.model.auth.LoginResponse;
import com.example.hbook.network.ApiClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends AppCompatActivity {

    private EditText    etEmail, etPassword;
    private Button      btnLogin;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 자동 로그인 체크 ──────────────────────────────────────────
        SharedPreferences prefs   = getSharedPreferences("user_prefs", MODE_PRIVATE);
        String            token   = prefs.getString("auth_token", null);
        long              savedAt = prefs.getLong("token_saved_at", 0);
        long              thirtyDays = 1000L * 60 * 60 * 24 * 30;

        if (token != null && (System.currentTimeMillis() - savedAt) < thirtyDays) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        etEmail     = findViewById(R.id.et_login_email);
        etPassword  = findViewById(R.id.et_login_password);
        btnLogin    = findViewById(R.id.btn_login);
        progressBar = findViewById(R.id.progress_bar);

        TextView tvGoToSignup = findViewById(R.id.tv_go_to_signup);
        tvGoToSignup.setOnClickListener(v ->
                startActivity(new Intent(this, SignupActivity.class)));

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        ApiClient.getService(this)
                .login(new LoginRequest(email, hashPassword(password)))
                .enqueue(new Callback<LoginResponse>() {
                    @Override
                    public void onResponse(Call<LoginResponse> call,
                                           Response<LoginResponse> response) {
                        setLoading(false);

                        if (response.isSuccessful() && response.body() != null) {
                            LoginResponse body = response.body();
                            int userId = body.getUserId();

                            getSharedPreferences("user_prefs", MODE_PRIVATE).edit()
                                    .putInt("logged_in_user_id", userId)
                                    .putString("auth_token", body.getToken())
                                    .putLong("token_saved_at", System.currentTimeMillis())
                                    .apply();

                            // 로컬 Room에 UserSetting이 없으면 생성
                            ExecutorService executor = Executors.newSingleThreadExecutor();
                            executor.execute(() -> {
                                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                                UserSetting existing = db.userDao().getUserSetting(userId);
                                if (existing == null) {
                                    db.userDao().insertUserSetting(new UserSetting(userId));
                                }
                                runOnUiThread(() -> goToMain());
                            });

                        } else if (response.code() == 401) {
                            Toast.makeText(LoginActivity.this,
                                    "이메일 또는 비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(LoginActivity.this,
                                    "서버 오류: " + response.code(), Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<LoginResponse> call, Throwable t) {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this,
                                "연결 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void setLoading(boolean on) {
        btnLogin.setEnabled(!on);
        if (progressBar != null)
            progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private String hashPassword(String pw) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] hash = d.digest(pw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("비밀번호 암호화 오류", e);
        }
    }
}