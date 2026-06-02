package com.example.hbook.model;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(tableName = "pages",
        foreignKeys = @ForeignKey(
                entity = Book.class,
                parentColumns = "book_id",
                childColumns = "book_id",
                onDelete = ForeignKey.CASCADE
        ))
public class Page {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "page_id")
    public int pageId;

    @ColumnInfo(name = "book_id")
    public int bookId;

    @ColumnInfo(name = "page_number")
    public int pageNumber;

    // GPT 교정 텍스트
    @ColumnInfo(name = "extracted_text")
    public String extractedText;

    @ColumnInfo(name = "emotion_valence")
    public float emotionValence;

    @ColumnInfo(name = "emotion_arousal")
    public float emotionArousal;

    @ColumnInfo(name = "emotion_dominance", defaultValue = "0.0")
    public float emotionDominance;

    // KoBERT 감정 레이블
    @Nullable
    @ColumnInfo(name = "emotion_label", defaultValue = "")
    public String emotionLabel;

    // Qwen3 TTS 생성 후 저장한 .wav 파일의 절대 경로
    @ColumnInfo(name = "audio_file_path")
    public String audioFilePath;

    public Page(int bookId, int pageNumber, String extractedText) {
        this.bookId = bookId;
        this.pageNumber = pageNumber;
        this.extractedText = extractedText;
    }
}
