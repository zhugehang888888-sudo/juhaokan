package com.oriontv.plus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.VH> {

    public interface Listener {
        void onCard(CardItem item, int position);

        /** 收藏批量管理：勾选数量变化 */
        default void onCheckedChanged(int count) {
        }
    }

    private final List<CardItem> items = new ArrayList<>();
    private final Listener listener;
    private boolean selectionMode = false;

    public PosterAdapter(Listener listener) {
        this.listener = listener;
    }

    /** 进入/退出勾选模式；退出时清空所有勾选 */
    public void setSelectionMode(boolean on) {
        if (selectionMode == on) {
            return;
        }
        selectionMode = on;
        if (!on) {
            for (CardItem c : items) {
                c.checked = false;
            }
        }
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int checkedCount() {
        int n = 0;
        for (CardItem c : items) {
            if (c.checked) {
                n++;
            }
        }
        return n;
    }

    public List<CardItem> checkedItems() {
        List<CardItem> out = new ArrayList<>();
        for (CardItem c : items) {
            if (c.checked) {
                out.add(c);
            }
        }
        return out;
    }

    public void setItems(List<CardItem> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void addItems(List<CardItem> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int from = items.size();
        items.addAll(list);
        notifyItemRangeInserted(from, list.size());
    }

    public List<CardItem> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final CardItem item = items.get(position);
        h.title.setText(item.title);
        if (item.badge != null && !item.badge.isEmpty()) {
            h.badge.setText(item.badge);
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }
        if (item.sub != null && !item.sub.isEmpty()) {
            h.sub.setText(item.sub);
            h.sub.setVisibility(View.VISIBLE);
        } else {
            h.sub.setVisibility(View.GONE);
        }
        if (item.poster == null || item.poster.isEmpty() || item.poster.contains("bgm.tv")) {
            h.poster.setImageDrawable(null);
            h.fallback.setText(item.title);
            h.fallback.setVisibility(View.VISIBLE);
        } else {
            h.fallback.setVisibility(View.GONE);
            App.get().images().load(item.poster, h.poster, 420);
        }
        h.root.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? 1.05f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                v.setZ(hasFocus ? 20f : 0f);
            }
        });
        h.root.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                    v.requestFocusFromTouch();
                }
                return false;
            }
        });
        h.mark.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.mark.setSelected(item.checked);
        h.mark.setText(item.checked ? "✓" : "");
        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener == null) {
                    return;
                }
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    return;
                }
                if (selectionMode) {
                    CardItem it = items.get(pos);
                    it.checked = !it.checked;
                    notifyItemChanged(pos);
                    listener.onCheckedChanged(checkedCount());
                    return;
                }
                listener.onCard(item, pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final ImageView poster;
        final TextView title;
        final TextView sub;
        final TextView badge;
        final TextView fallback;
        final TextView mark;

        VH(View v) {
            super(v);
            root = v.findViewById(R.id.cardRoot);
            poster = v.findViewById(R.id.poster);
            title = v.findViewById(R.id.posterTitle);
            sub = v.findViewById(R.id.posterSub);
            badge = v.findViewById(R.id.badge);
            fallback = v.findViewById(R.id.posterFallback);
            mark = v.findViewById(R.id.selectMark);
        }
    }
}
