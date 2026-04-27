package com.example.hbook.model;

import java.util.List;

public class OcrResponse {
    public String status;

    // 서버가 1장만 텍스트로 변환
    public String extracted_text;
    public int start_number;
    public int total_images;

    public Sentiment sentiment;

    // 서버가 보내주는 results 배열을 리스트로 받음
    public List<PageResult> results;

    // 리스트 안에 들어갈 개별 페이지 데이터 구조
    public static class PageResult {
        public String status;
        public int page_number;
        public String extracted_text;
        public Sentiment sentiment;
    }

    public static class Sentiment {
        public String label;
        public float confidence_score;
        public float valence;
        public float arousal;
    }
}
