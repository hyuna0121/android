package com.example.hbook.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.hbook.model.Book;
import com.example.hbook.model.Page;
import com.example.hbook.model.ReaderLog;
import com.example.hbook.model.User;
import com.example.hbook.model.UserSetting;

@Database(entities = {Book.class, Page.class, ReaderLog.class, User.class, UserSetting.class}, version = 9)
public abstract class AppDatabase extends RoomDatabase {
    public abstract LibraryDao libraryDao();
    public abstract UserDao userDao();

    private static AppDatabase INSTANCE;

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE pages ADD COLUMN emotion_label TEXT DEFAULT ''"
            );
            database.execSQL(
                    "ALTER TABLE pages ADD COLUMN audio_file_path TEXT"
            );
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE user_settings ADD COLUMN tts_voice TEXT DEFAULT 'Cherry'"
            );
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "my_ocr_library.db")
                    .allowMainThreadQueries()
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()   // 버전 바뀔 시 기존 DB 포맷하고 새 구조로 덮음
                    .build();
        }
        return INSTANCE;
    }
}
