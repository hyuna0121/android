package com.example.hbook.model;

import java.util.List;

/**
 * POST /api/detect-corners 응답 모델.
 *
 * 서버가 각 이미지에 대해 자동 감지한 꼭짓점 정보를 담습니다.
 *
 * JSON 구조:
 * {
 *   "status": "success",
 *   "total_images": 3,
 *   "results": [
 *     {
 *       "index":         0,
 *       "corners":       "120,80,2900,80,2900,3950,120,3950",
 *       "confidence":    0.912,
 *       "auto_detected": true
 *     },
 *     ...
 *   ]
 * }
 */
public class DetectCornersResponse {

    public String            status;
    public int               total_images;
    public List<ImageResult> results;

    public static class ImageResult {

        /** results 배열 내 순서 인덱스 (0-based) */
        public int     index;

        /**
         * "x0,y0,x1,y1,x2,y2,x3,y3" 형식의 꼭짓점 문자열 (원본 픽셀 좌표).
         * 디코딩 실패 시 빈 문자열.
         */
        public String  corners;

        /** 자동 감지 신뢰도 (0.0 ~ 1.0) */
        public float   confidence;

        /**
         * true  → 자동 확정 권장 (confidence ≥ 0.75)
         * false → 사용자 수동 지정 필요
         */
        public boolean auto_detected;
    }
}
