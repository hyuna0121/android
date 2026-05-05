package com.example.hbook.ui;

import android.os.Bundle;
import android.telecom.Call;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.data.UserDao;
import com.example.hbook.model.User;
import com.example.hbook.model.UserSetting;

import org.w3c.dom.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class SignupActivity extends AppCompatActivity {

    private EditText etEmail, etPassword, etDob;
    private CheckBox cbDyslexia;
    private Button btnCheckEmail, btnSubmit;

    private boolean isEmailChecked = false;
    private String checkedEmail = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        etEmail = findViewById(R.id.et_signup_email);
        etPassword = findViewById(R.id.et_signup_password);
        etDob = findViewById(R.id.et_signup_dob);
        cbDyslexia = findViewById(R.id.cb_dyslexia);
        btnCheckEmail = findViewById(R.id.btn_check_email);
        btnSubmit = findViewById(R.id.btn_signup_submit);

        // 이메일 입력값 바뀌면 중복검사 재수행
        etEmail.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {}
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                isEmailChecked = false;
            }
        });

        btnCheckEmail.setOnClickListener(v -> checkEmailDuplication());
        btnSubmit.setOnClickListener(v -> attemptSignup());
    }

    private void checkEmailDuplication() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty()) {
            Toast.makeText(this, "이메일을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            User existingUser = db.userDao().getUserByEmail(email);

            runOnUiThread(() -> {
                if (existingUser != null) {
                    Toast.makeText(this, "이미 사용 중인 이메일입니다.", Toast.LENGTH_SHORT).show();
                    isEmailChecked = false;
                } else {
                    Toast.makeText(this, "사용 가능한 이메일입니다.", Toast.LENGTH_SHORT).show();
                    isEmailChecked = true;
                    checkedEmail = email;
                }
            });
        });
    }

    private void attemptSignup() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String dob = etDob.getText().toString().trim();
        boolean hasDyslexia = cbDyslexia.isChecked();

        if (!isEmailChecked || !email.equals(checkedEmail)) {
            Toast.makeText(this, "이메일 중복 확인을 해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        String pwPattern = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,}$";
        if (!Pattern.matches(pwPattern, password)) {
            Toast.makeText(this, "비밀번호는 영문과 숫자 조합 8자 이상이어야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (dob.length() != 8 || !dob.matches("^[0-9]+$")) {
            Toast.makeText(this, "생년월일은 숫자 8자리로 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int birthYear = Integer.parseInt(dob.substring(0, 4));
        int birthMonthDay = Integer.parseInt(dob.substring(4, 8));

        Calendar today = Calendar.getInstance();
        int currentYear = today.get(Calendar.YEAR);
        int currentMonthDay = (today.get(Calendar.MONTH) + 1) * 100 + today.get(Calendar.DAY_OF_MONTH);

        int calculatedAge = currentYear - birthYear;
        if (currentMonthDay < birthMonthDay) {
            calculatedAge--;
        }
        final int age = calculatedAge;

        String hashedPassword = hashPassword(password);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            UserDao userDao = db.userDao();

            User newUser = new User(email, hashedPassword, age, hasDyslexia);
            long newUserId = userDao.insertUser(newUser);

            UserSetting defaultSetting = new UserSetting((int) newUserId);

            if (age >= 65) {
                // 1. 노인: 글자 크기 크게, 줄 간격 약간 넓게
                defaultSetting.fontSize = 28f;
                defaultSetting.lineSpacing = 1.8f;
                defaultSetting.backgroundColor = "#FDF5E6";
            } else if (age <= 12) {
                // 2. 어린이: 글자 크기 약간 크게, 부드러운 배경색
                defaultSetting.fontSize = 22f;
                defaultSetting.lineSpacing = 1.8f;
                defaultSetting.backgroundColor = "#FFF0F5";
            }

            if (hasDyslexia) {
                // 난독증 환자: 글자 크기 약간 크게, 줄 간격 & 자간 & 문단 간격 넓게, 눈이 편한 배경색
                defaultSetting.lineSpacing = Math.max(defaultSetting.lineSpacing, 2.0f);
                defaultSetting.letterSpacing = 0.15f;
                defaultSetting.paragraphSpacing = 2.0f;
                defaultSetting.backgroundColor = "#FDF5E6";
                defaultSetting.fontFamily = "SANS_SERIF";
            }

                userDao.insertUserSetting(defaultSetting);

            runOnUiThread(() -> {
                Toast.makeText(SignupActivity.this, "가입 완료!", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("비밀번호 암호화 에러 발생", e);
        }
    }
}