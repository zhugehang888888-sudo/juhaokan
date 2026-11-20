package com.oriontv.plus;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.oriontv.plus.player.AdBlockDataSource;
import com.oriontv.plus.player.TvSeekBar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {

    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_YEAR = "extra_year";
    public static final String EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_ID = "extra_id";
    public static final String EXTRA_SOURCE_NAME = "extra_source_name";
    public static final String EXTRA_EPISODES = "extra_episodes";
    public static final String EXTRA_EP_TITLES = "extra_ep_titles";
    public static final String EXTRA_INDEX = "extra_index";
    public static final String EXTRA_POS = "extra_pos";
    public static final String EXTRA_SOURCES = "extra_sources";
    public static final String EXTRA_SHARED = "extra_shared";

    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f};

    private final Prefs prefs = App.get().prefs();
    private final Api api = App.get().api();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout controls;
    private FrameLayout menuLayer;
    private TvSeekBar seek;
    private TextView titleView;
    private TextView subView;
    private TextView timePos;
    private TextView timeDur;
    private TextView netSpeed;
    private TextView clockText;
    private TextView centerHint;
    private TextView menuTitle;
    private ProgressBar buffering;
    private LinearLayout btnRow;
    private TextView btnTip;
    /** 底部功能按钮的边长与间距（按屏幕宽度自适应） */
    private int btnSize;
    private int btnGap;
    private ImageView btnRewind;
    private ImageView btnPlay;
    private ImageView btnForward;
    private ImageView btnEpisodes;
    private ImageView btnSource;
    private ImageView btnSkip;
    private ImageView btnRatio;
    private ImageView btnFull;
    private ImageView btnMore;
    private TextView btnSpeed;
    private TextView btnAd;
    private androidx.recyclerview.widget.RecyclerView menuList;

    private MenuAdapter menuAdapter;
    private final List<MenuItem> rootMenu = new ArrayList<>();
    private List<MenuItem> sourceMenuItems = new ArrayList<>();
    /** 手指/鼠标正在拖动进度条时不覆盖进度显示 */
    private boolean seeking = false;

    private final List<String> urls = new ArrayList<>();
    private final List<String> epTitles = new ArrayList<>();
    private final List<VideoItem> sources = new ArrayList<>();

    private String mediaTitle = "";
    private String mediaYear = "";
    private String mediaPoster = "";
    private String sourceId = "";
    private String sourceKey = "";
    private String sourceName = "";
    private String searchTitle = "";

    private int index = 0;
    private int startPos = 0;
    private boolean useShared = false;
    private boolean usingShared = false;
    private boolean firstPlayback = true;

    private float speed = 1f;
    private boolean adBlock = true;
    private boolean autoNext = true;
    private boolean skipOn = true;
    private boolean skipCfg = false;
    private int introSec = 0;
    private int outroSec = 0;
    private int resizeMode = 0;

    private boolean controlsVisible = false;
    private boolean prepared = false;
    private boolean introApplied = false;
    private boolean ended = false;
    private long lastBytes = 0;
    private int tick = 0;

    private float touchDownX, touchDownY;
    private long touchDownTime;
    /** 触屏左右滑动快进快退 */
    private boolean swipeSeeking = false;
    private boolean swipeAllow = false;
    private long swipeStartPos = 0;
    private long swipeTarget = 0;
    private long swipeSpanMs = 90000L;
    /**
     * 触摸模式下点中"有焦点能力"的控件时，系统会把第一次点击吞掉（只移焦点），
     * 表现为"点一下没反应，要点两下"。这里统计一次手势内是否真的产生了点击，没有就补发一次。
     */
    private int clickSeq = 0;
    private int clickSeqAtDown = 0;
    /** 按下时功能栏/菜单是否已经显示：只有已显示才补发点击，避免"点一下呼出控制栏"被误当成点按钮 */
    private boolean tapTargetReady = false;

    private final Runnable longPressShowControls = new Runnable() {
        @Override
        public void run() {
            if (!menuOpen() && !controlsVisible) {
                setControlsVisible(true);
            }
        }
    };

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            if (controlsVisible && !menuOpen()) {
                setControlsVisible(false);
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateUi();
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        adBlock = prefs.adBlock();
        autoNext = prefs.autoNext();
        speed = prefs.speed();
        skipOn = prefs.skipEnabled();
        resizeMode = prefs.resizeMode();

        playIntent(getIntent());
        initViews();
        initPlayer();
        loadSkipConfig();
        lastBytes = AdBlockDataSource.BYTES.get();
        startPlayback(index, startPos);
        handler.post(ticker);
    }

    private void playIntent(Intent in) {
        mediaTitle = in.getStringExtra(EXTRA_TITLE);
        mediaYear = in.getStringExtra(EXTRA_YEAR);
        sourceName = in.getStringExtra(EXTRA_SOURCE_NAME);
        searchTitle = mediaTitle == null ? "" : mediaTitle;
        if (mediaTitle == null) {
            mediaTitle = "";
        }
        if (sourceName == null) {
            sourceName = "";
        }
        String eps = in.getStringExtra(EXTRA_EPISODES);
        try {
            JSONArray arr = new JSONArray(eps == null ? "[]" : eps);
            for (int i = 0; i < arr.length(); i++) {
                urls.add(arr.optString(i, ""));
            }
            JSONArray t = new JSONArray(in.getStringExtra(EXTRA_EP_TITLES) == null
                    ? "[]" : in.getStringExtra(EXTRA_EP_TITLES));
            for (int i = 0; i < t.length(); i++) {
                epTitles.add(t.optString(i, ""));
            }
        } catch (Exception ignored) {
        }
        String src = in.getStringExtra(EXTRA_SOURCE);
        String id = in.getStringExtra(EXTRA_ID);
        sourceId = id == null ? "" : id;
        sourceKey = (src == null ? "" : src) + "+" + sourceId;
        index = Math.max(0, Math.min(in.getIntExtra(EXTRA_INDEX, 0), Math.max(0, urls.size() - 1)));
        startPos = Math.max(0, in.getIntExtra(EXTRA_POS, 0));
        useShared = in.getBooleanExtra(EXTRA_SHARED, false);

        try {
            JSONArray arr = new JSONArray(in.getStringExtra(EXTRA_SOURCES) == null ? "[]" : in.getStringExtra(EXTRA_SOURCES));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    sources.add(VideoItem.from(o));
                }
            }
        } catch (Exception ignored) {
        }
        if (sources.isEmpty() && !urls.isEmpty()) {
            VideoItem v = new VideoItem();
            v.title = mediaTitle;
            v.source = src == null ? "" : src;
            v.id = sourceId;
            v.sourceName = sourceName;
            v.year = mediaYear == null ? "" : mediaYear;
            v.episodes.addAll(urls);
            sources.add(v);
        }
        for (VideoItem v : sources) {
            if (v.source.equals(src) && v.id.equals(sourceId)) {
                mediaPoster = v.poster;
                break;
            }
        }
    }

    private void initViews() {
        playerView = findViewById(R.id.player);
        controls = findViewById(R.id.controls);
        menuLayer = findViewById(R.id.menuLayer);
        seek = findViewById(R.id.seek);
        titleView = findViewById(R.id.playerTitle);
        subView = findViewById(R.id.playerSub);
        timePos = findViewById(R.id.timePos);
        timeDur = findViewById(R.id.timeDur);
        netSpeed = findViewById(R.id.netSpeed);
        clockText = findViewById(R.id.clockText);
        clockText.setVisibility(prefs.clockEnabled() ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnCast).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                castCurrent();
            }
        });
        updateCastLabel();
        centerHint = findViewById(R.id.centerHint);
        menuTitle = findViewById(R.id.menuTitle);
        buffering = findViewById(R.id.buffering);
        btnRow = findViewById(R.id.btnRow);
        btnTip = findViewById(R.id.btnTip);
        menuList = findViewById(R.id.menuList);

        menuAdapter = new MenuAdapter(new MenuAdapter.Listener() {
            @Override
            public void onMenu(MenuItem item) {
                clickSeq++;
                if (item.action != null) {
                    item.action.run();
                }
            }
        });
        menuList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        menuList.setItemAnimator(null);
        menuList.setAdapter(menuAdapter);

        seek.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickSeq++;
                togglePlay();
            }
        });
        seek.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && controlsVisible) {
                    handler.removeCallbacks(hideControls);
                    handler.postDelayed(hideControls, 6000);
                }
            }
        });
        // 触屏 / 鼠标拖动进度条
        seek.setOnSeekListener(new TvSeekBar.OnSeekListener() {
            @Override
            public void onSeekStart() {
                seeking = true;
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onSeeking(float fraction) {
                long dur = player == null ? 0 : player.getDuration();
                if (dur > 0) {
                    timePos.setText(Ui.time((long) (dur * fraction)));
                }
            }

            @Override
            public void onSeekEnd(float fraction) {
                seeking = false;
                long dur = player == null ? 0 : player.getDuration();
                if (player != null && dur > 0) {
                    long pos = (long) (dur * fraction);
                    player.seekTo(pos);
                    hint(Ui.time(pos) + " / " + Ui.time(dur));
                }
                keepControls();
            }
        });

        // 触屏：菜单打开时，点击右侧面板以外的空白区域直接退出菜单
        View menuPanel = findViewById(R.id.menuPanel);
        if (menuPanel != null) {
            menuPanel.setClickable(true);
        }
        menuLayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMenu();
            }
        });

        // 触屏：功能栏显示时，点击空白处（非按钮）收起功能栏。
        // 呼出功能栏由 dispatchTouchEvent 统一处理（更可靠，不依赖 PlayerView 内部触摸分发）。
        controls.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (menuOpen()) {
                    return;
                }
                setControlsVisible(false);
            }
        });

        buildButtons();
        updateLabels();
        setControlsVisible(true);
    }

    private void buildButtons() {
        btnRow.removeAllViews();
        computeButtonMetrics();
        btnRewind = addIcon(R.drawable.ic_rewind, new Runnable() {
            @Override
            public void run() {
                seekBy(-10);
            }
        });
        btnPlay = addIcon(R.drawable.ic_play, new Runnable() {
            @Override
            public void run() {
                togglePlay();
            }
        });
        btnForward = addIcon(R.drawable.ic_forward, new Runnable() {
            @Override
            public void run() {
                seekBy(10);
            }
        });
        btnSpeed = addGlyph("1x", 17, new Runnable() {
            @Override
            public void run() {
                openSpeedMenu(true);
            }
        });
        btnEpisodes = addIcon(R.drawable.ic_list, new Runnable() {
            @Override
            public void run() {
                openEpisodeMenu(true);
            }
        });
        btnSource = addIcon(R.drawable.ic_swap, new Runnable() {
            @Override
            public void run() {
                openSourceMenu(true);
            }
        });
        btnAd = addGlyph("AD", 16, new Runnable() {
            @Override
            public void run() {
                toggleAdBlock();
            }
        });
        btnSkip = addIcon(R.drawable.ic_skip, new Runnable() {
            @Override
            public void run() {
                openSkipMenu(true);
            }
        });
        btnRatio = addIcon(R.drawable.ic_ratio, new Runnable() {
            @Override
            public void run() {
                cycleResize();
            }
        });
        btnFull = addIcon(R.drawable.ic_fullscreen, new Runnable() {
            @Override
            public void run() {
                exitToMini();
            }
        });
        btnMore = addIcon(R.drawable.ic_more, new Runnable() {
            @Override
            public void run() {
                openRootMenu();
            }
        });
        // 末尾按钮不留右边距，保证整排严格居中
        View last = btnRow.getChildAt(btnRow.getChildCount() - 1);
        if (last != null && last.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) last.getLayoutParams()).setMarginEnd(0);
        }
    }

    /** 11 个按钮按屏幕宽度均分，手机/平板/电视都不会被裁切 */
    private void computeButtonMetrics() {
        int count = 11;
        int w = getResources().getDisplayMetrics().widthPixels;
        int sidePad = Ui.dp(this, 44) * 2;
        int gap = Ui.dp(this, 10);
        int size = (w - sidePad - gap * (count - 1)) / count;
        btnSize = Math.max(Ui.dp(this, 34), Math.min(Ui.dp(this, 52), size));
        btnGap = gap;
    }

    private ImageView addIcon(int res, final Runnable action) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.max(Ui.dp(this, 8), btnSize / 4);
        b.setPadding(pad, pad, pad, pad);
        b.setBackgroundResource(R.drawable.bg_icon_btn);
        b.setColorFilter(0xFFFFFFFF);
        b.setFocusable(true);
        // 不设置 focusableInTouchMode，这样触屏单击即可触发点击（TV 端仍可通过方向键聚焦）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnSize, btnSize);
        lp.setMarginEnd(btnGap);
        b.setLayoutParams(lp);
        attachButton(b, action);
        btnRow.addView(b);
        return b;
    }

    private TextView addGlyph(String glyph, int sp, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(glyph);
        b.setTextSize(btnSize < Ui.dp(this, 46) ? sp - 2 : sp);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundResource(R.drawable.bg_icon_btn);
        b.setFocusable(true);
        // 不设置 focusableInTouchMode，这样触屏单击即可触发点击（TV 端仍可通过方向键聚焦）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnSize, btnSize);
        lp.setMarginEnd(btnGap);
        b.setLayoutParams(lp);
        attachButton(b, action);
        btnRow.addView(b);
        return b;
    }

    private void attachButton(View b, final Runnable action) {
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickSeq++;
                keepControls();
                action.run();
            }
        });
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    keepControls();
                    showBtnTip(v);
                }
            }
        });
    }

    /** 图标获得焦点时显示中文含义，松开焦点或隐藏控制条时清空 */
    private void showBtnTip(View v) {
        if (btnTip == null) {
            return;
        }
        String t = "";
        if (v == btnPlay) {
            t = isPlaying() ? "暂停" : "播放";
        } else if (v == btnSpeed) {
            t = "倍速 " + Ui.speedLabel(speed);
        } else if (v == btnEpisodes) {
            t = "选集";
        } else if (v == btnSource) {
            t = "换源测速";
        } else if (v == btnAd) {
            t = "去广告 " + (adBlock ? "已开启" : "已关闭");
        } else if (v == btnSkip) {
            t = "跳过片头片尾 " + skipStateText();
        } else if (v == btnRatio) {
            t = "画面比例：" + resizeName();
        } else if (v == btnFull) {
            t = "退出全屏，回到小窗播放";
        } else if (v == btnMore) {
            t = "更多设置";
        }
        btnTip.setText(t);
    }

    private boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    private void updateLabels() {
        if (btnRow == null || btnPlay == null) {
            return;
        }
        int accent = getResources().getColor(R.color.accent);
        int white = 0xFFFFFFFF;
        btnPlay.setImageResource(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        btnSpeed.setText(Ui.speedLabel(speed));
        btnSpeed.setTextColor(Math.abs(speed - 1f) < 0.01f ? white : accent);
        btnAd.setTextColor(adBlock ? accent : white);
        btnSkip.setColorFilter((skipOn && skipCfg) ? accent : white);
        btnRatio.setColorFilter(resizeMode == 0 ? white : accent);
        btnFull.setColorFilter(white);
        showBtnTip(getCurrentFocus());
        titleView.setText(mediaTitle + (urls.size() > 1 ? " · 第" + (index + 1) + "集" : ""));
        String sub = sourceName;
        if (epTitles.size() > index) {
            sub = sub + " · " + epTitles.get(index);
        }
        subView.setText(sub);
    }

    /* ------------------------------ 播放器 ------------------------------ */

    private DataSource.Factory dataSourceFactory() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android TV) OrionTVPlus")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory base = new AdBlockDataSource.Factory(http, adBlock);
        if (App.get().mediaCache() != null) {
            return new CacheDataSource.Factory()
                    .setCache(App.get().mediaCache())
                    .setUpstreamDataSourceFactory(base)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }
        return base;
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            boolean showBuffer = state == Player.STATE_BUFFERING;
            buffering.setVisibility(showBuffer ? View.VISIBLE : View.GONE);
            if (state == Player.STATE_READY) {
                prepared = true;
                applyIntroSkip();
            }
            if (state == Player.STATE_ENDED) {
                onEnded();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateLabels();
            keepControls();
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException error) {
            buffering.setVisibility(View.GONE);
            String code = error == null ? "" : error.getErrorCodeName();
            hint("播放失败（" + code + "），可尝试切换线路");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        openSourceMenu(true);
                    }
                }
            }, 1200);
        }
    };

    private void castCurrent() {
        if (urls.isEmpty()) {
            hint("没有可投屏的播放地址");
            return;
        }
        String u = urls.get(Math.max(0, Math.min(index, urls.size() - 1)));
        android.util.Log.i("CastUi", "castCurrent url=" + u);
        CastUi.show(this, u, mediaTitle, new CastUi.Listener() {
            @Override
            public void onCastChanged() {
                updateCastLabel();
            }
        });
    }

    private void updateCastLabel() {
        android.widget.ImageView b = findViewById(R.id.btnCast);
        if (b != null) {
            b.setColorFilter(CastManager.current() == null ? 0xFFFFFFFF : 0xFF16C784);
        }
    }

    private void initPlayer() {
        String targetUrl = urls.isEmpty() ? "" : urls.get(index);
        if (useShared && PlayerHolder.get() != null && targetUrl.equals(PlayerHolder.getUrl())) {
            // 复用小窗的共享播放器：直接挂到全屏，无需重新缓冲
            player = PlayerHolder.get();
            usingShared = true;
        } else {
            player = new ExoPlayer.Builder(this).build();
            usingShared = false;
        }
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setResizeMode(resizeModeOf(resizeMode));
        player.addListener(playerListener);
        syncBuffering();
    }

    /** 复用共享播放器时不会触发状态回调，需主动同步一次加载圈，避免一直转圈 */
    private void syncBuffering() {
        if (buffering == null) {
            return;
        }
        boolean show = player != null && player.getPlaybackState() == Player.STATE_BUFFERING;
        buffering.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private int resizeModeOf(int mode) {
        switch (mode) {
            case 1:
                return AspectRatioFrameLayout.RESIZE_MODE_FILL;
            case 2:
                return AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            default:
                return AspectRatioFrameLayout.RESIZE_MODE_FIT;
        }
    }

    private void startPlayback(int idx, int posSeconds) {
        if (urls.isEmpty()) {
            hint("没有播放地址");
            return;
        }
        index = Math.max(0, Math.min(idx, urls.size() - 1));
        String targetUrl = urls.get(index);

        if (firstPlayback && usingShared && targetUrl.equals(PlayerHolder.getUrl())) {
            // 首次进入全屏且共享播放器正在播放同一内容：直接续播，不重新 prepare
            firstPlayback = false;
            prepared = true;
            introApplied = false;
            ended = false;
            player.setPlaybackParameters(new PlaybackParameters(speed));
            player.setPlayWhenReady(true);
            syncBuffering();
            updateLabels();
            applyIntroSkip();
            return;
        }

        firstPlayback = false;
        prepared = false;
        introApplied = false;
        ended = false;
        MediaItem item = MediaItem.fromUri(targetUrl);
        HlsMediaSource source = new HlsMediaSource.Factory(dataSourceFactory())
                .setAllowChunklessPreparation(true)
                .createMediaSource(item);
        player.setMediaSource(source);
        player.prepare();
        if (posSeconds > 0) {
            player.seekTo((long) posSeconds * 1000L);
        }
        player.setPlaybackParameters(new PlaybackParameters(speed));
        player.setPlayWhenReady(true);
        // 切集/换源后同步共享播放器当前地址
        if (usingShared) {
            PlayerHolder.updateUrl(targetUrl);
        }
        updateLabels();
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.isPlaying()) {
            player.pause();
            hint("暂停");
        } else {
            player.play();
        }
        keepControls();
    }

    private void seekBy(int seconds) {
        if (player == null) {
            return;
        }
        long duration = player.getDuration();
        long pos = player.getCurrentPosition() + seconds * 1000L;
        if (pos < 0) {
            pos = 0;
        }
        if (duration > 0 && pos > duration - 500) {
            pos = duration - 500;
        }
        player.seekTo(Math.max(0, pos));
        hint((seconds > 0 ? "快进 " : "快退 ") + Math.abs(seconds) + "s · " + Ui.time(player.getCurrentPosition()));
        keepControls();
    }

    private void applyIntroSkip() {
        if (introApplied || !prepared || player == null) {
            return;
        }
        if (!skipOn || !skipCfg || introSec <= 0) {
            // 配置可能比画面就绪更晚返回，这里不锁定状态，等配置到达后再试
            return;
        }
        long dur = player.getDuration();
        long introMs = introSec * 1000L;
        if (dur > 0 && introMs >= dur - 5000L) {
            // 片头秒数超出片长（例如换了短片段/配置串了），直接忽略，避免一进就播完
            introApplied = true;
            return;
        }
        introApplied = true;
        long pos = Math.max(0, player.getCurrentPosition());
        if (pos < introMs - 1500L) {
            player.seekTo(introMs);
            hint("已跳过片头 " + Ui.time(introMs));
        }
    }

    private void onEnded() {
        if (ended) {
            return;
        }
        ended = true;
        saveRecord();
        if (autoNext && index + 1 < urls.size()) {
            hint("即将播放下一集");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startPlayback(index + 1, 0);
                }
            }, 1200);
        } else {
            hint("播放结束");
            setControlsVisible(true);
        }
    }

    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private void updateClock() {
        if (clockText == null || clockText.getVisibility() != View.VISIBLE) {
            return;
        }
        clockText.setText(clockFmt.format(new Date()));
    }

    private void updateUi() {
        if (player == null) {
            return;
        }
        // 兜底同步加载圈：复用共享播放器时状态回调可能缺失，避免一直转圈
        syncBuffering();
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = player.getDuration();
        if (dur > 0) {
            if (!seeking) {
                seek.setProgress(pos / (float) dur);
                timePos.setText(Ui.time(pos));
            }
            timeDur.setText(Ui.time(dur));
        } else {
            if (!seeking) {
                seek.setProgress(0);
                timePos.setText(Ui.time(pos));
            }
            timeDur.setText("--:--");
        }

        long bytes = AdBlockDataSource.BYTES.get();
        long delta = bytes - lastBytes;
        lastBytes = bytes;
        if (delta > 0) {
            double mb = delta / 1024.0 / 1024.0;
            netSpeed.setText(String.format(Locale.US, "%.1f MB/s", mb));
        }

        if (skipOn && skipCfg && outroSec > 0 && dur > 0 && !ended) {
            long outroMs = outroSec * 1000L;
            // 片尾配置必须明显短于整片，否则视为坏数据忽略，避免一进就自动结束
            if (outroMs < dur - 10000L && pos >= dur - outroMs) {
                hint("已跳过片尾");
                onEnded();
            }
        }

        tick++;
        if (tick % 10 == 0 && player.isPlaying()) {
            saveRecord();
        }
    }

    private void saveRecord() {
        if (sourceKey.isEmpty() || urls.isEmpty()) {
            return;
        }
        long pos = player == null ? 0 : player.getCurrentPosition();
        long dur = player == null ? 0 : player.getDuration();
        JSONObject rec = new JSONObject();
        try {
            rec.put("title", mediaTitle);
            rec.put("source_name", sourceName);
            rec.put("year", mediaYear == null ? "" : mediaYear);
            rec.put("cover", mediaPoster);
            rec.put("index", index + 1);
            rec.put("total_episodes", urls.size());
            rec.put("play_time", (int) (pos / 1000));
            rec.put("total_time", dur > 0 ? (int) (dur / 1000) : 0);
            rec.put("save_time", System.currentTimeMillis());
            rec.put("search_title", searchTitle);
        } catch (Exception ignored) {
        }
        api.savePlayRecord(sourceKey, rec);
    }

    /** 本机保存的跳过配置表：{ "source+id": {enable, intro_time, outro_time}, ... } */
    private JSONObject localSkipMap() {
        try {
            String s = prefs.skipConfigs();
            return new JSONObject(s == null || s.trim().isEmpty() ? "{}" : s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject readLocalSkip(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return localSkipMap().optJSONObject(key);
    }

    private void writeLocalSkip(String key, JSONObject cfg) {
        if (key == null || key.isEmpty()) {
            return;
        }
        JSONObject map = localSkipMap();
        try {
            if (cfg == null) {
                map.remove(key);
            } else {
                map.put(key, cfg);
            }
        } catch (Exception ignored) {
        }
        prefs.setSkipConfigs(map.toString());
    }

    /**
     * 片源自动测速/换线后 source+id 会变，若只用 sourceKey 存配置，换个线路配置就"消失"了。
     * 因此再按「影片ID」「片名」各存一份，任何一路换线后依然能命中。
     */
    private String skipTitleKey() {
        if (mediaTitle == null || mediaTitle.isEmpty()) {
            return "";
        }
        return "title:" + mediaTitle;
    }

    private String skipIdKey() {
        if (sourceId == null || sourceId.isEmpty()) {
            return "";
        }
        return "id:" + sourceId;
    }

    private JSONObject readSkipConfigLocal() {
        JSONObject cfg = readLocalSkip(sourceKey);
        if (cfg == null) {
            cfg = readLocalSkip(skipIdKey());
        }
        if (cfg == null) {
            cfg = readLocalSkip(skipTitleKey());
        }
        return cfg;
    }

    private void writeSkipConfigLocal(JSONObject cfg) {
        writeLocalSkip(sourceKey, cfg);
        writeLocalSkip(skipIdKey(), cfg);
        writeLocalSkip(skipTitleKey(), cfg);
    }

    /**
     * 应用一份跳过配置。配置存在时以 enable 字段为准，避免"关了又被 intro_time>0 判成开着"。
     */
    private void applySkipConfig(JSONObject cfg) {
        if (cfg == null) {
            skipCfg = false;
            introSec = 0;
            outroSec = 0;
            updateLabels();
            return;
        }
        introSec = Math.max(0, cfg.optInt("intro_time", 0));
        outroSec = Math.max(0, cfg.optInt("outro_time", 0));
        skipCfg = introSec > 0 || outroSec > 0;
        skipOn = !cfg.has("enable") || cfg.optBoolean("enable", true);
        updateLabels();
        applyIntroSkip();
    }

    /**
     * 先读本机配置（同步，保证画面就绪时能立刻跳片头），再向服务端同步一次用于换机/清数据恢复。
     */
    private void loadSkipConfig() {
        final JSONObject local = readSkipConfigLocal();
        if (local != null) {
            // 补齐 影片ID/片名 两份键，老配置换线后也能命中
            writeSkipConfigLocal(local);
        }
        applySkipConfig(local);
        api.skipConfigs(new Api.Cb<JSONObject>() {
            @Override
            public void done(JSONObject data, String err) {
                if (err != null || data == null || isFinishing() || isDestroyed() || player == null) {
                    return;
                }
                if (local != null) {
                    // 本机已有配置（用户刚改过），不要被旧的服务端数据覆盖
                    return;
                }
                JSONObject cfg = data.optJSONObject(sourceKey);
                if (cfg == null) {
                    cfg = data.optJSONObject(skipIdKey());
                }
                if (cfg == null) {
                    cfg = data.optJSONObject(skipTitleKey());
                }
                if (cfg == null) {
                    return;
                }
                writeSkipConfigLocal(cfg);
                applySkipConfig(cfg);
            }
        });
    }

    private void saveSkipConfig(boolean enable, int intro, int outro) {
        introSec = Math.max(0, intro);
        outroSec = Math.max(0, outro);
        skipCfg = introSec > 0 || outroSec > 0;
        skipOn = enable;
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("enable", enable);
            cfg.put("intro_time", introSec);
            cfg.put("outro_time", outroSec);
        } catch (Exception ignored) {
        }
        if (skipCfg) {
            writeSkipConfigLocal(cfg);
        } else {
            writeSkipConfigLocal(null);
        }
        api.saveSkipConfig(sourceKey, cfg);
        String titleKey = skipTitleKey();
        if (!titleKey.isEmpty() && !titleKey.equals(sourceKey)) {
            api.saveSkipConfig(titleKey, cfg);
        }
        String idKey = skipIdKey();
        if (!idKey.isEmpty() && !idKey.equals(sourceKey) && !idKey.equals(titleKey)) {
            api.saveSkipConfig(idKey, cfg);
        }
        updateLabels();
        if (skipOn && introSec > 0) {
            applyIntroSkip();
        }
    }

    /* ------------------------------ 交互 ------------------------------ */

    private int menuDepth = 0;
    /** true 表示菜单是从底部功能按钮直接打开的，按返回键应直接关闭菜单回到播放 */
    private boolean menuDirect = false;

    private boolean menuOpen() {
        return menuLayer != null && menuLayer.getVisibility() == View.VISIBLE;
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        controls.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            seek.requestFocus();
            handler.removeCallbacks(hideControls);
            handler.postDelayed(hideControls, 6000);
        } else {
            handler.removeCallbacks(hideControls);
            if (btnTip != null) {
                btnTip.setText("");
            }
        }
    }

    private void keepControls() {
        if (controlsVisible) {
            handler.removeCallbacks(hideControls);
            handler.postDelayed(hideControls, 6000);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        float slop = ViewConfiguration.get(this).getScaledTouchSlop() * 2f;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = ev.getX();
                touchDownY = ev.getY();
                touchDownTime = System.currentTimeMillis();
                clickSeqAtDown = clickSeq;
                tapTargetReady = controlsVisible || menuOpen();
                swipeSeeking = false;
                swipeStartPos = player == null ? 0 : player.getCurrentPosition();
                swipeTarget = swipeStartPos;
                swipeSpanMs = swipeSpan();
                // 触摸落在进度条或按钮栏上时不触发滑动快进快退，避免和拖动进度条冲突
                swipeAllow = !menuOpen()
                        && !(controlsVisible && (hitView(seek, ev) || hitView(btnRow, ev)));
                if (!menuOpen() && !controlsVisible) {
                    handler.postDelayed(longPressShowControls, 500);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - touchDownX;
                float dy = ev.getY() - touchDownY;
                if (!swipeSeeking && swipeAllow && Math.abs(dx) > slop
                        && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    // 判定为左右滑动：进入快进快退模式
                    swipeSeeking = true;
                    touchDownTime = 0;
                    seeking = true;
                    handler.removeCallbacks(longPressShowControls);
                    setControlsVisible(true);
                    handler.removeCallbacks(hideControls);
                }
                if (swipeSeeking) {
                    long dur = player == null ? 0 : player.getDuration();
                    long target = swipeStartPos + (long) (dx / (float) getResources().getDisplayMetrics().widthPixels * swipeSpanMs);
                    target = Math.max(0, dur > 0 ? Math.min(dur, target) : target);
                    swipeTarget = target;
                    if (dur > 0) {
                        seek.setProgress(target / (float) dur);
                        timePos.setText(Ui.time(target));
                    }
                    hint((dx >= 0 ? "快进 " : "快退 ") + Ui.time(target)
                            + " / " + (dur > 0 ? Ui.time(dur) : "--:--"));
                    return true;
                }
                if (dx * dx + dy * dy > 10000f) {
                    // 手指移动距离较大，取消点击与长按判定
                    touchDownTime = 0;
                    handler.removeCallbacks(longPressShowControls);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressShowControls);
                if (swipeSeeking) {
                    swipeSeeking = false;
                    seeking = false;
                    if (ev.getActionMasked() == MotionEvent.ACTION_UP && player != null) {
                        player.seekTo(swipeTarget);
                    }
                    keepControls();
                    return true;
                }
                long dt = System.currentTimeMillis() - touchDownTime;
                float dx = ev.getX() - touchDownX;
                float dy = ev.getY() - touchDownY;
                boolean isTap = touchDownTime > 0 && dx * dx + dy * dy < 10000f && dt < 600;
                boolean wasControlsVisible = controlsVisible;
                boolean wasMenuOpen = menuOpen();
                // 必须先让系统正常分发（可能产生一次真正的点击），再判断是否需要补发，
                // 否则同一次点击会执行两遍（播放/暂停变成"暂停又播放"，AD 开关被切两次）。
                boolean handled = super.dispatchTouchEvent(ev);
                if (isTap && !wasMenuOpen && !wasControlsVisible) {
                    // 功能栏隐藏时，点击任意位置呼出
                    setControlsVisible(true);
                }
                // 只有在系统完全没消费这次点击（handled=false）时才补发。
                // 按钮的点击可能是 post 到下一帧执行的（此时 clickSeq 还没变），
                // 若按 clickSeq 判断会重复触发，导致暂停又播放、AD 开关被切两次。
                if (isTap && tapTargetReady && !handled && clickSeq == clickSeqAtDown) {
                    // 这次点击被系统的"先聚焦"吞掉了，按坐标补发一次点击
                    View target = findClickableAt(getWindow().getDecorView(), ev.getRawX(), ev.getRawY());
                    if (target != null) {
                        target.performClick();
                    }
                }
                return handled;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 判断触摸点是否落在某个控件上（用于区分滑动手势和进度条/按钮操作） */
    private boolean hitView(View v, MotionEvent ev) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int pad = Ui.dp(this, 8);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] - pad && x <= loc[0] + v.getWidth() + pad
                && y >= loc[1] - pad && y <= loc[1] + v.getHeight() + pad;
    }

    /** 找出屏幕上该坐标处最上层、可见且可点击的控件 */
    private View findClickableAt(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return null;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (x < loc[0] || x >= loc[0] + v.getWidth() || y < loc[1] || y >= loc[1] + v.getHeight()) {
            return null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View hit = findClickableAt(g.getChildAt(i), x, y);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return v.isClickable() ? v : null;
    }

    /** 横向滑过整屏对应的时长跨度 */
    private long swipeSpan() {
        long dur = player == null ? 0 : player.getDuration();
        if (dur <= 0) {
            return 90000L;
        }
        return Math.max(30000L, Math.min(240000L, dur / 10));
    }

    private void hint(String text) {
        centerHint.setText(text);
        centerHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 1600);
    }

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            centerHint.setVisibility(View.GONE);
        }
    };

    private void showMenu(String title, List<MenuItem> items) {
        menuTitle.setText(title);
        menuAdapter.setItems(items);
        menuLayer.setVisibility(View.VISIBLE);
        controls.setVisibility(View.GONE);
        handler.removeCallbacks(hideControls);
        menuList.post(new Runnable() {
            @Override
            public void run() {
                if (menuList.getChildCount() > 0) {
                    menuList.getChildAt(0).requestFocusFromTouch();
                }
            }
        });
    }

    private void hideMenu() {
        menuLayer.setVisibility(View.GONE);
        setControlsVisible(true);
    }

    private void openRootMenu() {
        menuDepth = 0;
        menuDirect = false;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("播放速度", Ui.speedLabel(speed), new Runnable() {
            @Override
            public void run() {
                openSpeedMenu(false);
            }
        }));
        items.add(new MenuItem("选集", urls.size() > 1 ? ("第" + (index + 1) + "集 / 共" + urls.size() + "集") : "单片", new Runnable() {
            @Override
            public void run() {
                openEpisodeMenu(false);
            }
        }));
        items.add(new MenuItem("换源（测速）", sources.size() > 1 ? sources.size() + " 条线路" : "单线路", new Runnable() {
            @Override
            public void run() {
                openSourceMenu(false);
            }
        }));
        items.add(new MenuItem("投屏", CastManager.currentName().isEmpty() ? "未连接" : CastManager.currentName(), new Runnable() {
            @Override
            public void run() {
                hideMenu();
                castCurrent();
            }
        }));
        items.add(new MenuItem("AD 去广告", adBlock ? "已开启" : "已关闭", new Runnable() {
            @Override
            public void run() {
                toggleAdBlock();
                openRootMenu();
            }
        }));
        items.add(new MenuItem("跳过片头片尾", skipStateText(), new Runnable() {
            @Override
            public void run() {
                openSkipMenu(false);
            }
        }));
        items.add(new MenuItem("画面比例", resizeName(), new Runnable() {
            @Override
            public void run() {
                cycleResize();
                openRootMenu();
            }
        }));
        items.add(new MenuItem("小窗播放", "回到详情页继续", new Runnable() {
            @Override
            public void run() {
                exitToMini();
            }
        }));
        items.add(new MenuItem("自动连播", autoNext ? "已开启" : "已关闭", new Runnable() {
            @Override
            public void run() {
                autoNext = !autoNext;
                prefs.setAutoNext(autoNext);
                openRootMenu();
            }
        }));
        items.add(new MenuItem("关闭菜单", "", new Runnable() {
            @Override
            public void run() {
                hideMenu();
            }
        }));
        showMenu("播放设置", items);
    }

    private String resizeName() {
        if (resizeMode == 1) {
            return "拉伸铺满";
        }
        if (resizeMode == 2) {
            return "裁剪铺满";
        }
        return "原始比例";
    }

    private void openSpeedMenu(boolean direct) {
        menuDepth = direct ? 0 : 1;
        menuDirect = direct;
        List<MenuItem> items = new ArrayList<>();
        for (final float s : SPEEDS) {
            boolean active = Math.abs(s - speed) < 0.01f;
            items.add(new MenuItem(Ui.speedLabel(s) + (active ? "  ✓" : ""), active ? "当前" : "",
                    new Runnable() {
                        @Override
                        public void run() {
                            setSpeed(s);
                            hideMenu();
                        }
                    }));
        }
        items.add(new MenuItem("自定义倍速…", "", new Runnable() {
            @Override
            public void run() {
                customSpeedDialog();
            }
        }));
        if (!direct) {
            items.add(new MenuItem("‹ 返回", "", new Runnable() {
                @Override
                public void run() {
                    openRootMenu();
                }
            }));
        }
        showMenu("播放速度", items);
    }

    private void setSpeed(float s) {
        speed = Math.min(8f, Math.max(0.1f, s));
        prefs.setSpeed(speed);
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
        }
        updateLabels();
        hint("倍速 " + Ui.speedLabel(speed));
    }

    private void customSpeedDialog() {
        final Dialog dlg = new Dialog(this, R.style.DarkDialog);
        dlg.setContentView(R.layout.dialog_speed);
        Ui.fitDialogWidth(dlg, this, 380);
        final TextView tvValue = dlg.findViewById(R.id.tvSpeedValue);
        final float[] val = {Math.min(8f, Math.max(0.1f, speed))};

        final Runnable render = new Runnable() {
            @Override
            public void run() {
                tvValue.setText(String.format(Locale.US, "%.2f×", val[0]));
            }
        };
        final Runnable minus = new Runnable() {
            @Override
            public void run() {
                val[0] = Math.max(0.1f, Math.round((val[0] - 0.1f) * 100f) / 100f);
                render.run();
            }
        };
        final Runnable plus = new Runnable() {
            @Override
            public void run() {
                val[0] = Math.min(8f, Math.round((val[0] + 0.1f) * 100f) / 100f);
                render.run();
            }
        };
        render.run();

        dlg.findViewById(R.id.btnMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                minus.run();
            }
        });
        dlg.findViewById(R.id.btnPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                plus.run();
            }
        });
        // 焦点在数值上时，左右键直接微调
        tvValue.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    minus.run();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    plus.run();
                    return true;
                }
                return false;
            }
        });
        dlg.findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                openSpeedMenu(menuDirect);
            }
        });
        dlg.findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSpeed(val[0]);
                dlg.dismiss();
                hideMenu();
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (menuOpen()) {
                    menuList.post(new Runnable() {
                        @Override
                        public void run() {
                            int n = menuList.getChildCount();
                            if (n > 0) {
                                menuList.getChildAt(n - 1).requestFocusFromTouch();
                            }
                        }
                    });
                }
            }
        });
        dlg.show();
        tvValue.post(new Runnable() {
            @Override
            public void run() {
                tvValue.requestFocusFromTouch();
            }
        });
    }

    private void openEpisodeMenu(boolean direct) {
        menuDepth = direct ? 0 : 1;
        menuDirect = direct;
        List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            final int idx = i;
            String label = epTitles.size() > i && !epTitles.get(i).isEmpty()
                    ? epTitles.get(i) : ("第" + (i + 1) + "集");
            items.add(new MenuItem(label + (i == index ? "  ✓" : ""), i == index ? "播放中" : "",
                    new Runnable() {
                        @Override
                        public void run() {
                            startPlayback(idx, 0);
                            hideMenu();
                        }
                    }));
        }
        if (!direct) {
            items.add(new MenuItem("‹ 返回", "", new Runnable() {
                @Override
                public void run() {
                    openRootMenu();
                }
            }));
        }
        showMenu("选集", items);
    }

    private void openSourceMenu(boolean direct) {
        menuDepth = direct ? 0 : 1;
        menuDirect = direct;
        sourceMenuItems = new ArrayList<>();
        for (final VideoItem v : sources) {
            boolean active = v.source.equals(currentSource()) && v.id.equals(sourceId);
            final MenuItem mi = new MenuItem(
                    (v.sourceName == null || v.sourceName.isEmpty() ? v.source : v.sourceName) + (active ? "  ✓" : ""),
                    active ? "当前" : "测速中…",
                    new Runnable() {
                        @Override
                        public void run() {
                            switchSource(v);
                            hideMenu();
                        }
                    });
            sourceMenuItems.add(mi);
        }
        sourceMenuItems.add(new MenuItem("重新测速", "", new Runnable() {
            @Override
            public void run() {
                openSourceMenu(menuDirect);
            }
        }));
        if (!direct) {
            sourceMenuItems.add(new MenuItem("‹ 返回", "", new Runnable() {
                @Override
                public void run() {
                    openRootMenu();
                }
            }));
        }
        showMenu("换源（自动测速）", sourceMenuItems);
        testSources();
    }

    private String currentSource() {
        for (VideoItem v : sources) {
            if (v.episodes.size() > index && index < v.episodes.size() && v.id.equals(sourceId)) {
                return v.source;
            }
        }
        return "";
    }

    private void testSources() {
        for (int i = 0; i < sources.size(); i++) {
            final VideoItem v = sources.get(i);
            if (v.episodes.isEmpty()) {
                continue;
            }
            final int pos = i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final SourceTester.Result r = SourceTester.test(v.episodes.get(0));
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!menuOpen() || pos >= sourceMenuItems.size()) {
                                return;
                            }
                            // 测速回填只更新数值，不整体重建列表，避免面板焦点丢失
                            String text = r.ok
                                    ? (r.quality + " · " + r.speedText + " · " + r.pingMs + "ms")
                                    : "测速失败";
                            View focused = menuList.findFocus();
                            int focusedPos = focused == null ? -1
                                    : menuList.getChildAdapterPosition(focused);
                            menuAdapter.updateValue(pos, text);
                            if (focusedPos >= 0) {
                                menuList.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        View v = menuList.getLayoutManager()
                                                .findViewByPosition(focusedPos);
                                        if (v != null) {
                                            v.requestFocusFromTouch();
                                        }
                                    }
                                });
                            } else if (menuList.findFocus() == null) {
                                menuList.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (menuList.getChildCount() > 0) {
                                            menuList.getChildAt(0).requestFocusFromTouch();
                                        }
                                    }
                                });
                            }
                        }
                    });
                }
            });
        }
    }

    private void switchSource(VideoItem v) {
        if (v.episodes.isEmpty()) {
            return;
        }
        saveRecord();
        stopPlayerKeepPosition();
        urls.clear();
        urls.addAll(v.episodes);
        epTitles.clear();
        epTitles.addAll(v.episodeTitles);
        sourceId = v.id;
        sourceKey = v.source + "+" + v.id;
        sourceName = v.sourceName;
        mediaPoster = v.poster;
        if (mediaTitle == null || mediaTitle.isEmpty()) {
            mediaTitle = v.title;
        }
        index = Math.min(index, urls.size() - 1);
        startPos = 0;
        skipCfg = false;
        introSec = 0;
        outroSec = 0;
        loadSkipConfig();
        startPlayback(index, 0);
        hint("已切换线路：" + (v.sourceName.isEmpty() ? v.source : v.sourceName));
        updateLabels();
    }

    private void stopPlayerKeepPosition() {
        if (player != null) {
            player.stop();
        }
    }

    private void toggleAdBlock() {
        adBlock = !adBlock;
        prefs.setAdBlock(adBlock);
        long pos = player == null ? 0 : player.getCurrentPosition();
        boolean playing = player != null && player.isPlaying();
        if (player != null) {
            player.stop();
            startPlayback(index, 0);
            player.seekTo(pos);
            player.setPlayWhenReady(playing);
        }
        updateLabels();
        hint(adBlock ? "去广告已开启" : "去广告已关闭");
    }

    /** 底部图标/菜单共用的状态文案 */
    private String skipStateText() {
        if (!skipCfg) {
            return "未设置";
        }
        return skipOn ? "已开启" : "已关闭";
    }

    private void openSkipMenu(boolean direct) {
        menuDepth = direct ? 0 : 1;
        menuDirect = direct;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("跳过片头片尾", skipStateText(), new Runnable() {
            @Override
            public void run() {
                if (!skipCfg) {
                    hint("请先设置片头或片尾");
                } else {
                    saveSkipConfig(!skipOn, introSec, outroSec);
                    hint(skipOn ? "跳过片头片尾已开启" : "跳过片头片尾已关闭");
                }
                openSkipMenu(menuDirect);
            }
        }));
        items.add(new MenuItem("设置片头（当前进度）", introSec > 0 ? Ui.time(introSec * 1000L) : "未设置", new Runnable() {
            @Override
            public void run() {
                int cur = player == null ? 0 : (int) (player.getCurrentPosition() / 1000);
                if (cur < 1) {
                    hint("请播放到片头结束处再设置");
                    return;
                }
                saveSkipConfig(true, cur, outroSec);
                hint("已设置片头 " + Ui.time(cur * 1000L) + "，从头播放自动跳过");
                openSkipMenu(menuDirect);
            }
        }));
        items.add(new MenuItem("设置片尾（当前进度起跳）", outroSec > 0 ? Ui.time(outroSec * 1000L) : "未设置", new Runnable() {
            @Override
            public void run() {
                long dur = player == null ? 0 : player.getDuration();
                long pos = player == null ? 0 : player.getCurrentPosition();
                if (dur <= 0) {
                    hint("时长未知，稍后再试");
                } else if (dur - pos < 10000L) {
                    hint("请提前一些设置片尾（至少距结束 10 秒）");
                } else {
                    saveSkipConfig(true, introSec, (int) ((dur - pos) / 1000));
                    hint("已设置片尾，剩 " + Ui.time(dur - pos) + " 时自动跳过");
                    openSkipMenu(menuDirect);
                }
            }
        }));
        items.add(new MenuItem("删除跳过配置", "", new Runnable() {
            @Override
            public void run() {
                saveSkipConfig(false, 0, 0);
                hint("已删除跳过配置");
                openSkipMenu(menuDirect);
            }
        }));
        if (!direct) {
            items.add(new MenuItem("‹ 返回", "", new Runnable() {
                @Override
                public void run() {
                    openRootMenu();
                }
            }));
        }
        showMenu("跳过片头片尾", items);
    }

    private void cycleResize() {
        resizeMode = (resizeMode + 1) % 3;
        prefs.setResizeMode(resizeMode);
        playerView.setResizeMode(resizeModeOf(resizeMode));
        updateLabels();
        hint("画面：" + resizeName());
    }

    /** 退出全屏，返回详情页小窗播放并带回进度 */
    private void exitToMini() {
        // 先保存进度再 detach
        int pos = player == null ? 0 : (int) (player.getCurrentPosition() / 1000);
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_INDEX, index);
        data.putExtra(EXTRA_POS, pos);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuOpen()) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!controlsVisible || seek.hasFocus()) {
                    seekBy(event.getRepeatCount() > 0 ? -30 : -10);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!controlsVisible || seek.hasFocus()) {
                    seekBy(event.getRepeatCount() > 0 ? 30 : 10);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!controlsVisible) {
                    setControlsVisible(true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!controlsVisible) {
                    setControlsVisible(true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                if (!controlsVisible) {
                    setControlsVisible(true);
                }
                openRootMenu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-30);
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (menuOpen()) {
            if (menuDirect || menuDepth == 0) {
                // 从底部功能按钮直接打开的菜单（倍速/选集/换源/片头）：返回直接关闭菜单回到播放
                hideMenu();
            } else {
                openRootMenu();
            }
            return;
        }
        if (controlsVisible) {
            setControlsVisible(false);
            return;
        }
        exitToMini();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
            saveRecord();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveRecord();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        if (player != null) {
            player.removeListener(playerListener);
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            if (!usingShared) {
                player.release();
            }
            player = null;
        }
        super.onDestroy();
    }
}
