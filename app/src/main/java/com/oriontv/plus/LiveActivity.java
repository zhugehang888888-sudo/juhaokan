package com.oriontv.plus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 直播模块：订阅源（m3u/txt）自动解析分组，数字键换台，遥控器方向键/OK 与触屏均可操作。
 * 面板、右上角节目号、底部信息条 5 秒无操作自动收起。
 */
public class LiveActivity extends Activity {

    private static final long AUTO_HIDE_MS = 5000;
    /** 单个源 5 秒还没起播就自动换源 */
    private static final long PLAY_TIMEOUT_MS = 5000;
    /** 自动换源/换台连续失败次数上限，避免死循环 */
    private static final int MAX_AUTO_SWITCH = 20;

    private static final String[] DECODE_LABELS = {"自动（推荐）", "硬解（优先硬件解码）", "软解（兼容优先）"};
    private static final String[] KERNEL_LABELS = {"Media3 播放器（默认）", "系统播放器（兼容模式）"};

    private final Prefs prefs = App.get().prefs();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PlayerView playerView;
    private VideoView systemPlayer;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory http;

    private LinearLayout panel;
    private RecyclerView groupList;
    private RecyclerView channelList;
    private TextView groupTitle;
    private TextView groupCount;
    private TextView bigNumber;
    private TextView dateText;
    private TextView timeText;
    private TextView channelName;
    private TextView numberHint;
    private ProgressBar loading;
    private TextView empty;

    private View channelBadge;
    private View bottomInfo;
    private TextView btnSourceValue;
    private TextView btnDecodeValue;
    private TextView btnKernelValue;
    private TextView btnChannelSourceValue;
    private TextView liveCastValue;

    private LiveGroupAdapter groupAdapter;
    private LiveChannelAdapter channelAdapter;

    private final List<LiveChannel> all = new ArrayList<>();
    private final Map<String, List<LiveChannel>> groups = new LinkedHashMap<>();
    /** 记录每个频道地址是不是 HLS，避免每次换台都探测 */
    private final Map<String, Boolean> hlsCache = new LinkedHashMap<>();
    private int playSeq = 0;
    private String currentGroup = "";
    private int currentIndex = -1;
    private int playingNumber = -1;
    /** 当前频道正在使用第几个源（从 0 开始） */
    private int sourceIdx = 0;
    /** 连续自动换源/换台次数，起播成功后清零 */
    private int autoSwitchCount = 0;

    private final StringBuilder digits = new StringBuilder();
    private boolean panelVisible = false;
    private boolean chromeVisible = false;
    /** 当前是竖屏布局 */
    private boolean portraitUi = false;
    /** 正在播放的频道，切换屏幕方向后用来重排底部信息文字 */
    private LiveChannel lastChannel;
    /** 本次方向键/OK 的 DOWN 是否已被我们处理（触摸模式下 DOWN 会被系统吞掉） */
    private boolean navDownHandled = false;
    private boolean touchDownHidden = false;
    private boolean touchDownOutside = false;
    private boolean narrowScreen = false;
    /** 当前已加载的订阅源配置，回到页面时如果配置变了就重新加载 */
    private String sourcesSig = "";
    private String serverSig = "";
    /** 当前选中的订阅源 */
    private LiveSource currentSource;
    /** 解码方式 + 内核 的组合标识，变化时需要重建播放器 */
    private int playerSig = -1;

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("MM/dd EEEE", Locale.CHINA);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable commitDigits = new Runnable() {
        @Override
        public void run() {
            commitChannelNumber();
        }
    };

