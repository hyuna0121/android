package com.example.hbook.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hbook.R;
import com.example.hbook.model.auth.CheckEmailResponse;
import com.example.hbook.model.auth.SignupRequest;
import com.example.hbook.model.auth.SignupResponse;
import com.example.hbook.network.ApiClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.regex.Pattern;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SignupActivity extends AppCompatActivity {

    private EditText    etEmail, etPassword, etDob;
    private CheckBox    cbDyslexia;
    private Button      btnCheckEmail, btnSubmit;
    private ProgressBar progressBar;

    private boolean isEmailChecked = false;
    private String  checkedEmail   = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        etEmail       = findViewById(R.id.et_signup_email);
        etPassword    = findViewById(R.id.et_signup_password);
        etDob         = findViewById(R.id.et_signup_dob);
        cbDyslexia    = findViewById(R.id.cb_dyslexia);
        btnCheckEmail = findViewById(R.id.btn_check_email);
        btnSubmit     = findViewById(R.id.btn_signup_submit);
        progressBar   = findViewById(R.id.progress_bar);

        etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {}
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                isEmailChecked = false;
            }
        });

        btnCheckEmail.setOnClickListener(v -> checkEmail());
        btnSubmit.setOnClickListener(v -> attemptSignup());
    }

    private void checkEmail() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty()) {
            Toast.makeText(this, "이메일을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        btnCheckEmail.setEnabled(false);

        ApiClient.getService(this)
                .checkEmail(email)
                .enqueue(new Callback<CheckEmailResponse>() {
                    @Override
                    public void onResponse(Call<CheckEmailResponse> call,
                                           Response<CheckEmailResponse> response) {
                        btnCheckEmail.setEnabled(true);
                        if (response.isSuccessful() && response.body() != null) {
                            if (response.body().isAvailable()) {
                                Toast.makeText(SignupActivity.this,
                                        "사용 가능한 이메일입니다.", Toast.LENGTH_SHORT).show();
                                isEmailChecked = true;
                                checkedEmail   = email;
                            } else {
                                Toast.makeText(SignupActivity.this,
                                        "이미 사용 중인 이메일입니다.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call<CheckEmailResponse> call, Throwable t) {
                        btnCheckEmail.setEnabled(true);
                        Toast.makeText(SignupActivity.this,
                                "연결 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void attemptSignup() {
        String  email       = etEmail.getText().toString().trim();
        String  password    = etPassword.getText().toString().trim();
        String  dob         = etDob.getText().toString().trim();
        boolean hasDyslexia = cbDyslexia.isChecked();

        if (!isEmailChecked || !email.equals(checkedEmail)) {
            Toast.makeText(this, "이메일 중복 확인을 해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Pattern.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,}$", password)) {
            Toast.makeText(this,
                    "비밀번호는 영문+숫자 조합 8자 이상이어야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (dob.length() != 8 || !dob.matches("^[0-9]+$")) {
            Toast.makeText(this, "생년월일은 숫자 8자리로 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int birthYear = Integer.parseInt(dob.substring(0, 4));
        int birthMD   = Integer.parseInt(dob.substring(4, 8));
        Calendar today = Calendar.getInstance();
        int curMD = (today.get(Calendar.MONTH) + 1) * 100 + today.get(Calendar.DAY_OF_MONTH);
        int age   = today.get(Calendar.YEAR) - birthYear - (curMD < birthMD ? 1 : 0);

        setLoading(true);

        ApiClient.getService(this)
                .signup(new SignupRequest(email, hashPassword(password), age, hasDyslexia))
                .enqueue(new Callback<SignupResponse>() {
                    @Override
                    public void onResponse(Call<SignupResponse> call,
                                           Response<SignupResponse> response) {
                        setLoading(false);
                        if (response.isSuccessful()
                                && response.body() != null
                                && response.body().isSuccess()) {
                            Toast.makeText(SignupActivity.this,
                                    "가입 완료! 로그인해주세요.", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(SignupActivity.this, LoginActivity.class));
                            finish();
                        } else {
                            String msg = (response.body() != null)
                                    ? response.body().getMessage()
                                    : "회원가입 실패 (" + response.code() + ")";
                            Toast.makeText(SignupActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<SignupResponse> call, Throwable t) {
                        setLoading(false);
                        Toast.makeText(SignupActivity.this,
                                "연결 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setLoading(boolean on) {
        btnSubmit.setEnabled(!on);
        btnCheckEmail.setEnabled(!on);
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
            throw new RuntimeException(e);
        }
    }
}
