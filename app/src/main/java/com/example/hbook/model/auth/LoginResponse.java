package com.example.hbook.model.auth;

public class LoginResponse {
    private int userId;
    private String token;
    private String message;

    public int getUserId() { return userId; }
    public String getToken() { return  token; }
    public String getMessage() { return message; }
}
