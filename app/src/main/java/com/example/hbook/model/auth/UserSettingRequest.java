package com.example.hbook.model.auth;

import com.google.gson.annotations.SerializedName;
import com.example.hbook.model.UserSetting;

/** PUT /api/user/settings 요청 바디 — 서버 UserSettingUpdateRequest와 필드명 일치 */
public class UserSettingRequest {
    @SerializedName("font_size")         public float   font_size;
    @SerializedName("font_family")       public String  font_family;
    @SerializedName("line_spacing")      public float   line_spacing;
    @SerializedName("background_color")  public String  background_color;
    @SerializedName("letter_spacing")    public float   letter_spacing;
    @SerializedName("paragraph_spacing") public float   paragraph_spacing;
    @SerializedName("is_bold")           public boolean is_bold;
    @SerializedName("tts_voice")         public String  tts_voice;

    public static UserSettingRequest from(UserSetting s) {
        UserSettingRequest r  = new UserSettingRequest();
        r.font_size         = s.fontSize;
        r.font_family       = s.fontFamily        != null ? s.fontFamily        : "DEFAULT";
        r.line_spacing      = s.lineSpacing;
        r.background_color  = s.backgroundColor   != null ? s.backgroundColor   : "#F5F5F5";
        r.letter_spacing    = s.letterSpacing;
        r.paragraph_spacing = s.paragraphSpacing;
        r.is_bold           = s.isBold;
        r.tts_voice         = s.ttsVoice          != null ? s.ttsVoice          : "Cherry";
        return r;
    }
}