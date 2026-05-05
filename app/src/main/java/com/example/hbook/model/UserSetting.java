package com.example.hbook.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(tableName = "user_settings",
        foreignKeys = @ForeignKey(
                entity = User.class,
                parentColumns = "user_id",
                childColumns = "user_id",
                onDelete = ForeignKey.CASCADE
        ))
public class UserSetting {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "setting_id")
    public int settingId;

    @ColumnInfo(name = "user_id")
    public int userId;

    @ColumnInfo(name = "font_size")
    public float fontSize = 18f;

    @ColumnInfo(name = "font_family")
    public String fontFamily = "DEFAULT";

    @ColumnInfo(name = "line_spacing")
    public float lineSpacing = 1.5f;

    @ColumnInfo(name = "background_color")
    public String backgroundColor = "#F5F5F5";

    @ColumnInfo(name = "letter_spacing")
    public float letterSpacing = 0.05f;

    @ColumnInfo(name = "paragraph_spacing")
    public float paragraphSpacing = 1.0f;

    @ColumnInfo(name = "is_bold")
    public boolean isBold;

    public UserSetting(int userId) {
        this.userId = userId;
    }
}
