package com.oriontv.plus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LiveGroupAdapter extends RecyclerView.Adapter<LiveGroupAdapter.VH> {

    public interface Listener {
        void onGroup(String group);
    }

    private final List<String> items = new ArrayList<>();
    private final Listener listener;
    private String selected = "";

    public LiveGroupAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<String> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setSelected(String group) {
        selected = group == null ? "" : group;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_group, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final String group = items.get(position);
        h.name.setText(group);
        h.name.setTextColor(group.equals(selected) ? 0xFF22C55E : 0xFF9DAAB6);
        h.name.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onGroup(group);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;

        VH(View v) {
            super(v);
            name = (TextView) v;
        }
    }
}
