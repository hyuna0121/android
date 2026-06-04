package com.example.hbook.model.auth;

import com.google.gson.annotations.SerializedName;

public class SignupRequest {
    private String email;
    private String password;
    private int age;

    @SerializedName("has_dyslexia")
    private boolean hasDyslexia;

    public SignupRequest(String email, String password, int age, boolean hasDyslexia) {
        this.email = email;
        this.password = password;
        this.age = age;
        this.hasDyslexia = hasDyslexia;
    }
}
