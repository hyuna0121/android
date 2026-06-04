package com.example.hbook.model;

import java.io.File;

/**
 * 촬영/선택된 이미지 한 장과 꼭짓점 좌표를 함께 보관하는 모델.
 *
 * corners 배열 구조 (원본 이미지 픽셀 좌표 기준, 8개 float):
 *   [0,1] = TL(좌상), [2,3] = TR(우상), [4,5] = BR(우하), [6,7] = BL(좌하)
 *
 * autoDetected 플래그:
 *   true  → 서버 자동 감지로 확정된 corners (미리보기에서 수정 가능)
 *   false → 사용자가 직접 지정한 corners
 */
public class CapturedItem {

    public final File    file;
    public final float[] corners;       // null 허용
    public final boolean autoDetected;  // 자동 감지 여부

    /** 수동 지정 생성자 (기존 호환) */
    public CapturedItem(File file, float[] corners) {
        this(file, corners, false);
    }

    /** 전체 생성자 */
    public CapturedItem(File file, float[] corners, boolean autoDetected) {
        this.file          = file;
        this.corners       = corners;
        this.autoDetected  = autoDetected;
    }

    /** 꼭짓점 좌표를 "x0,y0,x1,y1,...,x3,y3" 형태의 문자열로 반환 (서버 전송용) */
    public String cornersToString() {
        if (corners == null || corners.length < 8) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(",");
            sb.append(Math.round(corners[i]));
        }
        return sb.toString();
    }

    public boolean hasCorners() {
        return corners != null && corners.length == 8;
    }

    /** corners 문자열을 float[] 로 파싱하는 정적 유틸 */
    public static float[] parseCorners(String cornersStr) {
        if (cornersStr == null || cornersStr.isEmpty()) return null;
        try {
            String[] parts = cornersStr.split(",");
            if (parts.length != 8) return null;
            float[] arr = new float[8];
            for (int i = 0; i < 8; i++) arr[i] = Float.parseFloat(parts[i].trim());
            return arr;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