    /** 5 秒无操作收起面板 / 节目号 / 底部信息 */
    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            hideAll();
        }
    };

    /** 5 秒起播超时：换下一个源，没有其他源就换下一个频道 */
    private final Runnable playTimeout = new Runnable() {
        @Override
        public void run() {
            onSourceFailed();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.livePlayer);
        systemPlayer = findViewById(R.id.liveSystemPlayer);
        panel = findViewById(R.id.livePanel);
        groupList = findViewById(R.id.liveGroups);
        channelList = findViewById(R.id.liveChannelList);
        groupTitle = findViewById(R.id.liveGroupTitle);
        groupCount = findViewById(R.id.liveGroupCount);
        bigNumber = findViewById(R.id.liveBigNumber);
        dateText = findViewById(R.id.liveDate);
        timeText = findViewById(R.id.liveTime);
        channelName = findViewById(R.id.liveChannelName);
        numberHint = findViewById(R.id.liveNumberHint);
        loading = findViewById(R.id.liveLoading);
        empty = findViewById(R.id.liveEmpty);
        channelBadge = findViewById(R.id.channelBadge);
        bottomInfo = findViewById(R.id.liveBottomInfo);
        btnSourceValue = findViewById(R.id.liveBtnSourceValue);
        btnDecodeValue = findViewById(R.id.liveBtnDecodeValue);
        btnKernelValue = findViewById(R.id.liveBtnKernelValue);
        btnChannelSourceValue = findViewById(R.id.liveChannelSourceValue);
        liveCastValue = findViewById(R.id.liveCastValue);
        updateCastBar();

        // 竖屏下布局容易在旋转后错位，直播统一按横屏播放
        enforceLandscape();

        initPlayer();
        initLists();
        layoutPanel();

        findViewById(R.id.liveBtnHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(LiveActivity.this, MainActivity.class);
                i.putExtra("skipLive", true);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            }
        });
        findViewById(R.id.liveBtnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LiveActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.liveBtnSource).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSource();
            }
        });
        findViewById(R.id.liveBtnChannelSource).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseChannelSource();
            }
        });
        findViewById(R.id.liveBtnCast).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                castCurrent();
            }
        });
        findViewById(R.id.liveBtnDecode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseDecode();
            }
        });
        findViewById(R.id.liveBtnKernel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseKernel();
            }
        });

        loadChannels();
        updateClock();
        refreshBars();
    }

    /* ------------------------------------------------------------------ */
    /* 播放器                                                              */
    /* ------------------------------------------------------------------ */

    private void initPlayer() {
        releasePlayer();
        playerSig = prefs.liveDecode() * 10 + prefs.liveKernel();
        if (prefs.liveKernel() == 1) {
            // 系统播放器内核：用 VideoView（MediaPlayer），兼容部分 HLS 直播源
            playerView.setVisibility(View.GONE);
            systemPlayer.setVisibility(View.VISIBLE);
            return;
        }
        systemPlayer.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector(prefs.liveDecode()));
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
                .build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                onSourceFailed();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    loading.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    cancelPlayTimeout();
                    autoSwitchCount = 0;
                } else if (state == Player.STATE_ENDED) {
                    loading.setVisibility(View.GONE);
                }
            }
        });
    }

    /** 解码方式：自动用系统默认，硬解/软解按解码器名称过滤（都失败时回退到全部） */
    private MediaCodecSelector codecSelector(final int mode) {
        if (mode == 0) {
            return MediaCodecSelector.DEFAULT;
        }
        return new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                        boolean requiresTunnelingDecoder)
                    throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                List<MediaCodecInfo> out = new ArrayList<>();
                for (MediaCodecInfo info : all) {
                    boolean software = isSoftwareDecoder(info.name);
                    if (mode == 2 ? software : !software) {
                        out.add(info);
                    }
                }
                return out.isEmpty() ? all : out;
            }
        };
    }

    private static boolean isSoftwareDecoder(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.")
                || n.contains(".sw.") || n.contains("software");
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void initLists() {
        groupAdapter = new LiveGroupAdapter(new LiveGroupAdapter.Listener() {
            @Override
            public void onGroup(String group) {
                selectGroup(group, false);
            }
        });
        groupList.setLayoutManager(new LinearLayoutManager(this));
        groupList.setAdapter(groupAdapter);

        channelAdapter = new LiveChannelAdapter(new LiveChannelAdapter.Listener() {
            @Override
            public void onPlay(LiveChannel channel) {
                playChannel(channel.number - 1);
            }
        });
        channelList.setLayoutManager(new LinearLayoutManager(this));
        channelList.setAdapter(channelAdapter);
    }

    /** 竖屏/小屏时收窄左右两栏、压缩底栏，保证频道列表和底部按钮都完整显示 */
    private void layoutPanel() {
        View root = findViewById(R.id.liveRoot);
        int w = root != null ? root.getWidth() : 0;
        int h = root != null ? root.getHeight() : 0;
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        float density = getResources().getDisplayMetrics().density;
        narrowScreen = w / density < 600;
        portraitUi = h > w;

        View groupPanel = findViewById(R.id.groupPanel);
        View channelPanel = findViewById(R.id.channelPanel);
        LinearLayout.LayoutParams lp1 = (LinearLayout.LayoutParams) groupPanel.getLayoutParams();
        LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) channelPanel.getLayoutParams();
        // 分组栏按「够显示 体育-昨天11-15」来定宽，而不是按比例，避免文字被省略
        int groupWidth = Math.max(Ui.dp(this, 118), Math.min(Ui.dp(this, 196),
                (int) (w * (portraitUi ? 0.34f : (narrowScreen ? 0.32f : 0.30f)))));
        if (portraitUi) {
            // 右侧留一条空白，方便点空白处收起菜单
            panel.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 26), Ui.dp(this, 62));
            lp1.width = Math.max(Ui.dp(this, 100), Math.min(Ui.dp(this, 120), (int) (w * 0.28f)));
            lp1.weight = 0;
            lp2.width = 0;
            lp2.weight = 1;
            lp2.setMarginStart(Ui.dp(this, 6));
        } else {
            panel.setPadding(Ui.dp(this, 24), Ui.dp(this, 22), Ui.dp(this, 24), Ui.dp(this, 108));
            lp1.width = groupWidth;
            lp1.weight = 0;
            // 频道栏只按节目名所需宽度，右侧留出大片空白方便点击退出
            lp2.width = Math.min(Ui.dp(this, 300), (int) (w * 0.42f));
            lp2.weight = 0;
            lp2.setMarginStart(Ui.dp(this, 14));
        }
        groupPanel.setLayoutParams(lp1);
        channelPanel.setLayoutParams(lp2);
        // 面板末尾的占位 View 也带 weight，竖屏时要去掉，否则频道栏只能拿到一半宽度
        View spacer = findViewById(R.id.livePanelSpacer);
        if (spacer != null && spacer.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) spacer.getLayoutParams();
            sp.weight = portraitUi ? 0 : 1;
            spacer.setLayoutParams(sp);
        }

        // 底部状态栏：竖屏只保留「台名 + 线路/解码/内核/主页/设置」，去掉数值和提示文字
        ViewGroup.LayoutParams blp = bottomInfo.getLayoutParams();
        blp.height = Ui.dp(this, portraitUi ? 52 : 84);
        bottomInfo.setLayoutParams(blp);
        bottomInfo.setPadding(Ui.dp(this, portraitUi ? 12 : 22), 0,
                Ui.dp(this, portraitUi ? 4 : 14), 0);
        View hint = findViewById(R.id.liveHint);
        if (hint != null) {
            // 提示行放不下就不显示（手机横屏），避免被裁掉一半；电视/大屏保留
            boolean show = !portraitUi && !narrowScreen;
            if (show) {
                int inner = w - bottomInfo.getPaddingLeft() - bottomInfo.getPaddingRight();
                float avail = inner * 3f / 8f;
                float need = ((TextView) hint).getPaint()
                        .measureText(((TextView) hint).getText().toString());
                show = avail >= need + Ui.dp(this, 8);
            }
            hint.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        int valueVisibility = portraitUi ? View.GONE : View.VISIBLE;
        findViewById(R.id.liveBtnSourceValue).setVisibility(valueVisibility);
        findViewById(R.id.liveBtnDecodeValue).setVisibility(valueVisibility);
        findViewById(R.id.liveBtnKernelValue).setVisibility(valueVisibility);
        findViewById(R.id.liveChannelSourceValue).setVisibility(valueVisibility);
        findViewById(R.id.liveCastValue).setVisibility(valueVisibility);
        View infoBox = findViewById(R.id.liveInfoBox);
        if (infoBox != null && infoBox.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) infoBox.getLayoutParams()).weight =
                    (portraitUi || narrowScreen) ? 2 : 3;
            infoBox.requestLayout();
        }
        // 底栏文字统一 13sp（竖屏隐藏数值，横屏显示线路/解码/内核的具体值）
        setTextSize(R.id.liveChannelName, 13);
        setTextSize(R.id.liveSourceLabel, 13);
        setTextSize(R.id.liveBtnSourceValue, 13);
        setTextSize(R.id.liveDecodeLabel, 13);
        setTextSize(R.id.liveBtnDecodeValue, 13);
        setTextSize(R.id.liveKernelLabel, 13);
        setTextSize(R.id.liveBtnKernelValue, 13);
        setTextSize(R.id.liveChannelSourceLabel, 13);
        setTextSize(R.id.liveChannelSourceValue, 13);
        setTextSize(R.id.liveCastLabel, 11);
        setTextSize(R.id.liveCastValue, 11);
        setTextSize(R.id.liveBtnHome, 13);
        setTextSize(R.id.liveBtnSettings, 13);
        setTextSize(R.id.liveGroupTitle, portraitUi ? 13 : 15);
        setTextSize(R.id.liveGroupCount, portraitUi ? 10 : 11);
        if (channelName != null && lastChannel != null) {
            channelName.setText(channelNameText(lastChannel));
        }

        updateChrome();
    }

    /** 竖屏/手机窄屏底栏信息位窄，只显示频道名，不再拼「分组」 */
    private String channelNameText(LiveChannel c) {
        return c.name + ((portraitUi || narrowScreen || TextUtils.isEmpty(c.group)) ? "" : "  ·  " + c.group);
    }

    private void setTextSize(int id, float sp) {
        TextView v = findViewById(id);
        if (v != null) {
            v.setTextSize(sp);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            // 检测到竖屏立即转回横屏，避免面板/底栏在旋转后错位
            enforceLandscape();
            return;
        }
        View root = findViewById(R.id.liveRoot);
        if (root != null) {
            root.post(new Runnable() {
                @Override
                public void run() {
                    layoutPanel();
                }
            });
        }
    }

    /** 当前是竖屏时强制横屏播放 */
    private void enforceLandscape() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 加载订阅源（只加载默认订阅源，不再合并全部订阅源）                    */
    /* ------------------------------------------------------------------ */

    /** 两份订阅源地址集合是否完全一致（顺序无关），用于迁移旧的内置顺序 */
    private static boolean sameSourceSet(List<LiveSource> a, List<LiveSource> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (LiveSource s : a) {
            boolean found = false;
            for (LiveSource t : b) {
                if (s.url.equals(t.url)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private void loadChannels() {
        List<LiveSource> sources = LiveSource.list(prefs);
        // 内置订阅源只属于「启用中的内置账号」：切到别的账号或被停用后都不可见、不可用
        ServerProfile owner = prefs.activeServer();
        boolean builtinAllowed = owner != null && !owner.disabled && owner.builtin;
        if (builtinAllowed && BuildConfig.LIVE_SOURCES != null
                && !BuildConfig.LIVE_SOURCES.isEmpty()) {
            // 内置版预置订阅源（名称|地址，分号分隔），第一个作为默认源
            List<LiveSource> builtin = new ArrayList<>();
            for (String part : BuildConfig.LIVE_SOURCES.split(";")) {
                int cut = part.indexOf('|');
                if (cut > 0) {
                    builtin.add(new LiveSource(part.substring(0, cut), part.substring(cut + 1)));
                }
            }
            if (!builtin.isEmpty()) {
                if (sources.isEmpty()) {
                    sources.addAll(builtin);
                    LiveSource.save(prefs, sources);
                    prefs.setLiveDefaultSource(builtin.get(0).url);
                } else if (sameSourceSet(sources, builtin)
                        && !sources.get(0).url.equals(builtin.get(0).url)) {
                    // 老版本内置顺序（ysp/1234/1905）迁移为：1234 → 1905 → ysp
                    sources.clear();
                    sources.addAll(builtin);
                    LiveSource.save(prefs, sources);
                    prefs.setLiveDefaultSource(builtin.get(0).url);
                }
            }
        }
        sourcesSig = prefs.liveSources();
        serverSig = prefs.activeServerId();
        cancelPlayTimeout();
        loading.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        all.clear();
        groups.clear();
        digits.setLength(0);
        currentIndex = -1;
        playingNumber = -1;
        if (sources.isEmpty()) {
            currentSource = null;
            loading.setVisibility(View.GONE);
            empty.setText("还没有直播订阅源\n请到「设置 → 直播订阅源」添加");
            empty.setVisibility(View.VISIBLE);
            hideAll();
            refreshBars();
            return;
        }
        currentSource = pickDefault(sources);
        refreshBars();
        LiveParser.load(currentSource.name, currentSource.url, new LiveParser.Cb() {
            @Override
            public void done(List<LiveChannel> list, String err) {
                if (isFinishing()) {
                    return;
                }
                build(list, err);
            }
        });
    }

    /** 找默认订阅源；没设置或已删除时退回第一个并记住 */
    private LiveSource pickDefault(List<LiveSource> sources) {
        String def = prefs.liveDefaultSource();
        LiveSource first = sources.get(0);
        if (TextUtils.isEmpty(def)) {
            prefs.setLiveDefaultSource(first.url);
            return first;
        }
        for (LiveSource s : sources) {
            if (s.url.equals(def)) {
                return s;
            }
        }
        prefs.setLiveDefaultSource(first.url);
        return first;
    }

    /**
     * 节目名归一化：CCTV-1 / CCTV1综合 / CCTV1综合高清 视为同一个频道。
     * 去掉空格、横线、点号等分隔符，再去掉清晰度和央视常见频道后缀。
     */
    private static String normalizeChannelName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toUpperCase(Locale.US).trim();
        s = s.replaceAll("[\\s\\-_·•・.。]+", "");
        s = s.replaceAll("(4K|8K|UHD|FHD|HD|HEVC|H265|H264)$", "");
        s = s.replaceAll("(高清|超清|蓝光|标清)$", "");
        s = s.replaceAll("(体育赛事|中文国际|国防军事|社会与法|电视剧|综合|财经|综艺|体育|电影|军事|纪录|科教|少儿|音乐|新闻|农业|法治)$", "");
        return s;
    }

    private void build(List<LiveChannel> list, String err) {
        loading.setVisibility(View.GONE);
        if (list != null) {
            // 同一个节目在订阅源里可能有多条线路，按「分组 + 节目名」合并成一条，地址全部保留
            Map<String, LiveChannel> merged = new LinkedHashMap<>();
            for (LiveChannel c : list) {
                if (c == null) {
                    continue;
                }
                String name = c.name == null ? "" : c.name.trim();
                String group = c.group == null || c.group.trim().isEmpty() ? "未分组" : c.group.trim();
                if (name.isEmpty()) {
                    name = "频道" + (merged.size() + 1);
                }
                // 同一个节目在订阅源里可能有多条线路，节目名归一化后合并成一条，地址全部保留
                String norm = normalizeChannelName(name);
                if (norm.isEmpty()) {
                    norm = name;
                }
                String key = group + "\u0001" + norm;
                LiveChannel m = merged.get(key);
                if (m == null) {
                    m = new LiveChannel();
                    m.name = name;
                    m.group = group;
                    m.logo = c.logo == null ? "" : c.logo;
                    m.sourceName = c.sourceName;
                    m.addUrl(c.url);
                    merged.put(key, m);
                    all.add(m);
                } else {
                    if ((m.logo == null || m.logo.isEmpty()) && c.logo != null && !c.logo.isEmpty()) {
                        m.logo = c.logo;
                    }
                    m.addUrl(c.url);
                }
            }
            for (int i = 0; i < all.size(); i++) {
                LiveChannel c = all.get(i);
                c.number = i + 1;
                c.url = c.urlAt(0);
            }
        }
        if (all.isEmpty()) {
            empty.setText(err != null ? err
                    : "订阅源没有解析到频道\n请检查订阅地址是否为 m3u / txt 直播列表");
            empty.setVisibility(View.VISIBLE);
            hideAll();
            return;
        }
        empty.setVisibility(View.GONE);
        // 不再提供「全部」分组，只按订阅源里的分组展示
        for (LiveChannel c : all) {
            String g = c.group == null || c.group.trim().isEmpty() ? "未分组" : c.group.trim();
            List<LiveChannel> l = groups.get(g);
            if (l == null) {
                l = new ArrayList<>();
                groups.put(g, l);
            }
            l.add(c);
        }
        groupAdapter.setItems(new ArrayList<>(groups.keySet()));
        String first = groups.keySet().iterator().next();
        selectGroup(first, false);

        int last = prefs.liveLastIndex();
        int start = Math.max(0, Math.min(last - 1, all.size() - 1));
        playChannel(start);
        showPanel(true, false);
    }

    private void selectGroup(String group, boolean playFirst) {
        if (!groups.containsKey(group)) {
            return;
        }
        currentGroup = group;
        groupAdapter.setSelected(group);
        List<LiveChannel> list = groups.get(group);
        channelAdapter.setItems(list);
        channelAdapter.setPlaying(playingNumber);
        groupTitle.setText(group);
        groupCount.setText(list == null ? "" : list.size() + " 个频道");
        if (playFirst && list != null && !list.isEmpty()) {
            playChannel(list.get(0).number - 1);
        }
        focusPlayingChannel();
        resetAutoHide();
    }

    private void refreshBars() {
        btnSourceValue.setText(currentSource == null ? "未设置"
                : (TextUtils.isEmpty(currentSource.name) ? LiveSource.guessName(currentSource.url)
                : currentSource.name));
        btnDecodeValue.setText(decodeLabel(prefs.liveDecode()));
        btnKernelValue.setText(prefs.liveKernel() == 1 ? "系统" : "Media3");
    }

    private static String decodeLabel(int mode) {
        if (mode == 1) {
            return "硬解";
        }
        if (mode == 2) {
            return "软解";
        }
        return "自动";
    }

    /* ------------------------------------------------------------------ */
    /* 播放                                                                */
    /* ------------------------------------------------------------------ */

    private void playChannel(int index) {
        playChannel(index, false);
    }

    private void playChannel(int index, boolean autoAdvance) {
        if (index < 0 || index >= all.size()) {
            Ui.toast(this, "没有这个频道");
            return;
        }
        currentIndex = index;
        sourceIdx = 0;
        if (!autoAdvance) {
            autoSwitchCount = 0;
        }
        LiveChannel c = all.get(index);
        playingNumber = c.number;
        playCurrentSource();
        prefs.setLiveLastIndex(c.number);

        bigNumber.setText(String.valueOf(c.number));
        lastChannel = c;
        channelName.setText(channelNameText(c));
        channelAdapter.setPlaying(c.number);
        chromeVisible = true;
        updateChrome();
        resetAutoHide();
    }

    private LiveChannel currentChannel() {
        if (currentIndex < 0 || currentIndex >= all.size()) {
            return null;
        }
        return all.get(currentIndex);
    }

    /** 用当前 sourceIdx 对应的地址起播 */
    private void playCurrentSource() {
        LiveChannel c = currentChannel();
        if (c == null) {
            return;
        }
        if (c.urls.isEmpty()) {
            c.addUrl(c.url);
        }
        if (c.urls.isEmpty()) {
            onSourceFailed();
            return;
        }
        if (sourceIdx < 0) {
            sourceIdx = 0;
        }
        if (sourceIdx >= c.urls.size()) {
            sourceIdx = c.urls.size() - 1;
        }
        c.url = c.urls.get(sourceIdx);
        loading.setVisibility(View.VISIBLE);
        cancelPlayTimeout();
        playSeq++;
        lastChannel = c;
        updateChannelSourceBar();
        if (prefs.liveKernel() == 1) {
            playWithSystem(c);
        } else {
            playWithExo(c);
        }
    }

    /** 起播 5 秒超时 + 播放出错时：先换同频道下一个源，没有源就换下一个频道 */
    private void onSourceFailed() {
        cancelPlayTimeout();
        if (isFinishing()) {
            return;
        }
        LiveChannel c = currentChannel();
        if (c == null) {
            return;
        }
        loading.setVisibility(View.GONE);
        if (c.urls.size() > 1 && sourceIdx + 1 < c.urls.size()) {
            sourceIdx++;
            Ui.toast(this, "连接超时，切换源 " + (sourceIdx + 1) + "/" + c.urls.size());
            playCurrentSource();
            return;
        }
        if (autoSwitchCount >= MAX_AUTO_SWITCH) {
            Ui.toast(this, "连续切换失败，请检查订阅源");
            return;
        }
        autoSwitchCount++;
        Ui.toast(this, "该频道暂无可用源，切换下一频道");
        int next = currentIndex + 1;
        if (next >= all.size()) {
            next = 0;
        }
        playChannel(next, true);
    }

    private void armPlayTimeout() {
        cancelPlayTimeout();
        handler.postDelayed(playTimeout, PLAY_TIMEOUT_MS);
    }

    private void cancelPlayTimeout() {
        handler.removeCallbacks(playTimeout);
    }

    /** 底栏「源」按钮显示当前是第几个源 / 共几个源 */
    private void updateChannelSourceBar() {
        if (btnChannelSourceValue == null) {
            return;
        }
        LiveChannel c = currentChannel();
        int total = c == null ? 0 : c.urls.size();
        btnChannelSourceValue.setText(total <= 0 ? "-" : (sourceIdx + 1) + "/" + total);
    }

    /** 把当前直播频道投到同一局域网的电视 */
    private void castCurrent() {
        final LiveChannel c = currentChannel();
        if (c == null || c.url == null || c.url.isEmpty()) {
            Ui.toast(this, "请先选择一个频道");
            return;
        }
        CastUi.show(this, c.url, channelNameText(c), new CastUi.Listener() {
            @Override
            public void onCastChanged() {
                updateCastBar();
            }
        });
    }

    private void updateCastBar() {
        if (liveCastValue == null) {
            return;
        }
        String n = CastManager.currentName();
        liveCastValue.setText(n == null || n.isEmpty() ? "未投屏" : n);
    }

    private void playWithExo(final LiveChannel c) {
        if (player == null || http == null) {
            initPlayer();
        }
        final String url = c.url == null ? "" : c.url;
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8") || lower.contains("m3u8?")) {
            startExo(c, true);
            return;
        }
        Boolean cached = hlsCache.get(url);
        if (cached != null) {
            startExo(c, cached);
            return;
        }
        // 有些订阅源地址没有 .m3u8 后缀（例如 1234 源的 .../608807420），先探测它实际返回什么
        final int seq = playSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Boolean hls = probeHls(url);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != playSeq || isFinishing() || player == null) {
                            return;
                        }
                        boolean useHls = hls != null && hls;
                        hlsCache.put(url, useHls);
                        startExo(c, useHls);
                    }
                });
            }
        }).start();
    }

    private void startExo(LiveChannel c, boolean hls) {
        if (player == null) {
            return;
        }
        MediaItem item = MediaItem.fromUri(c.url);
        MediaSource source = hls
                ? new HlsMediaSource.Factory(http).createMediaSource(item)
                : new ProgressiveMediaSource.Factory(http).createMediaSource(item);
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        armPlayTimeout();
    }

    /** 探测地址返回的是不是 HLS 播放列表（跟随跳转后看 Content-Type / 内容头部） */
    private static Boolean probeHls(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
            String ct = conn.getContentType();
            if (ct != null && ct.toLowerCase(Locale.US).contains("mpegurl")) {
                return Boolean.TRUE;
            }
            java.net.URL finalUrl = conn.getURL();
            if (finalUrl != null && finalUrl.toString().toLowerCase(Locale.US).contains(".m3u8")) {
                return Boolean.TRUE;
            }
            java.io.InputStream in = conn.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            in.close();
            if (n > 0) {
                String head = new String(buf, 0, n, "UTF-8").trim();
                if (head.startsWith("#EXTM3U") || head.contains("#EXTINF")) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void playWithSystem(LiveChannel c) {
        systemPlayer.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        systemPlayer.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(android.media.MediaPlayer mp) {
                loading.setVisibility(View.GONE);
                cancelPlayTimeout();
                autoSwitchCount = 0;
                mp.start();
            }
        });
        systemPlayer.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                loading.setVisibility(View.GONE);
                onSourceFailed();
                return true;
            }
        });
        try {
            systemPlayer.setVideoURI(Uri.parse(c.url));
            systemPlayer.start();
            armPlayTimeout();
        } catch (Exception e) {
            loading.setVisibility(View.GONE);
            onSourceFailed();
        }
    }

    private void playRelative(int step) {
        if (all.isEmpty()) {
            return;
        }
        int idx = currentIndex < 0 ? 0 : currentIndex + step;
        if (idx < 0) {
            idx = all.size() - 1;
        }
        if (idx >= all.size()) {
            idx = 0;
        }
        playChannel(idx);
        showChannelTip(all.get(idx));
    }

    private void showChannelTip(LiveChannel c) {
        numberHint.setText("CH " + c.number + "  " + c.name);
        numberHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitDigits);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (digits.length() == 0) {
                    numberHint.setVisibility(View.GONE);
                }
            }
        }, 1600);
        focusPlayingChannel();
        resetAutoHide();
    }

    /* ------------------------------------------------------------------ */
    /* 订阅源 / 解码 / 内核 切换（底部信息条）                              */
    /* ------------------------------------------------------------------ */

    private void chooseSource() {
        final List<LiveSource> list = LiveSource.list(prefs);
        if (list.isEmpty()) {
            Ui.toast(this, "还没有订阅源，请先添加");
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra(SettingsActivity.EXTRA_OPEN_SOURCES, true));
            return;
        }
        String[] labels = new String[list.size()];
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            LiveSource s = list.get(i);
            labels[i] = (TextUtils.isEmpty(s.name) ? LiveSource.guessName(s.url) : s.name)
                    + (currentSource != null && s.url.equals(currentSource.url) ? "（当前）" : "");
            if (currentSource != null && s.url.equals(currentSource.url)) {
                checked = i;
            }
        }
        pauseAutoHide();
        new AlertDialog.Builder(this)
                .setTitle("选择直播订阅源")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        LiveSource s = list.get(which);
                        prefs.setLiveDefaultSource(s.url);
                        loadChannels();
                    }
                })
                .setNegativeButton("管理订阅源", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(LiveActivity.this, SettingsActivity.class)
                                .putExtra(SettingsActivity.EXTRA_OPEN_SOURCES, true));
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        resetAutoHide();
                    }
                })
                .show();
    }

    /** 切换当前节目自己的源（一个节目合并了多条线路时可用） */
    private void chooseChannelSource() {
        final LiveChannel c = currentChannel();
        if (c == null) {
            Ui.toast(this, "请先选择频道");
            return;
        }
        if (c.urls.size() <= 1) {
            Ui.toast(this, "当前节目只有一个源");
            return;
        }
        String[] labels = new String[c.urls.size()];
        for (int i = 0; i < c.urls.size(); i++) {
            labels[i] = "源 " + (i + 1) + " · " + LiveSource.guessName(c.urls.get(i))
                    + (i == sourceIdx ? "（当前）" : "");
        }
        pauseAutoHide();
        new AlertDialog.Builder(this)
                .setTitle("切换节目源 · " + c.name)
                .setSingleChoiceItems(labels, sourceIdx, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        sourceIdx = which;
                        autoSwitchCount = 0;
                        playCurrentSource();
                        resetAutoHide();
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        resetAutoHide();
                    }
                })
                .show();
    }

    private void chooseDecode() {
        pauseAutoHide();
        new AlertDialog.Builder(this)
                .setTitle("直播解码方式")
                .setItems(DECODE_LABELS, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setLiveDecode(which);
                        refreshBars();
                        initPlayer();
                        if (currentIndex >= 0) {
                            playChannel(currentIndex);
                        }
                        Ui.toast(LiveActivity.this, "已切换为" + decodeLabel(which) + "解码");
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        resetAutoHide();
                    }
                })
                .show();
    }

    private void chooseKernel() {
        pauseAutoHide();
        new AlertDialog.Builder(this)
                .setTitle("直播播放内核")
                .setItems(KERNEL_LABELS, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setLiveKernel(which);
                        refreshBars();
                        initPlayer();
                        if (currentIndex >= 0) {
                            playChannel(currentIndex);
                        }
                        Ui.toast(LiveActivity.this, "已切换为" + (which == 1
                                ? "系统播放器，若黑屏/无声请切回 Media3" : "Media3"));
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        resetAutoHide();
                    }
                })
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* 数字换台                                                            */
    /* ------------------------------------------------------------------ */

    private boolean isDigit(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return true;
        }
        return keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9;
    }

    private int digitOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
    }

    private void commitChannelNumber() {
        if (digits.length() == 0) {
            return;
        }
        int n;
        try {
            n = Integer.parseInt(digits.toString());
        } catch (Exception e) {
            n = -1;
        }
        digits.setLength(0);
        if (n >= 1 && n <= all.size()) {
            playChannel(n - 1);
            numberHint.setText("CH " + n + "  " + all.get(n - 1).name);
            handler.postDelayed(hideNumberHint, 1200);
            focusPlayingChannel();
        } else {
            numberHint.setText(n <= 0 ? "请输入频道号" : "没有 " + n + " 频道");
            handler.postDelayed(hideNumberHint, 1200);
        }
    }

    private final Runnable hideNumberHint = new Runnable() {
        @Override
        public void run() {
            if (digits.length() == 0) {
                numberHint.setVisibility(View.GONE);
            }
        }
    };

    /* ------------------------------------------------------------------ */
    /* 面板 / 自动隐藏                                                      */
    /* ------------------------------------------------------------------ */

    private void showPanel(boolean show, boolean focus) {
        panelVisible = show;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            chromeVisible = true;
        }
        updateChrome();
        if (show && focus) {
            focusPlayingChannel();
        }
        resetAutoHide();
    }

    private void updateChrome() {
        // 频道面板展开时右下角信息足够，先收起右上角节目号，避免和面板挤在一起
        boolean badge = chromeVisible && !panelVisible;
        channelBadge.setVisibility(badge ? View.VISIBLE : View.GONE);
        bottomInfo.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
    }

    /** 面板 + 节目号 + 底部信息全部收起 */
    private void hideAll() {
        panelVisible = false;
        chromeVisible = false;
        panel.setVisibility(View.GONE);
        updateChrome();
        handler.removeCallbacks(autoHide);
        View root = findViewById(R.id.liveRoot);
        if (root != null) {
            root.requestFocus();
        }
    }

    private void resetAutoHide() {
        handler.removeCallbacks(autoHide);
        // 焦点停在下方工具栏时不自动收起
        if (focusInBottomBar()) {
            return;
        }
        if (panelVisible || chromeVisible) {
            handler.postDelayed(autoHide, AUTO_HIDE_MS);
        }
    }

    /** 当前焦点是否在下方工具栏里 */
    private boolean focusInBottomBar() {
        View f = getCurrentFocus();
        while (f != null) {
            if (f == bottomInfo) {
                return true;
            }
            android.view.ViewParent p = f.getParent();
            f = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /** 把焦点跳到下方工具栏（长按 OK / 遥控器菜单键） */
    private void focusBottomBar() {
        panelVisible = false;
        panel.setVisibility(View.GONE);
        chromeVisible = true;
        updateChrome();
        handler.removeCallbacks(autoHide);
        View btn = findViewById(R.id.liveBtnSource);
        if (btn != null) {
            btn.requestFocus();
        }
    }

    /** 各品牌电视、机顶盒、移动联通电信广电遥控器的「菜单」键 */
    private static boolean isMenuKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:              // 82 通用菜单
            case KeyEvent.KEYCODE_SETTINGS:          // 176 设置/菜单
            case KeyEvent.KEYCODE_INFO:              // 165 信息（电信/移动遥控常见）
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:  // 256
            case KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU: // 257
            case KeyEvent.KEYCODE_ASSIST:            // 219
            case KeyEvent.KEYCODE_APP_SWITCH:        // 187
            case KeyEvent.KEYCODE_GUIDE:             // 172 广电「指南」
            case KeyEvent.KEYCODE_F1:                // 131 部分机顶盒映射
            case KeyEvent.KEYCODE_PROG_RED:          // 183 IPTV 遥控器红键
                return true;
            default:
                return false;
        }
    }

    private void pauseAutoHide() {
        handler.removeCallbacks(autoHide);
    }

    private void focusPlayingChannel() {
        if (!panelVisible || channelAdapter == null) {
            return;
        }
        final List<LiveChannel> items = channelAdapter.items();
        int pos = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).number == playingNumber) {
                pos = i;
                break;
            }
        }
        if (pos < 0) {
            pos = 0;
        }
        final int target = pos;
        channelList.post(new Runnable() {
            @Override
            public void run() {
                channelList.scrollToPosition(target);
                channelList.post(new Runnable() {
                    @Override
                    public void run() {
                        RecyclerView.ViewHolder vh = channelList.findViewHolderForAdapterPosition(target);
                        if (vh != null) {
                            vh.itemView.requestFocus();
                        } else if (channelList.getChildCount() > 0) {
                            channelList.getChildAt(0).requestFocus();
                        }
                    }
                });
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 按键 / 触摸                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownHidden = !panelVisible && !chromeVisible;
            touchDownOutside = !insideChrome(ev.getX(), ev.getY());
            resetAutoHide();
        } else if (action == MotionEvent.ACTION_UP) {
            // 全部收起时轻点画面：直接呼出面板（不依赖 onClick，避免首次点击被系统吞掉）
            if (touchDownHidden && !panelVisible && !chromeVisible) {
                cancelTouchToChildren(ev);
                showPanel(true, true);
                return true;
            }
            // 面板展开时点面板/底栏以外的画面：收起面板
            if (panelVisible && touchDownOutside) {
                cancelTouchToChildren(ev);
                hideAll();
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 自己消费了这次点击，给视图树补一个 CANCEL，避免控件停在按下态 */
    private void cancelTouchToChildren(MotionEvent ev) {
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    /** 触点是否落在频道面板或底部工具栏上 */
    private boolean insideChrome(float x, float y) {
        if (panelVisible && (insideView(findViewById(R.id.groupPanel), x, y)
                || insideView(findViewById(R.id.channelPanel), x, y))) {
            return true;
        }
        return insideView(bottomInfo, x, y);
    }

    private static boolean insideView(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return x >= loc[0] && x <= loc[0] + v.getWidth()
                && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDigit(keyCode)) {
            if (digits.length() >= 4) {
                digits.setLength(0);
            }
            digits.append(digitOf(keyCode));
            numberHint.setText("切换频道  " + digits);
            numberHint.setVisibility(View.VISIBLE);
            handler.removeCallbacks(commitDigits);
            handler.postDelayed(commitDigits, 1500);
            resetAutoHide();
            return true;
        }
        // 各品牌电视/机顶盒遥控器的「菜单」键，统一跳到下方工具栏
        if (isMenuKey(keyCode)) {
            focusBottomBar();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (panelVisible || chromeVisible) {
                    hideAll();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 焦点已经在底部工具栏时，OK 交给按钮自己处理（线路/源/解码/内核/主页/设置）
                if (!panelVisible && !focusInBottomBar()) {
                    showPanel(true, true);
                    return true;
                }
                resetAutoHide();
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusInBottomBar()) {
                    showPanel(true, true);
                    return true;
                }
                if (!panelVisible) {
                    showPanel(true, true);
                    return true;
                }
                resetAutoHide();
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 焦点在底部工具栏时保留方向键给按钮切换焦点
                if (!panelVisible && !focusInBottomBar()) {
                    showPanel(true, true);
                    return true;
                }
                resetAutoHide();
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                playRelative(1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                playRelative(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * 触摸模式下第一次按键的 DOWN 会被系统吞掉（用于退出 touch mode），只能收到 UP；
     * 所以 DOWN/UP 都要处理，保证 OK 和方向键都是一次呼出频道面板。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isNavKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                navDownHandled = false;
                if (!panelVisible && !chromeVisible) {
                    navDownHandled = true;
                    showPanel(true, true);
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                // 触摸模式下第一次按键的 DOWN 会被系统吃掉（用于退出 touch mode），
                // 只剩 UP 能到达这里，补处理一次，避免要按两次才出面板
                boolean handled = navDownHandled;
                navDownHandled = false;
                if (!handled && !panelVisible && !chromeVisible) {
                    showPanel(true, true);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 能呼出频道面板的按键：OK/回车 + 四个方向键 */
    private static boolean isNavKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private void togglePlay() {
        if (prefs.liveKernel() == 1) {
            if (systemPlayer.isPlaying()) {
                systemPlayer.pause();
            } else {
                systemPlayer.start();
            }
            return;
        }
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    /* ------------------------------------------------------------------ */

    private void updateClock() {
        java.util.Date now = new java.util.Date();
        dateText.setText(dateFmt.format(now));
        timeText.setText(timeFmt.format(now));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBars();
        int sig = prefs.liveDecode() * 10 + prefs.liveKernel();
        if (sig != playerSig) {
            initPlayer();
            if (currentIndex >= 0) {
                playChannel(currentIndex);
            }
        }
        if (!prefs.activeServerId().equals(serverSig)
                || !prefs.liveSources().equals(sourcesSig) || currentSource == null) {
            loadChannels();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
            player.setPlayWhenReady(true);
        }
        if (systemPlayer != null) {
            systemPlayer.start();
        }
        resetAutoHide();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(clockTick);
        cancelPlayTimeout();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        if (systemPlayer != null && systemPlayer.isPlaying()) {
            systemPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (systemPlayer != null) {
            systemPlayer.stopPlayback();
        }
    }
}
