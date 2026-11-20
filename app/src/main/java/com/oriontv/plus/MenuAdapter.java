package com.oriontv.plus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MenuAdapter extends RecyclerView.Adapter<MenuAdapter.VH> {

    public interface Listener {
        void onMenu(MenuItem item);
    }

    private final List<MenuItem> items = new ArrayList<>();
    private final Listener listener;
    private static final Object PAYLOAD_VALUE = new Object();

    public MenuAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<MenuItem> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    /** 只刷新某一项的右侧值，不重建条目，避免刷新时焦点丢失导致遥控器失效 */
    public void updateValue(int position, String value) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        items.get(position).value = value;
        notifyItemChanged(position, PAYLOAD_VALUE);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_menu, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_VALUE)) {
            bindValue(h, items.get(position));
            return;
        }
        super.onBindViewHolder(h, position, payloads);
    }

    private void bindValue(VH h, MenuItem item) {
        if (item.value == null || item.value.isEmpty()) {
            h.value.setVisibility(View.GONE);
        } else {
            h.value.setVisibility(View.VISIBLE);
            h.value.setText(item.value);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, final int position) {
        final MenuItem item = items.get(position);
        h.label.setText(item.label);
        bindValue(h, item);
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onMenu(item);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView value;

        VH(View v) {
            super(v);
            label = v.findViewById(R.id.menuLabel);
            value = v.findViewById(R.id.menuValue);
        }
    }
}
