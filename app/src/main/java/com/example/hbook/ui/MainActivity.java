package com.example.hbook.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hbook.R;
import com.example.hbook.data.AppDatabase;
import com.example.hbook.model.Book;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private RecyclerView rvLibrary;
    private BookAdapter adapter;
    private EditText etSearch;
    private TextView tvEdit;
    private LinearLayout layoutDeleteMode;
    private TextView tvDeleteCount;
    private FloatingActionButton fabAdd;
    private int currentUserId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getInt("logged_in_user_id", -1);

        if (currentUserId == -1) {
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        rvLibrary = findViewById(R.id.rv_library);
        rvLibrary.setLayoutManager(new GridLayoutManager(this, 3));

        etSearch = findViewById(R.id.et_search);
        tvEdit = findViewById(R.id.tv_edit);
        layoutDeleteMode = findViewById(R.id.layout_delete_mode);
        tvDeleteCount = findViewById(R.id.tv_delete_count);
        fabAdd = findViewById(R.id.fab_add);

        TextView tvCancelDelete = findViewById(R.id.tv_cancel_delete);
        TextView tvConfirmDelete = findViewById(R.id.tv_confirm_delete);

        tvEdit.setOnClickListener(v -> showEditMenu(v));
        tvCancelDelete.setOnClickListener(v -> exitDeleteMode());
        tvConfirmDelete.setOnClickListener(v -> executeDelete());

        ImageView ivProfile = findViewById(R.id.iv_profile);
        ivProfile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ProfileActivity.class);
            startActivity(intent);
        });

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showNameInputDialog());
        refreshBookList("LATEST");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentUserId != -1) {
            refreshBookList("LATEST");
        }
    }

    private void showNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("새로운 책 추가");
        builder.setMessage("저장할 책의 이름을 입력해 주세요.");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = 64;
        params.rightMargin = 64;
        input.setLayoutParams(params);

        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("확인", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String bookName = input.getText().toString();

                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                intent.putExtra("BOOK_NAME", bookName);
                intent.putExtra("USER_ID", currentUserId);
                startActivity(intent);
            }
        });

        builder.setNegativeButton("취소", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void showEditMenu(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
        MenuCompat.setGroupDividerEnabled(popup.getMenu(), true);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();

            if (id == R.id.menu_sort_latest) {
                refreshBookList("LATEST");
                return true;
            } else if (id == R.id.menu_sort_name) {
                refreshBookList("NAME");
                return true;
            } else if (id == R.id.menu_edit_delete) {
                startDeleteMode();
                return true;
            }
            return false;
        });
        popup.show();
    }

    // 삭제 눌렀을 때 실행
    private void startDeleteMode() {
        etSearch.setVisibility(View.GONE);
        tvEdit.setVisibility(View.GONE);

        layoutDeleteMode.setVisibility(View.VISIBLE);
        fabAdd.setVisibility(View.GONE);

        tvDeleteCount.setText("0개 선택");
        if (adapter != null) {
            adapter.setDeleteMode(true);
        }
    }

    // 삭제 취소 후 원래대로 돌아감
    private void exitDeleteMode() {
        etSearch.setVisibility(View.VISIBLE);
        tvEdit.setVisibility(View.VISIBLE);

        layoutDeleteMode.setVisibility(View.GONE);
        fabAdd.setVisibility(View.VISIBLE);

        if (adapter != null) {
            adapter.setDeleteMode(false);
        }
    }

    // DB에서 체크된 책 삭제
    private void executeDelete() {
        if (adapter == null || adapter.getSelectedBooks().isEmpty()) {
            Toast.makeText(this, "삭제할 책을 선택해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        AppDatabase db = AppDatabase.getInstance(this);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            for (Book book : adapter.getSelectedBooks()) {
                db.libraryDao().deleteBook(book);
            }
            runOnUiThread(() -> {
                Toast.makeText(this, "선택한 책이 삭제되었습니다.", Toast.LENGTH_SHORT).show();
                refreshBookList("LATEST");
                exitDeleteMode();
            });
        });
    }

    private void refreshBookList(String sortType) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<Book> books;

            if (sortType.equals("NAME")) {
                books = db.libraryDao().getAllBooksSortedByName(currentUserId);
            } else {
                books = db.libraryDao().getAllBooksSortedByDate(currentUserId);
            }

            runOnUiThread(() -> {
                adapter = new BookAdapter(books, count -> {
                    if (tvDeleteCount != null) {
                        tvDeleteCount.setText(count + "개 선택");
                    }
                });
                rvLibrary.setAdapter(adapter);
            });
        });
    }
}