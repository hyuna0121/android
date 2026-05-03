package com.example.hbook.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "users", indices = {@Index(value = "email", unique = true)})
public class User {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "user_id")
    public int id;

    public String email;
    public String password;
    public int age;

    @ColumnInfo(name = "has_dyslexia")
    public boolean hasDyslexia;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public User(String email, String password, int age, boolean hasDyslexia) {
        this.email = email;
        this.password = password;
        this.age = age;
        this.hasDyslexia = hasDyslexia;
        this.createdAt = System.currentTimeMillis();
    }
}
