package com.example.hbook.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageViewHolder> {
    private List<String> pageList;
    private OnPageClickListener listener;

    private UserSetting userSetting;
    private int fontColor;

    private int highlightPage = -1;
    private int highlightStart = -1;
    private int highlightEnd = -1;

    private static final Map<String, Integer> HIGHLIGHT_BG_MAP = new HashMap<String, Integer>() {{
        put("#F5F5F5", 0xFFFFE066);  // 기본: 노랑
        put("#E0E0E0", 0xFFFF8C42);  // 그레이: 주황
        put("#424242", 0xFFA8E6CF);  // 다크그레이: 민트
        put("#000000", 0xFF00E5FF);  // 고대비(블랙): 시안
        put("#F6F1E5", 0xFFB5D5A8);  // 베이지: 연녹
        put("#233E3B", 0xFFFFD166);  // 다크그린: 황금
    }};

    private static final Map<String, Integer> HIGHLIGHT_FG_MAP = new HashMap<String, Integer>() {{
        put("#F5F5F5", 0xFF222222);  // 기본
        put("#E0E0E0", 0xFF111111);  // 그레이
        put("#424242", 0xFF1A1A1A);  // 다크그레이 (민트 위)
        put("#000000", 0xFF000000);  // 고대비 (시안 위)
        put("#F6F1E5", 0xFF2A1A0E);  // 베이지 (연녹 위)
        put("#233E3B", 0xFF1A1A1A);  // 다크그린 (황금 위)
    }};

    private static final int HIGHLIGHT_BG_DEFAULT  = 0xFFFFE066;
    private static final int HIGHLIGHT_FG_DEFAULT = 0xFF222222;

    private static class ThinHighlightSpan implements LineBackgroundSpan {
        private final int color;
        private final float textSizeSp;
        private final Context context;

        ThinHighlightSpan(int color, float textSizeSp, Context context) {
            this.color = color;
            this.textSizeSp = textSizeSp;
            this.context = context;
        }

        @Override
        public void drawBackground(Canvas c, Paint p,
                                   int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lineNum) {
            float textPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, textSizeSp,
                    context.getResources().getDisplayMetrics());

            float halfBar = textPx * 0.6f;   // 글자 크기의 120% / 2
            float barTop = baseline - textPx * 0.85f;
            float barBottom = baseline + textPx * 0.25f;

            // 텍스트가 있는 구간의 실제 너비만 하이라이트 (공백 제외, API 24 호환)
            String lineText = text.subSequence(start, end).toString();
            // 앞 공백 개수
            int leadingSpaces = 0;
            while (leadingSpaces < lineText.length()
                    && Character.isWhitespace(lineText.charAt(leadingSpaces))) leadingSpaces++;
            // 뒤 공백 개수
            int trailingSpaces = 0;
            int len = lineText.length();
            while (trailingSpaces < len - leadingSpaces
                    && Character.isWhitespace(lineText.charAt(len - 1 - trailingSpaces)))
                trailingSpaces++;

            float leadOffset = leadingSpaces > 0 ? p.measureText(lineText, 0, leadingSpaces) : 0;
            float trailOffset = trailingSpaces > 0 ? p.measureText(lineText, len - trailingSpaces, len) : 0;

            float drawLeft = left + leadOffset;
            float drawRight = right - trailOffset;

            if (drawLeft >= drawRight) return; // 공백만 있는 줄이면 skip

            Paint paint = new Paint();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(drawLeft, barTop, drawRight, barBottom, paint);
        }
    }

    public interface OnPageClickListener {
        void onPageClick();
    }

    public PageAdapter(List<String> pageList, UserSetting userSetting, int fontColor, OnPageClickListener listener) {
        this.pageList = pageList;
        this.userSetting = userSetting;
        this.fontColor = fontColor;
        this.listener = listener;
    }

    private int getHighlightBgColor() {
        String bg = userSetting != null ? userSetting.backgroundColor : "#F5F5F5";
        Integer c = HIGHLIGHT_BG_MAP.get(bg);
        return c != null ? c : HIGHLIGHT_BG_DEFAULT;
    }

    private int getHighlightFgColor() {
        String bg = userSetting != null ? userSetting.backgroundColor : "#F5F5F5";
        Integer c = HIGHLIGHT_FG_MAP.get(bg);
        return c != null ? c : HIGHLIGHT_FG_DEFAULT;
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
            if ("RIDIBATANG".equals(userSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.ridibatang);
            else if ("KOPUB_BATANG".equals(userSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.kopub_batang);
            else if ("NANUM_BARUN".equals(userSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.nanum_barun_gothic);
            else if ("NANUM_ROUND".equals(userSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.nanum_square_round);
            else if ("MARU".equals(userSetting.fontFamily))
                baseFace = ResourcesCompat.getFont(holder.itemView.getContext(), R.font.maruburi);
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
                    new ThinHighlightSpan(getHighlightBgColor(), userSetting.fontSize,
                            holder.itemView.getContext()),
                    highlightStart,
                    highlightEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            spannable.setSpan(
                    new ForegroundColorSpan(getHighlightFgColor()),
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

        if (prevPage >= 0 && prevPage != page) notifyItemChanged(prevPage);
        if (page >= 0) notifyItemChanged(page);
    }

    public void setHighlightByText(String chunkText, int currentViewerPage) {
        if (chunkText == null || chunkText.isEmpty() || pageList == null) return;

        // 공백을 정규화한 뒤 비교 (서버 split 과정에서 공백이 달라질 수 있음)
        String normalizedChunk = chunkText.trim();

        // 1순위: 현재 페이지에서 검색
        int startPage = Math.max(0, Math.min(currentViewerPage, pageList.size() - 1));
        int idx = pageList.get(startPage).indexOf(normalizedChunk);
        if (idx >= 0) {
            setHighlight(startPage, idx, idx + normalizedChunk.length());
            return;
        }

        // 2순위: 전체 페이지 순회
        for (int p = 0; p < pageList.size(); p++) {
            if (p == startPage) continue; // 이미 위에서 검색함
            idx = pageList.get(p).indexOf(normalizedChunk);
            if (idx >= 0) {
                setHighlight(p, idx, idx + normalizedChunk.length());
                return;
            }
        }

        // 찾지 못하면 하이라이트 유지 (이전 상태 그대로)
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
