package com.example.hbook.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.hbook.model.Book;
import com.example.hbook.model.Page;
import com.example.hbook.model.ReaderLog;

import java.util.List;

@Dao
public interface LibraryDao {
    // 책 저장, 방금 생성된 책 ID 반환받음
    @Insert
    long insertBook(Book book);

    @Update
    void updateBook(Book book);

    @Delete
    void deleteBook(Book book);

    // 최신순으로 책 목록 가져오기
    @Query("SELECT * FROM books WHERE user_id = :userId ORDER BY created_at DESC")
    List<Book> getAllBooks(int userId);

    // 최신순 정렬
    @Query("SELECT * FROM books WHERE user_id = :userId ORDER BY is_favorite DESC, created_at DESC")
    List<Book> getAllBooksSortedByDate(int userId);

    // 이름순 정렬
    @Query("SELECT * FROM books WHERE user_id = :userId ORDER BY is_favorite DESC, title ASC")
    List<Book> getAllBooksSortedByName(int userId);

    // 페이지 하나 저장
    @Insert
    void insertPage(Page page);

    // 읽을 책의 모든 페이지 가져오기
    @Query("SELECT * FROM pages WHERE book_id = :bookId ORDER BY page_number ASC")
    List<Page> getPagesForBook(int bookId);

    @Insert
    void insertLog(ReaderLog log);

    @Query("SELECT * FROM reader_logs WHERE book_id = :bookId")
    List<ReaderLog> getLogsForBook(int bookId);
}
