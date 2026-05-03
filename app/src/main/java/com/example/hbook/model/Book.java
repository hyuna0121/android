package com.example.hbook.model;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "books")
public class Book {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "book_id")
    public int id;

    @ColumnInfo(name = "user_id")
    public int userId;

    @NonNull
    public String title;

    @ColumnInfo(name = "is_favorite")
    public boolean isFavorite;

    @ColumnInfo(name = "last_read_page")
    public int lastReadPage;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public Book(String title, int userId) {
        this.title = title;
        this.userId = userId;
        this.isFavorite = false;
        this.lastReadPage = 1;
        this.createdAt = System.currentTimeMillis();
    }
}
