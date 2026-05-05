package com.example.hbook.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.hbook.model.User;
import com.example.hbook.model.UserSetting;

@Dao
public interface UserDao {
    // 유저 정보 저장
    @Insert
    long insertUser(User user);

    // 회원가입 시 기본 설정값 생성
    @Insert
    void insertUserSetting(UserSetting setting);

    // 이메일과 비밀번호 동시에 일치하는 유저 찾음
    @Query("SELECT * FROM users WHERE email = :email AND password = :password")
    User login(String email, String password);

    // 이메일 중복 검사
    @Query("SELECT * FROM users WHERE email = :email")
    User getUserByEmail(String email);

    // 로그인한 유저의 설정 불러옴
    @Query("SELECT * FROM user_settings WHERE user_id = :userId")
    UserSetting getUserSetting(int userId);

    @Update
    void updateUserSetting(UserSetting userSetting);
}
