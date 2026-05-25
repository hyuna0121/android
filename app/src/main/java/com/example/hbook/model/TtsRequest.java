package com.example.hbook.model;

public class TtsRequest {
    public String text;
    public String tts_instruction;
    public int    page_id;

    public TtsRequest(String text, String tts_instruction, int page_id) {
        this.text            = text;
        this.tts_instruction = tts_instruction;
        this.page_id         = page_id;
    }
}
