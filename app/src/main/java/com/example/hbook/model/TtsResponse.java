package com.example.hbook.model;

import java.util.List;

public class TtsResponse {
    // WAV 파일 base64
    public String audio_base64;

    // "audio/wav"
    public String content_type;

    // 청크별 재생 구간 정보
    public List<TimestampEntry> timestamps;
}
