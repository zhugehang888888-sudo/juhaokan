package com.oriontv.plus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH> {

    public interface Listener {
        void onEpisode(int index);
    }

    private final List<String> items = new ArrayList<>();
    private final Listener listener;
    private int selected = 0;

    public EpisodeAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<String> labels, int selected) {
        items.clear();
        if (labels != null) {
            items.addAll(labels);
        }
        this.selected = selected;
        notifyDataSetChanged();
    }

    public String labelOf(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : "";
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, final int position) {
        String label = items.get(position);
        h.text.setText(label.length() > 6 ? label.substring(0, 6) : label);
        h.text.setTextColor(position == selected ? 0xFF22C55E : 0xFFF2F6F9);
        h.text.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selected = position;
                notifyDataSetChanged();
                if (listener != null) {
                    listener.onEpisode(position);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;

        VH(View v) {
            super(v);
            text = (TextView) v;
        }
    }
}
