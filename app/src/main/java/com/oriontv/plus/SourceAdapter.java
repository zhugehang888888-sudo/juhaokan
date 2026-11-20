package com.oriontv.plus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.VH> {

    public interface Listener {
        void onSource(SourceEntry entry, int position);
    }

    private final List<SourceEntry> items = new ArrayList<>();
    private final Listener listener;

    public SourceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<SourceEntry> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_source, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, final int position) {
        final SourceEntry e = items.get(position);
        String name = e.item.sourceName == null || e.item.sourceName.isEmpty()
                ? e.item.source : e.item.sourceName;
        h.name.setText(name);
        h.name.setTextColor(e.selected ? 0xFF22C55E : 0xFFF2F6F9);

        if (e.selected) {
            h.name.setText("✓ " + name);
        }

        if (e.result == null) {
            h.quality.setText("—");
            h.speed.setText(e.testing ? "测速中…" : "未测速");
            h.speed.setTextColor(0xFF9DAAB6);
            h.ping.setText("");
        } else if (!e.result.ok) {
            h.quality.setText("—");
            h.speed.setText("不可用");
            h.speed.setTextColor(0xFFEF4444);
            h.ping.setText(e.result.error == null ? "" : e.result.error);
        } else {
            h.quality.setText(e.result.quality);
            h.speed.setText(e.result.speedText);
            h.speed.setTextColor(0xFF22C55E);
            h.ping.setText(e.result.pingMs + "ms");
        }

        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onSource(e, h.getAdapterPosition());
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView name;
        final TextView quality;
        final TextView speed;
        final TextView ping;

        VH(View v) {
            super(v);
            root = v;
            name = v.findViewById(R.id.sourceName);
            quality = v.findViewById(R.id.sourceQuality);
            speed = v.findViewById(R.id.sourceSpeed);
            ping = v.findViewById(R.id.sourcePing);
        }
    }
}
