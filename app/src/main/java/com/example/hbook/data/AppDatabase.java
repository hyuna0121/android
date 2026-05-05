package com.example.hbook.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.hbook.model.Book;
import com.example.hbook.model.Page;
import com.example.hbook.model.ReaderLog;
import com.example.hbook.model.User;
import com.example.hbook.model.UserSetting;

@Database(entities = {Book.class, Page.class, ReaderLog.class, User.class, UserSetting.class}, version = 7)
public abstract class AppDatabase extends RoomDatabase {
    public abstract LibraryDao libraryDao();
    public abstract UserDao userDao();

    private static AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "my_ocr_library.db")
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()   // 버전 바뀔 시 기존 DB 포맷하고 새 구조로 덮음
                    .build();
        }
        return INSTANCE;
    }
}
