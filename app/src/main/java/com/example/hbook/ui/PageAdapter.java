package com.example.hbook.ui;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hbook.R;
import com.example.hbook.model.Page;

import java.util.List;

public class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageViewHolder> {
    private List<String> pageList;
    private OnPageClickListener listener;

    private float fontSize;
    private float lineSpacing;
    private int fontColor;

    private int highlightPage = -1;
    private int highlightStart = -1;
    private int highlightEnd = -1;

    private static final int HIGHLIGHT_COLOR = 0xFFFFE066;

    public interface OnPageClickListener {
        void onPageClick();
    }

    public PageAdapter(List<String> pageList, float fontSize, float lineSpacing, int fontColor, OnPageClickListener listener) {
        this.pageList = pageList;
        this.fontSize = fontSize;
        this.lineSpacing = lineSpacing;
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

        holder.tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        holder.tvContent.setTextColor(fontColor);
        holder.tvContent.setLineSpacing(0, lineSpacing);

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
