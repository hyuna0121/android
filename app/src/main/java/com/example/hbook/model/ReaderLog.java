package com.example.hbook.model;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(tableName = "reader_logs",
        foreignKeys = @ForeignKey(
                entity = Book.class,
                parentColumns = "book_id",
                childColumns = "book_id",
                onDelete = ForeignKey.CASCADE
        ))
public class ReaderLog {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "log_id")
    public int logId;

    @ColumnInfo(name = "book_id")
    public int bookId;

    @ColumnInfo(name = "page_number")
    public int pageNumber;

    @ColumnInfo(name = "log_type")
    @NonNull
    public String logType;

    @ColumnInfo(name = "start_index")
    public int startIndex;

    @ColumnInfo(name = "end_index")
    public int endIndex;
    public String color;

    public ReaderLog(int bookId, int pageNumber, String logType, int startIndex, int endIndex, String color) {
        this.bookId = bookId;
        this.pageNumber = pageNumber;
        this.logType = logType;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.color = color;
    }
}
