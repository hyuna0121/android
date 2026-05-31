package com.example.hbook.ui;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hbook.R;
import com.example.hbook.model.Page;
import com.example.hbook.model.UserSetting;

import java.util.List;

public class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageViewHolder> {
    private List<String> pageList;
    private OnPageClickListener listener;

    private UserSetting userSetting;
    private int fontColor;

    private int highlightPage = -1;
    private int highlightStart = -1;
    private int highlightEnd = -1;

    private static final int HIGHLIGHT_COLOR = 0xFFFFE066;

    public interface OnPageClickListener {
        void onPageClick();
    }

    public PageAdapter(List<String> pageList, UserSetting userSetting, int fontColor, OnPageClickListener listener) {
        this.pageList = pageList;
        this.userSetting = userSetting;
        this.fontColor = fontColor;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        String text = pageList.get(position);

        holder.tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, userSetting.fontSize);
        holder.tvContent.setTextColor(fontColor);
        holder.tvContent.setLineSpacing(0, userSetting.lineSpacing);
        holder.tvContent.setLetterSpacing(userSetting.letterSpacing);

        Typeface baseFace = Typeface.DEFAULT;
        try {
            if ("RIDIBATANG".equals(userSetting.fontFamily)) baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.ridibatang);
            else if ("KOPUB_BATANG".equals(userSetting.fontFamily)) baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.kopub_batang);
            else if ("NANUM_BARUN".equals(userSetting.fontFamily)) baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.nanum_barun_gothic);
            else if ("NANUM_ROUND".equals(userSetting.fontFamily)) baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.nanum_square_round);
            else if ("MARU".equals(userSetting.fontFamily)) baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.maruburi);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (userSetting.isBold) {
            holder.tvContent.setTypeface(baseFace, Typeface.BOLD);
        } else {
            holder.tvContent.setTypeface(baseFace, Typeface.NORMAL);
        }

        holder.tvContent.setOnClickListener(v -> {
            if (listener != null) listener.onPageClick();
        });

        if (position == highlightPage && highlightStart >= 0 && highlightEnd > highlightStart && highlightEnd <= text.length()) {
            SpannableString spannable = new SpannableString(text);
            spannable.setSpan(
                    new BackgroundColorSpan(HIGHLIGHT_COLOR),
                    highlightStart,
                    highlightEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            holder.tvContent.setText(spannable);
        } else {
            holder.tvContent.setText(text);
        }
    }

    @Override
    public int getItemCount() {
        return pageList != null ? pageList.size() : 0;
    }

    public void setHighlight(int page, int start, int end) {
        int prevPage = this.highlightPage;

        highlightPage = page;
        highlightStart = start;
        highlightEnd = end;

        if (prevPage >=0 && prevPage != page) notifyItemChanged(prevPage);
        if (page >= 0) notifyItemChanged(page);
    }

    public void setHighlightByText(String chunkText, int currentViewerPage) {
        if (chunkText == null || chunkText.isEmpty() || pageList == null) return;

        // 공백을 정규화한 뒤 비교 (서버 split 과정에서 공백이 달라질 수 있음)
        String normalizedChunk = chunkText.trim().replaceAll("\\s+", " ");

        // 1순위: 현재 페이지에서 검색
        int startPage = Math.max(0, Math.min(currentViewerPage, pageList.size() - 1));
        String normalizedPage = pageList.get(startPage).replaceAll("\\s+", " ");
        int idx = normalizedPage.indexOf(normalizedChunk);
        if (idx >= 0) {
            setHighlight(startPage, idx, idx + normalizedChunk.length());
            return;
        }

        // 2순위: 전체 페이지 순회
        for (int p = 0; p < pageList.size(); p++) {
            if (p == startPage) continue; // 이미 위에서 검색함
            normalizedPage = pageList.get(p).replaceAll("\\s+", " ");
            idx = normalizedPage.indexOf(normalizedChunk);
            if (idx >= 0) {
                setHighlight(p, idx, idx + normalizedChunk.length());
                return;
            }
        }

        // 3순위: 앞 10글자로 부분 매칭 시도 (청크가 페이지 경계에 걸친 경우)
        String prefix = normalizedChunk.length() > 10
                ? normalizedChunk.substring(0, 10) : normalizedChunk;
        for (int p = 0; p < pageList.size(); p++) {
            normalizedPage = pageList.get(p).replaceAll("\\s+", " ");
            idx = normalizedPage.indexOf(prefix);
            if (idx >= 0) {
                int end = Math.min(idx + normalizedChunk.length(), pageList.get(p).length());
                setHighlight(p, idx, end);
                return;
            }
        }

        Log.w("PageAdapter", "하이라이트 매칭 실패: [" + normalizedChunk + "]");
    }

    public void clearHighlight() {
        int prevPage = highlightPage;

        highlightPage = -1;
        highlightStart = -1;
        highlightEnd = -1;

        if (prevPage >= 0) notifyItemChanged(prevPage);
    }

    public String getPageText(int position) {
        if (pageList != null && position < pageList.size()) {
            return pageList.get(position);
        }

        return "";
    }

    public int getPageCount() {
        return pageList != null ? pageList.size() : 0;
    }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        TextView tvContent;

        public PageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_page_content);
        }
    }
}
