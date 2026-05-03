package com.example.hbook.model;

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

    public Page(int bookId, int pageNumber, String extractedText) {
        this.bookId = bookId;
        this.pageNumber = pageNumber;
        this.extractedText = extractedText;
    }
}
