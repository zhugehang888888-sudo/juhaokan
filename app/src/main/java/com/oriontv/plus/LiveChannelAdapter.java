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

public class LiveChannelAdapter extends RecyclerView.Adapter<LiveChannelAdapter.VH> {

    public interface Listener {
        void onPlay(LiveChannel channel);
    }

    private final List<LiveChannel> items = new ArrayList<>();
    private final Listener listener;
    private int playingNumber = -1;

    public LiveChannelAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<LiveChannel> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setPlaying(int number) {
        playingNumber = number;
        notifyDataSetChanged();
    }

    public List<LiveChannel> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_channel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final VH h, int position) {
        final LiveChannel c = items.get(position);
        h.num.setText(String.valueOf(c.number));
        h.name.setText(c.name);
        h.name.setTextColor(c.number == playingNumber
                ? 0xFF22C55E : 0xFFF2F6F9);
        if (c.logo == null || c.logo.isEmpty()) {
            h.logo.setVisibility(View.GONE);
        } else {
            h.logo.setVisibility(View.VISIBLE);
            App.get().images().load(c.logo, h.logo, 120);
        }
        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPlay(c);
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
        final TextView num;
        final TextView name;
        final ImageView logo;

        VH(View v) {
            super(v);
            root = v.findViewById(R.id.liveChannelRoot);
            num = v.findViewById(R.id.liveChannelNum);
            name = v.findViewById(R.id.liveChannelName);
            logo = v.findViewById(R.id.liveChannelLogo);
        }
    }
}
