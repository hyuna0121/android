package com.example.hbook.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.Book;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private RecyclerView rvLibrary;
    private BookAdapter adapter;
    private EditText etSearch;
    private ImageView ivMore;
    private LinearLayout layoutDeleteMode;
    private LinearLayout layoutSelectAll;
    private View dividerSelectAll;
    private CheckBox cbSelectAll;
    private ImageView ivCheckAllIcon;
    private TextView tvDeleteCount;
    private FloatingActionButton fabAdd;

    private int currentUserId = -1;
    private String currentSortType = "LATEST";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);
        if (currentUserId == -1) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        rvLibrary        = findViewById(R.id.rv_library);
        etSearch         = findViewById(R.id.et_search);
        ivMore           = findViewById(R.id.iv_more);
        layoutDeleteMode = findViewById(R.id.layout_delete_mode);
        layoutSelectAll  = findViewById(R.id.layout_select_all);
        dividerSelectAll = findViewById(R.id.divider_select_all);
        cbSelectAll      = findViewById(R.id.cb_select_all);
        ivCheckAllIcon   = findViewById(R.id.iv_check_all_icon);
        tvDeleteCount    = findViewById(R.id.tv_delete_count);
        fabAdd           = findViewById(R.id.fab_add);

        TextView tvCancelDelete  = findViewById(R.id.tv_cancel_delete);
        TextView tvConfirmDelete = findViewById(R.id.tv_confirm_delete);
        ImageView ivProfile      = findViewById(R.id.iv_profile);

        rvLibrary.setLayoutManager(new GridLayoutManager(this, 3));

        ivMore.setOnClickListener(v -> showBottomSheetMenu());
        tvCancelDelete.setOnClickListener(v -> exitDeleteMode());
        tvConfirmDelete.setOnClickListener(v -> executeDelete());
        ivProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
        fabAdd.setOnClickListener(v -> showNameInputDialog());

        // 전체 선택: 텍스트 클릭 or 아이콘 클릭 모두 동작
        View.OnClickListener selectAllClick = v -> toggleSelectAll();
        cbSelectAll.setOnClickListener(selectAllClick);
        ivCheckAllIcon.setOnClickListener(selectAllClick);
        layoutSelectAll.setOnClickListener(selectAllClick);

        refreshBookList(currentSortType);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentUserId != -1) refreshBookList(currentSortType);
    }

    private void toggleSelectAll() {
        if (adapter == null) return;
        if (adapter.isAllSelected()) {
            adapter.deselectAll();
            ivCheckAllIcon.setImageResource(R.drawable.ic_check_off);
        } else {
            adapter.selectAll();
            ivCheckAllIcon.setImageResource(R.drawable.ic_check_on);
        }
        tvDeleteCount.setText(adapter.getSelectedBooks().size() + "개 선택");
    }

    // ── BottomSheet 메뉴 ──────────────────────────────────────────────────
    private void showBottomSheetMenu() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_menu, null);
        sheet.setContentView(view);

        ImageView ivCheckLatest = view.findViewById(R.id.iv_check_latest);
        ImageView ivCheckName   = view.findViewById(R.id.iv_check_name);
        ivCheckLatest.setVisibility(currentSortType.equals("LATEST") ? View.VISIBLE : View.GONE);
        ivCheckName.setVisibility(currentSortType.equals("NAME")     ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.menu_edit).setOnClickListener(v -> {
            sheet.dismiss();
            showRenameDialog();
        });
        view.findViewById(R.id.menu_sort_latest).setOnClickListener(v -> {
            currentSortType = "LATEST";
            refreshBookList("LATEST");
            sheet.dismiss();
        });
        view.findViewById(R.id.menu_sort_name).setOnClickListener(v -> {
            currentSortType = "NAME";
            refreshBookList("NAME");
            sheet.dismiss();
        });
        view.findViewById(R.id.menu_delete).setOnClickListener(v -> {
            sheet.dismiss();
            startDeleteMode();   // 편집 모드 진입 (삭제 선택용)
        });

        sheet.show();
    }

    // ── 편집 모드 진입 ────────────────────────────────────────────────────
    private void startDeleteMode() {
        layoutDeleteMode.setVisibility(View.VISIBLE);
        layoutSelectAll.setVisibility(View.VISIBLE);
        dividerSelectAll.setVisibility(View.VISIBLE);
        fabAdd.setVisibility(View.GONE);
        tvDeleteCount.setText("0개 선택");
        ivCheckAllIcon.setImageResource(R.drawable.ic_check_off);
        if (adapter != null) adapter.setDeleteMode(true);
    }

    // ── 편집 모드 종료 ────────────────────────────────────────────────────
    private void exitDeleteMode() {
        layoutDeleteMode.setVisibility(View.GONE);
        layoutSelectAll.setVisibility(View.GONE);
        dividerSelectAll.setVisibility(View.GONE);
        fabAdd.setVisibility(View.VISIBLE);
        if (adapter != null) adapter.setDeleteMode(false);
    }

    // ── 삭제 실행 ─────────────────────────────────────────────────────────
    private void executeDelete() {
        if (adapter == null || adapter.getSelectedBooks().isEmpty()) {
            Toast.makeText(this, "삭제할 책을 선택해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = adapter.getSelectedBooks().size();
        new AlertDialog.Builder(this)
                .setTitle("삭제 확인")
                .setMessage(count + "권을 삭제하시겠습니까?")
                .setPositiveButton("삭제", (d, w) -> {
                    AppDatabase db = AppDatabase.getInstance(this);
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        for (Book book : adapter.getSelectedBooks())
                            db.libraryDao().deleteBook(book);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                            refreshBookList(currentSortType);
                            exitDeleteMode();
                        });
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ── 새 책 이름 입력 ──────────────────────────────────────────────────
    private void showNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("새로운 책 추가");
        builder.setMessage("저장할 책의 이름을 입력해 주세요.");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 64;
        params.rightMargin = 64;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("확인", (d, w) -> {
            String bookName = input.getText().toString().trim();
            if (bookName.isEmpty()) {
                Toast.makeText(this, "책 이름을 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, CameraActivity.class);
            intent.putExtra("BOOK_NAME", bookName);
            intent.putExtra("USER_ID", currentUserId);
            startActivity(intent);
        });
        builder.setNegativeButton("취소", null);
        builder.show();
    }

    // ── 책 목록 새로고침 ─────────────────────────────────────────────────
    private void refreshBookList(String sortType) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<Book> books = sortType.equals("NAME")
                    ? db.libraryDao().getAllBooksSortedByName(currentUserId)
                    : db.libraryDao().getAllBooksSortedByDate(currentUserId);

            runOnUiThread(() -> {
                adapter = new BookAdapter(books, count -> {
                    if (tvDeleteCount != null)
                        tvDeleteCount.setText(count + "개 선택");
                    // 전체 선택 아이콘 동기화
                    if (ivCheckAllIcon != null && adapter != null) {
                        ivCheckAllIcon.setImageResource(
                                adapter.isAllSelected()
                                        ? R.drawable.ic_check_on
                                        : R.drawable.ic_check_off);
                    }
                });
                // 즐겨찾기 변경 → DB 저장 후 목록 재정렬
                adapter.setFavoriteChangeListener(book -> {
                    ExecutorService dbExec = Executors.newSingleThreadExecutor();
                    dbExec.execute(() -> {
                        AppDatabase.getInstance(getApplicationContext())
                                .libraryDao().updateBook(book);
                        runOnUiThread(() -> refreshBookList(currentSortType));
                    });
                });
                rvLibrary.setAdapter(adapter);
            });
        });
    }

    // ── 책 이름 수정 다이얼로그 ──────────────────────────────────────────
    private void showRenameDialog() {
        // 어댑터에서 현재 책 목록 가져오기 (편집할 책 선택)
        if (adapter == null || adapter.getItemCount() == 0) {
            Toast.makeText(this, "수정할 책이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 책 목록을 선택지로 보여주기
        String[] titles = new String[adapter.getItemCount()];
        List<Book> books = adapter.getBookList();
        for (int i = 0; i < books.size(); i++) {
            titles[i] = books.get(i).title;
        }

        new AlertDialog.Builder(this)
                .setTitle("수정할 책 선택")
                .setItems(titles, (d, which) -> {
                    Book selectedBook = books.get(which);
                    showRenameInputDialog(selectedBook);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showRenameInputDialog(Book book) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("책 이름 수정");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(book.title);
        input.setSelection(book.title.length()); // 커서 끝으로

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 64;
        params.rightMargin = 64;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("확인", (d, w) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, "이름을 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newName.equals(book.title)) return; // 변경 없으면 패스

            book.title = newName;
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                AppDatabase.getInstance(this).libraryDao().updateBook(book);
                runOnUiThread(() -> {
                    Toast.makeText(this, "책 이름이 수정되었습니다.", Toast.LENGTH_SHORT).show();
                    refreshBookList(currentSortType);
                });
            });
        });
        builder.setNegativeButton("취소", null);
        builder.show();
    }
}