package com.oriontv.plus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oriontv.plus.player.AdBlockDataSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DetailActivity extends Activity {

    public static final String EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_ID = "extra_id";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_YEAR = "extra_year";
    public static final String EXTRA_AUTOPLAY_INDEX = "extra_autoplay_index";
    public static final String EXTRA_PLAYTIME = "extra_playtime";

    private final Prefs prefs = App.get().prefs();
    private final Api api = App.get().api();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private final List<SourceEntry> entries = new ArrayList<>();
    private VideoItem current;
    private int selectedSource = -1;
    private int selectedEpisode = 0;
    private boolean favorite = false;
    /** 服务端收藏夹里与当前片名对应的键：同片名不同源也能识别为已收藏 */
    private final List<String> favKeys = new ArrayList<>();
    private boolean autoPlayDone = false;
    private boolean preferBest = false;

    private int autoIndex = -1;
    private int autoTime = 0;

    private SourceAdapter sourceAdapter;
    private EpisodeAdapter episodeAdapter;

    private ProgressBar loading;
    private TextView status;
    private TextView btnFavorite;
    private TextView btnPlay;
    private RecyclerView episodeRv;
    /** 手机竖屏布局里选集网格在 ScrollView 内（wrap_content），需要程序算出总高度；
     *  电视布局是 0dp + weight 自己滚动，不能改高度 */
    private boolean episodeGridAutoHeight = false;

    /* 小窗播放 */
    private static final int REQ_PLAY = 1001;
    private ExoPlayer mini;
    private PlayerView miniView;
    private FrameLayout miniWrap;
    private boolean miniActive = false;
    private int miniIndex = 0;
    /** 正在把播放器交给全屏 Activity，此时 onPause 不暂停播放 */
    private boolean launchingFullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        loading = findViewById(R.id.detailLoading);
        status = findViewById(R.id.detailStatus);
        btnFavorite = findViewById(R.id.btnFavorite);

        RecyclerView sourceList = findViewById(R.id.sourceList);
        sourceAdapter = new SourceAdapter(new SourceAdapter.Listener() {
            @Override
            public void onSource(SourceEntry entry, int position) {
                selectSource(position, true);
            }
        });
        sourceList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        sourceList.setAdapter(sourceAdapter);

        RecyclerView episodeList = findViewById(R.id.episodeList);
        episodeRv = episodeList;
        episodeGridAutoHeight = episodeList.getLayoutParams() != null
                && episodeList.getLayoutParams().height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        episodeAdapter = new EpisodeAdapter(new EpisodeAdapter.Listener() {
            @Override
            public void onEpisode(int index) {
                selectedEpisode = index;
                play(index);
            }
        });
        // 先给 4 列，真正列数由 setEpisodeSpans() 按宽度算，避免测量前 8 列挤在一起
        episodeList.setLayoutManager(new GridLayoutManager(this, 4));
        episodeList.setAdapter(episodeAdapter);
        applyEpisodeSpans();
        // 每次布局完成后都按真实宽度校正列数：全屏播放器转横屏再返回时，
        // onConfigurationChanged 拿到的还是横屏旧宽度，会把竖屏算成 8 列导致集数挤在一起
        episodeList.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        setEpisodeSpans();
                    }
                });

        btnPlay = findViewById(R.id.btnPlay);
        miniView = findViewById(R.id.miniPlayer);
        miniWrap = findViewById(R.id.miniWrap);

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (miniActive) {
                    if (mini == null) {
                        return;
                    }
                    if (mini.isPlaying()) {
                        mini.pause();
                        btnPlay.setText("▶ 播放");
                    } else {
                        mini.play();
                        btnPlay.setText("❚❚ 暂停");
                    }
                    return;
                }
                startMini(Math.max(0, selectedEpisode), 0);
            }
        });
        findViewById(R.id.btnFull).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current == null || current.episodes.isEmpty()) {
                    Ui.toast(DetailActivity.this, "没有可播放地址");
                    return;
                }
                int idx = Math.max(0, selectedEpisode);
                int pos = 0;
                boolean shared = false;
                if (miniActive && mini != null) {
                    idx = miniIndex;
                    pos = (int) (mini.getCurrentPosition() / 1000);
                    // 不暂停、不释放：把播放器交给全屏 Activity 无缝继续播放
                    miniView.setPlayer(null);
                    miniWrap.setVisibility(View.GONE);
                    shared = true;
                    // 等小窗的 Surface 真正释放后再进全屏，否则同一个播放器同时挂两个
                    // PlayerView 会出现画面卡住（看起来像自动停止播放）
                    final int fIdx = idx;
                    final int fPos = pos;
                    launchingFullscreen = true;
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            openFullscreen(fIdx, fPos, true);
                        }
                    }, 120);
                    return;
                }
                openFullscreen(idx, pos, shared);
            }
        });
        btnFavorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFavorite();
            }
        });
        findViewById(R.id.btnRetest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTests();
            }
        });
        // 等布局完成后把焦点放到「播放」上
        btnPlay.post(new Runnable() {
            @Override
            public void run() {
                btnPlay.requestFocus();
            }
        });

        autoIndex = getIntent().getIntExtra(EXTRA_AUTOPLAY_INDEX, -1);
        autoTime = getIntent().getIntExtra(EXTRA_PLAYTIME, 0);

        String source = getIntent().getStringExtra(EXTRA_SOURCE);
        String id = getIntent().getStringExtra(EXTRA_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String year = getIntent().getStringExtra(EXTRA_YEAR);

        ((TextView) findViewById(R.id.detailTitle)).setText(title == null ? "" : title);
        preferBest = (source == null || source.isEmpty());

        showLoading(true);
        if (source != null && !source.isEmpty() && id != null && !id.isEmpty()) {
            loadDetail(source, id);
        } else if (title != null && !title.isEmpty()) {
            searchSources(title, year == null ? "" : year);
        } else {
            showStatus("缺少影片信息");
        }
    }

    /** 选集网格列数自适应：电视 8 列，手机按宽度 3~8 列 */
    private void applyEpisodeSpans() {
        if (episodeRv == null) {
            return;
        }
        if (episodeRv.getWidth() > 0) {
            setEpisodeSpans();
            return;
        }
        episodeRv.post(new Runnable() {
            @Override
            public void run() {
                setEpisodeSpans();
            }
        });
    }

    private void setEpisodeSpans() {
        if (episodeRv == null || episodeRv.getWidth() <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int wDp = (int) (episodeRv.getWidth() / density);
        int cols = Math.max(3, Math.min(8, wDp / 82));
        RecyclerView.LayoutManager lm = episodeRv.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            GridLayoutManager glm = (GridLayoutManager) lm;
            if (glm.getSpanCount() != cols) {
                glm.setSpanCount(cols);
            }
        }
        updateEpisodeHeight(cols);
    }

    /** 选集网格在 ScrollView 内必须给足高度，否则只能滚到可视区域（31 集看不到底部） */
    private void updateEpisodeHeight(int cols) {
        if (episodeRv == null || !episodeGridAutoHeight) {
            return;
        }
        int count = episodeAdapter == null ? 0 : episodeAdapter.getItemCount();
        int rows = count <= 0 ? 0 : (count + cols - 1) / cols;
        int h = rows * Ui.dp(this, 52);
        android.view.ViewGroup.LayoutParams lp = episodeRv.getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            episodeRv.setLayoutParams(lp);
            episodeRv.requestLayout();
        }
    }

    private void updateEpisodeHeight() {
        if (episodeAdapter == null) {
            return;
        }
        if (episodeRv == null || episodeRv.getWidth() <= 0) {
            return;
        }
        int cols = 4;
        RecyclerView.LayoutManager lm = episodeRv.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            cols = ((GridLayoutManager) lm).getSpanCount();
        }
        updateEpisodeHeight(cols);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyEpisodeSpans();
    }

    private void showLoading(boolean show) {
        loading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            status.setVisibility(View.GONE);
        }
    }

    private void showStatus(String msg) {
        loading.setVisibility(View.GONE);
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
    }

    private void loadDetail(final String source, final String id) {
        api.detail(source, id, new Api.Cb<VideoItem>() {
            @Override
            public void done(VideoItem data, String err) {
                if (err != null || data == null || data.episodes.isEmpty()) {
                    String title = getIntent().getStringExtra(EXTRA_TITLE);
                    if (title != null && !title.isEmpty()) {
                        searchSources(title, getIntent().getStringExtra(EXTRA_YEAR));
                    } else {
                        showStatus(err == null ? "没有可播放地址" : err);
                    }
                    return;
                }
                String title = data.title;
                if (title == null || title.isEmpty()) {
                    title = getIntent().getStringExtra(EXTRA_TITLE);
                    data.title = title;
                }
                applyCurrent(data);
                searchSources(title, data.year);
            }
        });
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\u3000", "").toLowerCase(Locale.US);
    }

    /** 去掉【】标签和结尾的年份括号，作为重试时的查询词（网页标题常带这些后缀） */
    private static String cleanQuery(String t) {
        if (t == null) {
            return "";
        }
        String s = t.replaceAll("[【\\[][^】\\]]*[】\\]]", "").trim();
        s = s.replaceAll("[（(]\\s*\\d{4}\\s*[)）]", "").trim();
        return s.isEmpty() ? t : s;
    }

    private void searchSources(final String title, final String year) {
        searchSources(title, year, false);
    }

    /**
     * 按片名聚合播放源。服务端偶发超时或首轮源未返回时会出现“明明有源却提示未找到”，
     * 这里自动再查一次，仍然为空才提示用户。
     */
    private void searchSources(final String title, final String year, final boolean retried) {
        final String retryTitle = cleanQuery(title);
        api.search(title, new Api.Cb<List<VideoItem>>() {
            @Override
            public void done(List<VideoItem> data, String err) {
                showLoading(false);
                if (err != null) {
                    if (!retried) {
                        showLoading(true);
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && !isDestroyed()) {
                                    searchSources(retryTitle, year, true);
                                }
                            }
                        }, 1200);
                        return;
                    }
                    if (current == null) {
                        showStatus(err);
                    } else {
                        Ui.toast(DetailActivity.this, err);
                    }
                    return;
                }
                List<VideoItem> matches = new ArrayList<>();
                final String wantedYear = (year == null || year.isEmpty() || "unknown".equals(year)) ? "" : year;
                java.util.Set<String> usedSources = new java.util.HashSet<>();
                if (data != null) {
                    for (VideoItem v : data) {
                        if (!v.playable()) {
                            continue;
                        }
                        if (!norm(v.title).equals(norm(title))) {
                            continue;
                        }
                        if (!wantedYear.isEmpty() && v.year != null
                                && !v.year.isEmpty() && !v.year.equals("unknown")
                                && !v.year.equals(wantedYear)) {
                            continue;
                        }
                        if (!usedSources.add(v.source)) {
                            continue;
                        }
                        matches.add(v);
                    }
                    if (matches.isEmpty()) {
                        String q = norm(title);
                        for (VideoItem v : data) {
                            if (!v.playable()) {
                                continue;
                            }
                            String t = norm(v.title);
                            if (q.isEmpty() || (!t.contains(q) && !q.contains(t))) {
                                continue;
                            }
                            if (!usedSources.add(v.source)) {
                                continue;
                            }
                            matches.add(v);
                            if (matches.size() >= 6) {
                                break;
                            }
                        }
                    }
                }
                entries.clear();
                if (current != null) {
                    entries.add(new SourceEntry(current));
                    usedSources.add(current.source);
                }
                for (VideoItem v : matches) {
                    if (usedSources.contains(v.source) && current != null && v.source.equals(current.source)) {
                        continue;
                    }
                    entries.add(new SourceEntry(v));
                }
                if (entries.isEmpty()) {
                    if (!retried) {
                        showLoading(true);
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && !isDestroyed()) {
                                    searchSources(retryTitle, year, true);
                                }
                            }
                        }, 1200);
                        return;
                    }
                    showStatus("未找到可用播放源，请稍后重试");
                    return;
                }
                selectedSource = 0;
                entries.get(0).selected = true;
                if (current == null) {
                    current = entries.get(0).item;
                }
                renderSources();
                renderEpisodes();
                refreshMeta();
                loadFavoriteState();
                startTests();
                if (!preferBest) {
                    maybeAutoPlay();
                }
            }
        });
    }

    private void renderSources() {
        sourceAdapter.setItems(entries);
    }

    private void renderEpisodes() {
        List<String> labels = new ArrayList<>();
        if (current != null) {
            for (int i = 0; i < current.episodes.size(); i++) {
                labels.add(current.episodeLabel(i));
            }
        }
        selectedEpisode = 0;
        episodeAdapter.setItems(labels, 0);
        updateEpisodeHeight();
        TextView label = findViewById(R.id.episodeLabel);
        label.setText(current != null && current.episodes.size() > 1 ? "选集（" + current.episodes.size() + "）" : "正片");
    }

    private void refreshMeta() {
        if (current == null) {
            return;
        }
        TextView title = findViewById(R.id.detailTitle);
        if (current.title != null && !current.title.isEmpty()) {
            title.setText(current.title);
        }
        App.get().images().load(current.poster, (android.widget.ImageView) findViewById(R.id.detailPoster), 700);

        TextView chipYear = findViewById(R.id.chipYear);
        String yearText = current.year == null ? "" : current.year.trim();
        if (yearText.isEmpty() || "unknown".equals(yearText)) {
            chipYear.setVisibility(View.GONE);
        } else {
            chipYear.setText(yearText);
            chipYear.setVisibility(View.VISIBLE);
        }

        TextView chipType = findViewById(R.id.chipType);
        if (current.typeName == null || current.typeName.isEmpty()) {
            chipType.setVisibility(View.GONE);
        } else {
            chipType.setText(current.typeName);
            chipType.setVisibility(View.VISIBLE);
        }

        TextView chipRate = findViewById(R.id.chipRate);
        if (current.rate == null || current.rate.isEmpty()
                || "0".equals(current.rate) || "0.0".equals(current.rate)) {
            chipRate.setVisibility(View.GONE);
        } else {
            chipRate.setText("★ " + current.rate);
            chipRate.setVisibility(View.VISIBLE);
        }

        TextView meta = findViewById(R.id.detailMeta);
        meta.setText(current.episodes.size() > 1 ? "全 " + current.episodes.size() + " 集" : "单集正片");
        String name = current.sourceName == null || current.sourceName.isEmpty() ? current.source : current.sourceName;
        boolean testing = false;
        for (SourceEntry e : entries) {
            if (e.testing) {
                testing = true;
                break;
            }
        }
        ((TextView) findViewById(R.id.detailSub)).setText(
                name + (entries.size() > 1 ? (testing ? " · 正在匹配线路…" : " · 已测速 " + entries.size() + " 条线路") : ""));
        TextView desc = findViewById(R.id.detailDesc);
        desc.setText(current.desc == null ? "" : current.desc);
        desc.setVisibility(current.desc == null || current.desc.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void applyCurrent(VideoItem v) {
        current = v;
        if (v.title == null || v.title.isEmpty()) {
            v.title = getIntent().getStringExtra(EXTRA_TITLE);
        }
        refreshMeta();
        renderEpisodes();
    }

    private void startTests() {
        if (entries.isEmpty()) {
            return;
        }
        final AtomicInteger left = new AtomicInteger(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            final SourceEntry e = entries.get(i);
            e.testing = true;
            e.result = null;
            final int pos = i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final SourceTester.Result r = SourceTester.test(e.item.episodes.get(0));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            e.result = r;
                            e.testing = false;
                            sourceAdapter.notifyItemChanged(pos);
                            if (left.decrementAndGet() == 0) {
                                onAllTested();
                            }
                        }
                    });
                }
            });
        }
        sourceAdapter.notifyDataSetChanged();
    }

    private void onAllTested() {
        // 用户已经开始播放（小窗或全屏）后，不再自动切换到"最快线路"，
        // 否则会 stopMini 把正在播放的共享播放器停掉，全屏画面直接卡死且无法恢复
        if (preferBest && !autoPlayDone && !miniActive && !launchingFullscreen) {
            int best = -1;
            double bestScore = -1;
            for (int i = 0; i < entries.size(); i++) {
                SourceTester.Result r = entries.get(i).result;
                if (r != null && r.ok && r.score > bestScore) {
                    bestScore = r.score;
                    best = i;
                }
            }
            if (best >= 0 && best != selectedSource) {
                selectSource(best, false);
            }
        }
        ((TextView) findViewById(R.id.detailSub)).setText(
                (current.sourceName == null || current.sourceName.isEmpty() ? current.source : current.sourceName)
                        + " · 已测速 " + entries.size() + " 条线路");
        maybeAutoPlay();
    }

    private void selectSource(int index, boolean fromUser) {
        if (index < 0 || index >= entries.size()) {
            return;
        }
        if (fromUser) {
            preferBest = false;
        }
        if (launchingFullscreen) {
            // 播放器已交给全屏播放器在用，这里绝对不能 stop/clear，否则全屏会停住且无法恢复
            return;
        }
        if (miniActive) {
            stopMini();
        }
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).selected = i == index;
        }
        selectedSource = index;
        current = entries.get(index).item;
        sourceAdapter.notifyDataSetChanged();
        renderEpisodes();
        refreshMeta();
        loadFavoriteState();
        if (fromUser) {
            Ui.toast(this, "已切换到：" + (current.sourceName.isEmpty() ? current.source : current.sourceName));
        }
    }

    private void maybeAutoPlay() {
        if (autoPlayDone) {
            return;
        }
        if (autoIndex < 0) {
            return;
        }
        if (preferBest && selectedSource < 0) {
            return;
        }
        autoPlayDone = true;
        int idx = Math.min(autoIndex, Math.max(0, current.episodes.size() - 1));
        selectedEpisode = idx;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < current.episodes.size(); i++) {
            labels.add(current.episodeLabel(i));
        }
        episodeAdapter.setItems(labels, idx);
        updateEpisodeHeight();
        Ui.toast(this, "已定位到上次观看的第" + (idx + 1) + "集，按「播放」继续");
        findViewById(R.id.btnPlay).requestFocus();
    }

    private void play(int index) {
        if (current == null || current.episodes.isEmpty()) {
            Ui.toast(this, "没有可播放地址");
            return;
        }
        autoPlayDone = true;
        startMini(index, 0);
    }

    /** 在左侧画报区域小窗播放（优先复用共享播放器，避免重新缓冲） */
    private void startMini(int index, int pos) {
        if (current == null || current.episodes.isEmpty()) {
            Ui.toast(this, "没有可播放地址");
            return;
        }
        miniIndex = Math.max(0, Math.min(index, current.episodes.size() - 1));
        selectedEpisode = miniIndex;
        String targetUrl = current.episodes.get(miniIndex);

        ExoPlayer shared = PlayerHolder.get();
        if (shared != null && targetUrl.equals(PlayerHolder.getUrl())) {
            // 同一集：直接复用，无需重新缓冲
            mini = shared;
        } else {
            if (shared != null) {
                // 共享播放器存在但内容不同：复用实例、换源
                mini = shared;
            } else if (mini == null) {
                mini = new ExoPlayer.Builder(this).build();
                mini.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        btnPlay.setText(isPlaying ? "❚❚ 暂停" : "▶ 播放");
                    }
                });
            }
            MediaItem item = MediaItem.fromUri(targetUrl);
            HlsMediaSource src = new HlsMediaSource.Factory(dataSourceFactory()).createMediaSource(item);
            mini.setMediaSource(src);
            mini.prepare();
            PlayerHolder.set(mini, targetUrl);
        }
        miniView.setPlayer(mini);
        miniWrap.setVisibility(View.VISIBLE);
        miniActive = true;
        if (pos > 0) {
            mini.seekTo(pos * 1000L);
        }
        mini.play();
    }

    private void stopMini() {
        if (miniView != null) {
            miniView.setPlayer(null);
        }
        miniActive = false;
        miniWrap.setVisibility(View.GONE);
        btnPlay.setText("▶ 播放");
        if (launchingFullscreen) {
            // 播放器正在全屏方式下使用，不能 stop/clear
            return;
        }
        if (mini != null) {
            mini.stop();
            mini.clearMediaItems();
        }
    }

    private void openFullscreen(int index, int pos, boolean shared) {
        Intent i = buildPlayerIntent(index);
        i.putExtra(PlayerActivity.EXTRA_POS, pos);
        i.putExtra(PlayerActivity.EXTRA_SHARED, shared);
        launchingFullscreen = shared;
        startActivityForResult(i, REQ_PLAY);
    }

    private DataSource.Factory dataSourceFactory() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android TV) OrionTVPlus")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);
        return new AdBlockDataSource.Factory(http, prefs.adBlock());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        launchingFullscreen = false;
        if (requestCode == REQ_PLAY && data != null) {
            int idx = data.getIntExtra(PlayerActivity.EXTRA_INDEX, selectedEpisode);
            int pos = data.getIntExtra(PlayerActivity.EXTRA_POS, 0);
            // 全屏返回：直接把共享播放器挂回小窗，不重新缓冲
            if (PlayerHolder.get() != null) {
                mini = PlayerHolder.get();
                miniView.setPlayer(mini);
                miniWrap.setVisibility(View.VISIBLE);
                miniActive = true;
                miniIndex = idx;
                selectedEpisode = idx;
                mini.play();
                btnPlay.setText(mini.isPlaying() ? "❚❚ 暂停" : "▶ 播放");
                // 刷新选集高亮
                List<String> labels = new ArrayList<>();
                if (current != null) {
                    for (int i = 0; i < current.episodes.size(); i++) {
                        labels.add(current.episodeLabel(i));
                    }
                }
                episodeAdapter.setItems(labels, idx);
                updateEpisodeHeight();
            } else {
                startMini(idx, pos);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchingFullscreen = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (launchingFullscreen) {
            // 正在交给全屏播放，不要暂停
            return;
        }
        if (mini != null && mini.isPlaying()) {
            mini.pause();
        }
    }

    private Intent buildPlayerIntent(int index) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_TITLE, current.title);
        i.putExtra(PlayerActivity.EXTRA_YEAR, current.year);
        i.putExtra(PlayerActivity.EXTRA_SOURCE, current.source);
        i.putExtra(PlayerActivity.EXTRA_ID, current.id);
        i.putExtra(PlayerActivity.EXTRA_SOURCE_NAME, current.sourceName);
        i.putExtra(PlayerActivity.EXTRA_EPISODES, new JSONArray(current.episodes).toString());
        i.putExtra(PlayerActivity.EXTRA_EP_TITLES, new JSONArray(current.episodeTitles).toString());
        i.putExtra(PlayerActivity.EXTRA_INDEX, index);
        i.putExtra(PlayerActivity.EXTRA_SOURCES, sourcesJson());
        return i;
    }

    private String sourcesJson() {
        JSONArray arr = new JSONArray();
        for (SourceEntry e : entries) {
            arr.put(e.item.toJson());
        }
        return arr.toString();
    }

    private void loadFavoriteState() {
        if (current == null) {
            return;
        }
        final String key = current.key();
        final String title = current.title;
        final String year = current.year;
        api.favorites(new Api.Cb<JSONObject>() {
            @Override
            public void done(JSONObject data, String err) {
                if (current == null || !key.equals(current.key())) {
                    return;
                }
                if (err != null || data == null) {
                    // 拉取失败时保持原状态，避免把「已收藏」误显示成未收藏
                    return;
                }
                List<String> keys = new ArrayList<>();
                java.util.Iterator<String> it = data.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject f = data.optJSONObject(k);
                    if (f == null) {
                        continue;
                    }
                    // 同一部片在服务端可能以别的源/别的 id 收藏过（网页端就是这样），
                    // 只比对 key 会出现「详情页显示未收藏」的假不同步，这里按片名兜底。
                    if (k.equals(key) || sameTitle(f.optString("title", ""), f.optString("year", ""),
                            title, year)) {
                        if (!keys.contains(k)) {
                            keys.add(k);
                        }
                    }
                }
                favKeys.clear();
                favKeys.addAll(keys);
                favorite = !favKeys.isEmpty();
                updateFavoriteButton();
            }
        });
    }

    /** 片名归一化后比较，年份都有值时要求一致（避免同名不同年份误判） */
    private static boolean sameTitle(String a, String aYear, String b, String bYear) {
        String na = normTitle(a);
        if (na.isEmpty() || !na.equals(normTitle(b))) {
            return false;
        }
        return aYear == null || bYear == null || aYear.isEmpty() || bYear.isEmpty()
                || aYear.equals(bYear);
    }

    private static String normTitle(String t) {
        if (t == null) {
            return "";
        }
        return t.replaceAll("[\\s·:：!！?？,，。.、\\-_—()（）\\[\\]【】]", "").toLowerCase(Locale.US);
    }

    private void updateFavoriteButton() {
        btnFavorite.setText(favorite ? "♥ 已收藏" : "♡ 收藏");
    }

    private void toggleFavorite() {
        if (current == null) {
            return;
        }
        String key = current.key();
        if (favorite) {
            List<String> keys = new ArrayList<>(favKeys);
            if (keys.isEmpty()) {
                keys.add(key);
            }
            for (String k : keys) {
                api.removeFavorite(k);
            }
            favKeys.clear();
            favorite = false;
            updateFavoriteButton();
            Ui.toast(this, "已取消收藏");
            return;
        }
        JSONObject fav = new JSONObject();
        try {
            fav.put("title", current.title);
            fav.put("source_name", current.sourceName);
            fav.put("year", current.year);
            fav.put("cover", current.poster);
            fav.put("total_episodes", current.episodes.size());
            fav.put("save_time", System.currentTimeMillis());
            fav.put("search_title", current.title);
        } catch (Exception ignored) {
        }
        api.addFavorite(key, fav);
        favKeys.clear();
        favKeys.add(key);
        favorite = true;
        updateFavoriteButton();
        Ui.toast(this, "已收藏");
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        // 若播放器已交给全屏 Activity，此处不释放，由全屏返回后再由本页释放
        if (!launchingFullscreen && mini != null) {
            mini.release();
            mini = null;
            PlayerHolder.clear();
        }
        super.onDestroy();
    }
}
