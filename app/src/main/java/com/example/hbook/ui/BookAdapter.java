package com.example.hbook.ui;

import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hbook.R;
import com.example.hbook.model.Book;
import com.google.android.material.card.MaterialCardView;
import com.example.hbook.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.BookViewHolder> {

    private static final int[] THUMB_COLORS = {
            0xFFEDE9FE, // 연보라
            0xFFDCEEFD, // 연파랑
            0xFFE8F8F0, // 연초록
            0xFFFFF3E8, // 연주황
            0xFFFCE8F3, // 연분홍
            0xFFE8F8F8, // 연민트
    };

    private List<Book> bookList;
    private boolean isDeleteMode = false;
    private Set<Book> selectedBooks = new HashSet<>();

    public interface OnSelectionChangeListener {
        void onSelectionChanged(int count);
    }
    private OnSelectionChangeListener selectionChangeListener;

    /** 즐겨찾기 변경 시 호출 — DB 저장 및 목록 재정렬을 Activity에 위임 */
    public interface OnFavoriteChangeListener {
        void onFavoriteChanged(Book book);
    }
    private OnFavoriteChangeListener favoriteChangeListener;

    public BookAdapter(List<Book> bookList, OnSelectionChangeListener listener) {
        this.bookList = bookList;
        this.selectionChangeListener = listener;
    }

    public void setFavoriteChangeListener(OnFavoriteChangeListener listener) {
        this.favoriteChangeListener = listener;
    }

    public void setDeleteMode(boolean mode) {
        this.isDeleteMode = mode;
        this.selectedBooks.clear();
        notifyDataSetChanged();
    }

    public boolean isDeleteMode() { return isDeleteMode; }
    public Set<Book> getSelectedBooks() { return selectedBooks; }

    public void selectAll() {
        selectedBooks.clear();
        if (bookList != null) selectedBooks.addAll(bookList);
        if (selectionChangeListener != null)
            selectionChangeListener.onSelectionChanged(selectedBooks.size());
        notifyDataSetChanged();
    }

    public void deselectAll() {
        selectedBooks.clear();
        if (selectionChangeListener != null)
            selectionChangeListener.onSelectionChanged(0);
        notifyDataSetChanged();
    }

    public boolean isAllSelected() {
        return bookList != null && !bookList.isEmpty()
                && selectedBooks.size() == bookList.size();
    }

    /** MainActivity의 이름 수정 다이얼로그에서 책 목록 접근용 */
    public List<Book> getBookList() {
        return bookList != null ? bookList : new java.util.ArrayList<>();
    }

    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book, parent, false);
        return new BookViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        Book book = bookList.get(position);

        // 제목
        holder.tvTitle.setText(book.title);

        // 등록일
        if (holder.tvDate != null) {
            try {
                String dateStr = book.createdAt > 0
                        ? new SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
                        .format(new Date(book.createdAt))
                        : "";
                holder.tvDate.setText(dateStr);
            } catch (Exception e) {
                holder.tvDate.setText("");
            }
        }

        // 썸네일 배경색
        if (holder.viewThumbBg != null) {
            holder.viewThumbBg.setBackgroundColor(THUMB_COLORS[position % THUMB_COLORS.length]);
        }

        // 즐겨찾기
        if (book.isFavorite) {
            holder.ivFavorite.setImageResource(android.R.drawable.star_on);
            holder.ivFavorite.setColorFilter(Color.parseColor("#F9CA24"));
        } else {
            holder.ivFavorite.setImageResource(android.R.drawable.star_off);
            holder.ivFavorite.setColorFilter(Color.parseColor("#CCCCCC"));
        }

        // ── 편집 모드 UI ──────────────────────────────────────────────────────
        if (isDeleteMode) {
            holder.ivCheckBox.setVisibility(View.VISIBLE);
            holder.ivFavorite.setVisibility(View.GONE);

            boolean selected = selectedBooks.contains(book);
            updateCheckState(holder, book, selected);
        } else {
            holder.ivCheckBox.setVisibility(View.GONE);
            holder.ivFavorite.setVisibility(View.VISIBLE);
            // 카드 테두리 원복
            if (holder.cardBookCover != null) {
                holder.cardBookCover.setStrokeColor(Color.parseColor("#E8E8E8"));
                holder.cardBookCover.setStrokeWidth(2);
            }
        }

        // 즐겨찾기 클릭 → DB 저장 후 목록 재정렬 콜백
        holder.ivFavorite.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            Book target = bookList.get(pos);
            target.isFavorite = !target.isFavorite;
            // DB 저장 + 목록 새로고침 요청
            if (favoriteChangeListener != null) {
                favoriteChangeListener.onFavoriteChanged(target);
            } else {
                notifyItemChanged(pos);
            }
        });

        // 카드 클릭
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            Book clickedBook = bookList.get(pos);

            if (isDeleteMode) {
                boolean nowSelected = !selectedBooks.contains(clickedBook);
                if (nowSelected) selectedBooks.add(clickedBook);
                else             selectedBooks.remove(clickedBook);
                updateCheckState(holder, clickedBook, nowSelected);
                if (selectionChangeListener != null)
                    selectionChangeListener.onSelectionChanged(selectedBooks.size());
            } else {
                Intent intent = new Intent(v.getContext(), ViewerActivity.class);
                intent.putExtra("BOOK_ID", clickedBook.id);
                intent.putExtra("BOOK_NAME", clickedBook.title);
                v.getContext().startActivity(intent);
            }
        });
    }

    /** 체크 상태 UI 업데이트 (ImageView 기반) */
    private void updateCheckState(BookViewHolder holder, Book book, boolean selected) {
        if (selected) {
            // 체크된 상태: 보라색 체크 이미지
            holder.ivCheckBox.setImageResource(R.drawable.ic_check_on);
            if (holder.cardBookCover != null) {
                holder.cardBookCover.setStrokeColor(Color.parseColor("#6C5CE7"));
                holder.cardBookCover.setStrokeWidth(6);
            }
        } else {
            // 미선택 상태: 빈 체크박스
            holder.ivCheckBox.setImageResource(R.drawable.ic_check_off);
            if (holder.cardBookCover != null) {
                holder.cardBookCover.setStrokeColor(Color.parseColor("#E8E8E8"));
                holder.cardBookCover.setStrokeWidth(2);
            }
        }
    }

    @Override
    public int getItemCount() { return bookList != null ? bookList.size() : 0; }

    public static class BookViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvDate;
        ImageView ivFavorite;
        ImageView ivCheckBox;    // CheckBox 대신 ImageView 사용
        View viewThumbBg;
        MaterialCardView cardBookCover;

        public BookViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle       = itemView.findViewById(R.id.tv_book_title);
            tvDate        = itemView.findViewById(R.id.tv_book_date);
            ivFavorite    = itemView.findViewById(R.id.iv_favorite);
            ivCheckBox    = itemView.findViewById(R.id.iv_check_box);
            viewThumbBg   = itemView.findViewById(R.id.view_thumb_bg);
            cardBookCover = itemView.findViewById(R.id.card_book_cover);
        }
    }
}