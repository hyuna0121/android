package com.example.hbook.model;

public class TimestampEntry {
    public int    text_index;

    // 초 단위 (오디오 시작 시간)
    public float  start;

    // 초 단위 (오디오 종료 시간)
    public float  end;

    // 해당 청크 텍스트
    public String text;
}
