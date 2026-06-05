package com.example.hbook.model.auth;

import com.google.gson.annotations.SerializedName;

public class LoginResponse {
    private int userId;
    private String token;
    private String message;
    private SettingDto setting;

    public int getUserId() { return userId; }
    public String getToken() { return  token; }
    public String getMessage() { return message; }
    public SettingDto   getSetting(){ return setting; }

    public static class SettingDto {
        @SerializedName("fontSize")         public float   fontSize         = 18f;
        @SerializedName("fontFamily")       public String  fontFamily       = "DEFAULT";
        @SerializedName("lineSpacing")      public float   lineSpacing      = 1.5f;
        @SerializedName("backgroundColor") public String  backgroundColor  = "#F5F5F5";
        @SerializedName("letterSpacing")    public float   letterSpacing    = 0f;
        @SerializedName("paragraphSpacing") public float   paragraphSpacing = 1.0f;
        @SerializedName("isBold")           public boolean isBold           = false;
        @SerializedName("ttsVoice")         public String  ttsVoice         = "Cherry";
    }
}
