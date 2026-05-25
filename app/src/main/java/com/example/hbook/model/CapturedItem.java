package com.example.hbook.model;

import java.io.File;

/**
 * 촬영/선택된 이미지 한 장과 사용자가 지정한 꼭짓점 좌표를 함께 보관하는 모델.
 *
 * corners 배열 구조 (원본 이미지 픽셀 좌표 기준, 8개 float):
 *   [0,1] = TL(좌상), [2,3] = TR(우상), [4,5] = BR(우하), [6,7] = BL(좌하)
 *
 * corners 가 null 이면 꼭짓점 미지정 → 서버가 자동 원근 보정 사용.
 */
public class CapturedItem {

    public final File   file;
    public final float[] corners; // null 허용

    public CapturedItem(File file, float[] corners) {
        this.file    = file;
        this.corners = corners;
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
}
