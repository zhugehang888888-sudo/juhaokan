package com.oriontv.plus;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int TAB_MOVIE = 0;
    private static final int TAB_TV = 1;
    private static final int TAB_ANIME = 2;
    private static final int TAB_SHOW = 3;
    private static final int TAB_REPLAY = 4;
    private static final int TAB_FAVORITE = 5;

    private static final String ALL = "全部";

    private static class Opt {
        final String label;
        final String value;

        Opt(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static Opt[] opts(String... labels) {
        Opt[] arr = new Opt[labels.length];
        for (int i = 0; i < labels.length; i++) {
            arr[i] = new Opt(labels[i], labels[i]);
        }
        return arr;
    }

    private static final Opt[] MOVIE_PRIMARY = {
            new Opt("全部", ALL), new Opt("热门电影", "热门"), new Opt("最新电影", "最新"),
            new Opt("豆瓣高分", "豆瓣高分"), new Opt("冷门佳片", "冷门佳片")
    };
    private static final Opt[] TV_PRIMARY = {new Opt("全部", ALL), new Opt("最近热门", "最近热门")};
    private static final Opt[] SHOW_PRIMARY = {new Opt("全部", ALL), new Opt("最近热门", "最近热门")};
    private static final Opt[] ANIME_PRIMARY = {
            new Opt("每日放送", "每日放送"), new Opt("番剧", "番剧"), new Opt("剧场版", "剧场版")
    };

    private static final Opt[] TYPE_MOVIE = opts(
            ALL, "喜剧", "爱情", "动作", "科幻", "悬疑", "犯罪", "惊悚", "冒险", "音乐",
            "历史", "奇幻", "恐怖", "战争", "传记", "歌舞", "武侠", "灾难", "西部", "纪录片", "短片");
    private static final Opt[] TYPE_TV = opts(
            ALL, "古装", "家庭", "喜剧", "爱情", "动作", "科幻", "悬疑", "犯罪", "惊悚",
            "冒险", "历史", "奇幻", "战争", "传记", "武侠", "纪录片");
    private static final Opt[] TYPE_SHOW = opts(ALL, "真人秀", "脱口秀", "访谈", "选秀");
    private static final Opt[] REGION = opts(
            ALL, "华语", "欧美", "韩国", "日本", "中国大陆", "美国", "中国香港", "中国台湾",
            "英国", "法国", "德国", "意大利", "西班牙", "印度", "泰国", "俄罗斯", "加拿大",
            "澳大利亚", "爱尔兰", "瑞典", "巴西", "丹麦");
    private static final Opt[] YEAR = opts(
            ALL, "2020年代", "2025", "2024", "2023", "2022", "2021", "2020", "2019",
            "2010年代", "2000年代", "90年代", "80年代", "70年代", "60年代", "更早");
    private static final Opt[] SORT = {
            new Opt("综合排序", "T"), new Opt("近期热度", "U"), new Opt("高分优先", "S")
    };

    private static final String[] WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] WEEK_EN = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    private static final int[] NAV_IDS = {
            R.id.navMovie, R.id.navTv, R.id.navAnime,
            R.id.navShow, R.id.navReplay, R.id.navFavorite
    };

    private final Prefs prefs = App.get().prefs();
    private final Handler clockHandler = new Handler(Looper.getMainLooper());

    private PosterAdapter adapter;
    private RecyclerView grid;
    private LinearLayout chipRow;
    private LinearLayout subRow;
    private View chipScroll;
    private View subScroll;
    private View filterOverlay;
    private LinearLayout filterPanel;
    private LinearLayout filterOptions;
    private ScrollView filterScrollView;
    private ProgressBar loading;
    private TextView emptyText;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView clockTime;
    private TextView clockDate;

    private int tab = TAB_MOVIE;
    private String primary = ALL;
    private String fType = ALL;
    private String fRegion = ALL;
    private String fYear = ALL;
    private String fSort = "T";
    private int weekday;
    private int filterOpen = -1;

    private int page = 0;
    private boolean hasMore = true;
    private boolean loadingMore = false;
    private int requestSeq = 0;
    /** 收藏页批量管理模式 */
    private boolean favSelectMode = false;
    private int autoFill = 0;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            tickClock();
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        grid = findViewById(R.id.grid);
        chipRow = findViewById(R.id.chipRow);
        subRow = findViewById(R.id.subRow);
        chipScroll = findViewById(R.id.chipScroll);
        subScroll = findViewById(R.id.subScroll);
        filterOverlay = findViewById(R.id.filterOverlay);
        filterPanel = findViewById(R.id.filterPanel);
        filterOptions = findViewById(R.id.filterOptions);
        filterScrollView = findViewById(R.id.filterScrollView);
        loading = findViewById(R.id.loading);
        emptyText = findViewById(R.id.emptyText);
        pageTitle = findViewById(R.id.pageTitle);
        pageSubtitle = findViewById(R.id.pageSubtitle);
        clockTime = findViewById(R.id.clockTime);
        clockDate = findViewById(R.id.clockDate);

        filterOverlay.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    moveDropdownFocus(1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    moveDropdownFocus(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    closeDropdown(true);
                    return true;
                }
                return false;
            }
        });

        Calendar c = Calendar.getInstance();
        int dow = c.get(Calendar.DAY_OF_WEEK);
        weekday = dow == Calendar.SUNDAY ? 6 : dow - 2;

        adapter = new PosterAdapter(new PosterAdapter.Listener() {
            @Override
            public void onCard(CardItem item, int position) {
                openCard(item);
            }

            @Override
            public void onCheckedChanged(int count) {
                if (favSelectMode) {
                    buildFavChips();
                }
            }
        });
        grid.setLayoutManager(new GridLayoutManager(this, 6));
        grid.setAdapter(adapter);
        // 遥控器：收藏页第一行按「上」回到操作按钮（选择/清空、取消/取消收藏）
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN
                        || keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    return false;
                }
                if (tab != TAB_FAVORITE || chipRow.getChildCount() == 0) {
                    return false;
                }
                View focus = getCurrentFocus();
                RecyclerView.ViewHolder vh = focus == null
                        ? null : grid.findContainingViewHolder(focus);
                if (vh == null) {
                    return false;
                }
                int span = 1;
                RecyclerView.LayoutManager lm = grid.getLayoutManager();
                if (lm instanceof GridLayoutManager) {
                    span = ((GridLayoutManager) lm).getSpanCount();
                }
                if (vh.getAdapterPosition() < span) {
                    chipRow.getChildAt(0).requestFocus();
                    return true;
                }
                return false;
            }
        });
        applyGridSpans();
        grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft) {
                    setGridSpans();
                }
            }
        });
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loadingMore || !hasMore || !prefs.configured()) {
                    return;
                }
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (lm instanceof GridLayoutManager
                        && ((GridLayoutManager) lm).findLastVisibleItemPosition()
                        >= adapter.getItemCount() - 7) {
                    loadingMore = true;
                    loadPage(false);
                }
            }
        });

        for (int i = 0; i < NAV_IDS.length; i++) {
            final int which = i;
            findViewById(NAV_IDS[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(which);
                }
            });
        }
        findViewById(R.id.navSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });
        findViewById(R.id.navLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LiveActivity.class));
            }
        });
        findViewById(R.id.navSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        applyTab();
        findViewById(R.id.navSearch).requestFocus();
        applyRailFill();

        emptyText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (BuildConfig.BUILT_IN_SERVER && !App.get().api().authed()) {
                    autoConnect();
                }
            }
        });

        if (!BuildConfig.BUILT_IN_SERVER && !prefs.configured()) {
            startActivity(new Intent(this, SetupActivity.class));
        } else if (savedInstanceState == null && prefs.homeLive()
                && !getIntent().getBooleanExtra("skipLive", false)) {
            // 首页内容设为「直播」时，打开 App 直接进直播
            startActivity(new Intent(this, LiveActivity.class));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 时钟                                                                */
    /* ------------------------------------------------------------------ */

    /** 根据网格实际宽度自适应列数：电视 6 列，手机横屏 5~6 列，竖屏 2 列 */
    private void applyGridSpans() {
        if (grid == null) {
            return;
        }
        if (grid.getWidth() > 0) {
            setGridSpans();
            return;
        }
        grid.post(new Runnable() {
            @Override
            public void run() {
                setGridSpans();
            }
        });
    }

    private void setGridSpans() {
        if (grid == null || grid.getWidth() <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int wDp = (int) (grid.getWidth() / density);
        int cols = Math.max(2, Math.min(6, wDp / 118));
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            ((GridLayoutManager) lm).setSpanCount(cols);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyGridSpans();
        applyRailFill();
    }

    /**
     * 左侧菜单（搜索 ~ 设置共 8 项）等分铺满可用高度，
     * 竖屏/横屏都保持一致的平均间距，不会出现某项被挤掉或间距忽大忽小。
     */
    private void applyRailFill() {
        LinearLayout navList = findViewById(R.id.navList);
        if (navList == null) {
            return;
        }
        for (int i = 0; i < navList.getChildCount(); i++) {
            View child = navList.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.height = 0;
            lp.weight = 1f;
            child.setLayoutParams(lp);
        }
    }

    private void tickClock() {
        Date now = new Date();
        clockTime.setText(timeFmt.format(now));
        clockDate.setText(dateFmt.format(now));
    }

    @Override
    protected void onResume() {
        super.onResume();
        tickClock();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.postDelayed(clockTick, 1000);
        if (BuildConfig.BUILT_IN_SERVER) {
            if (App.get().api().authed()) {
                loadFirst();
            } else {
                autoConnect();
            }
        } else if (prefs.configured()) {
            loadFirst();
        } else {
            adapter.setItems(new ArrayList<CardItem>());
            loading.setVisibility(View.GONE);
            emptyText.setText("请先在设置中登录服务器");
            emptyText.setVisibility(View.VISIBLE);
        }
    }

    /** 内置版：启动自动登录，无需手输账号 */
    private void autoConnect() {
        loading.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        emptyText.setFocusable(false);
        clockHandler.removeCallbacks(connectRetry);
        App.get().api().login(prefs.server(), prefs.user(), prefs.pass(), new Api.Cb<Boolean>() {
            @Override
            public void done(Boolean ok, String err) {
                if (isFinishing()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                if (ok != null && ok) {
                    loadFirst();
                    return;
                }
                adapter.setItems(new ArrayList<CardItem>());
                emptyText.setText("连接服务器失败：" + err + "\n按 OK 重试（8 秒后自动重试）");
                emptyText.setFocusable(true);
                emptyText.setBackgroundResource(R.drawable.bg_btn);
                emptyText.setPadding(Ui.dp(MainActivity.this, 26), Ui.dp(MainActivity.this, 14),
                        Ui.dp(MainActivity.this, 26), Ui.dp(MainActivity.this, 14));
                emptyText.setVisibility(View.VISIBLE);
                emptyText.requestFocus();
                clockHandler.postDelayed(connectRetry, 8000);
            }
        });
    }

    private final Runnable connectRetry = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !App.get().api().authed()) {
                autoConnect();
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.removeCallbacks(connectRetry);
    }

    /* ------------------------------------------------------------------ */
    /* 导航                                                                */
    /* ------------------------------------------------------------------ */

    private void selectTab(int which) {
        if (tab == which) {
            if (favSelectMode) {
                exitFavSelect(true);
            }
            loadFirst();
            return;
        }
        if (favSelectMode) {
            exitFavSelect(false);
        }
        tab = which;
        applyTab();
        loadFirst();
        View nav = findViewById(NAV_IDS[which]);
        if (nav != null) {
            nav.requestFocus();
        }
    }

    private void applyTab() {
        primary = (tab == TAB_ANIME) ? "每日放送" : ALL;
        fType = ALL;
        fRegion = ALL;
        fYear = ALL;
        fSort = "T";
        filterOpen = -1;
        closeDropdown(false);

        pageTitle.setText(titleOf(tab));
        pageSubtitle.setText(subtitleOf(tab));

        for (int i = 0; i < NAV_IDS.length; i++) {
            View v = findViewById(NAV_IDS[i]);
            // 不再给当前栏目加高亮底色/边框，避免看起来像“默认被选中”
            v.setSelected(false);
            v.setBackgroundResource(R.drawable.bg_rail_item);
        }

        buildPrimaryChips();
        buildSecondary();
    }

    private int titleOf(int t) {
        switch (t) {
            case TAB_TV:
                return R.string.nav_tv;
            case TAB_ANIME:
                return R.string.nav_anime;
            case TAB_SHOW:
                return R.string.nav_show;
            case TAB_REPLAY:
                return R.string.nav_continue;
            case TAB_FAVORITE:
                return R.string.nav_favorite;
            default:
                return R.string.nav_movie;
        }
    }

    private String subtitleOf(int t) {
        switch (t) {
            case TAB_ANIME:
                return "来自 Bangumi 番组计划的精选内容";
            case TAB_REPLAY:
                return "继续上次看到的位置";
            case TAB_FAVORITE:
                return "我收藏的影视";
            case TAB_MOVIE:
            case TAB_TV:
            case TAB_SHOW:
                return "来自豆瓣的精选内容";
            default:
                return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* 分类 / 筛选                                                         */
    /* ------------------------------------------------------------------ */

    private Opt[] primaryOptions() {
        switch (tab) {
            case TAB_TV:
                return TV_PRIMARY;
            case TAB_ANIME:
                return ANIME_PRIMARY;
            case TAB_SHOW:
                return SHOW_PRIMARY;
            default:
                return MOVIE_PRIMARY;
        }
    }

    private void buildPrimaryChips() {
        chipRow.removeAllViews();
        if (tab == TAB_REPLAY) {
            chipScroll.setVisibility(View.GONE);
            return;
        }
        if (tab == TAB_FAVORITE) {
            chipScroll.setVisibility(View.VISIBLE);
            buildFavChips();
            return;
        }
        chipScroll.setVisibility(View.VISIBLE);
        Opt[] list = primaryOptions();
        for (final Opt o : list) {
            TextView chip = makeTab(o.label, o.value.equals(primary));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (o.value.equals(primary)) {
                        return;
                    }
                    primary = o.value;
                    fType = ALL;
                    fRegion = ALL;
                    fYear = ALL;
                    fSort = "T";
                    filterOpen = -1;
                    closeDropdown(false);
                    buildPrimaryChips();
                    buildSecondary();
                    loadFirst();
                    focusActiveTab();
                }
            });
            chipRow.addView(chip);
        }
        if (chipRow.getChildCount() > 0) {
            int firstId = chipRow.getChildAt(0).getId();
            for (int i = 0; i < NAV_IDS.length; i++) {
                findViewById(NAV_IDS[i]).setNextFocusRightId(firstId);
            }
        }
    }

    private void focusActiveTab() {
        Opt[] list = primaryOptions();
        for (int i = 0; i < list.length; i++) {
            if (list[i].value.equals(primary)) {
                View v = chipRow.getChildAt(i);
                if (v != null) {
                    v.requestFocus();
                }
                return;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 收藏：批量取消 / 清空（与网页版一致）                               */
    /* ------------------------------------------------------------------ */

    private void buildFavChips() {
        chipRow.removeAllViews();
        int total = adapter == null ? 0 : adapter.getItemCount();
        int checked = adapter == null ? 0 : adapter.checkedCount();
        if (!favSelectMode) {
            pageSubtitle.setText(total > 0
                    ? "我收藏的影视 · 共 " + total + " 个"
                    : "我收藏的影视");
            if (total == 0) {
                return;
            }
            TextView select = makeTab("选择", false);
            select.setNextFocusDownId(R.id.grid);
            select.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    enterFavSelect();
                }
            });
            chipRow.addView(select);

            TextView clear = makeTab("清空收藏", false);
            clear.setNextFocusDownId(R.id.grid);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmClearFavorites();
                }
            });
            chipRow.addView(clear);
            linkNavToChips();
            return;
        }

        pageSubtitle.setText("已选 " + checked + " 个 · OK 勾选");
        TextView cancel = makeTab("取消", false);
        cancel.setNextFocusDownId(R.id.grid);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitFavSelect(true);
            }
        });
        chipRow.addView(cancel);

        TextView del = makeTab(checked > 0 ? "取消收藏 (" + checked + ")" : "取消收藏", checked > 0);
        del.setNextFocusDownId(R.id.grid);
        del.setEnabled(checked > 0);
        del.setAlpha(checked > 0 ? 1f : 0.45f);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelectedFavorites();
            }
        });
        chipRow.addView(del);
        linkNavToChips();
    }

    /** 让左侧栏目按「右键」能进入当前操作区（遥控器场景） */
    private void linkNavToChips() {
        if (chipRow.getChildCount() == 0) {
            return;
        }
        int firstId = chipRow.getChildAt(0).getId();
        for (int i = 0; i < NAV_IDS.length; i++) {
            View nav = findViewById(NAV_IDS[i]);
            if (nav != null) {
                nav.setNextFocusRightId(firstId);
            }
        }
    }

    private void enterFavSelect() {
        if (adapter == null || adapter.getItemCount() == 0) {
            return;
        }
        favSelectMode = true;
        adapter.setSelectionMode(true);
        buildFavChips();
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                grid.scrollToPosition(0);
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
                if (vh != null) {
                    vh.itemView.requestFocus();
                } else {
                    grid.requestFocus();
                }
            }
        }, 80);
    }

    private void exitFavSelect(boolean rebuildChips) {
        favSelectMode = false;
        if (adapter != null) {
            adapter.setSelectionMode(false);
        }
        if (rebuildChips) {
            buildFavChips();
            if (chipRow.getChildCount() > 0) {
                chipRow.getChildAt(0).requestFocus();
            }
        }
    }

    private void deleteSelectedFavorites() {
        if (adapter == null) {
            return;
        }
        List<CardItem> checked = adapter.checkedItems();
        if (checked.isEmpty()) {
            Ui.toast(this, "请先勾选要取消的收藏");
            return;
        }
        int n = 0;
        for (CardItem c : checked) {
            if (c.source == null || c.source.isEmpty() || c.id == null || c.id.isEmpty()) {
                continue;
            }
            App.get().api().removeFavorite(c.source + "+" + c.id);
            n++;
        }
        Ui.toast(this, "已取消 " + n + " 个收藏");
        exitFavSelect(true);
        loadFirst();
        focusFavNav();
    }

    private void focusFavNav() {
        View nav = findViewById(NAV_IDS[TAB_FAVORITE]);
        if (nav != null) {
            nav.requestFocus();
        }
    }

    private void confirmClearFavorites() {
        if (adapter == null) {
            return;
        }
        final List<CardItem> all = new ArrayList<>(adapter.items());
        if (all.isEmpty()) {
            return;
        }
        Ui.confirm(this, "清空收藏", "将取消全部 " + all.size()
                + " 个收藏，此操作不可恢复。", "清空", new Runnable() {
            @Override
            public void run() {
                int n = 0;
                for (CardItem c : all) {
                    if (c.source == null || c.source.isEmpty()
                            || c.id == null || c.id.isEmpty()) {
                        continue;
                    }
                    App.get().api().removeFavorite(c.source + "+" + c.id);
                    n++;
                }
                Ui.toast(MainActivity.this, "已清空 " + n + " 个收藏");
                exitFavSelect(true);
                loadFirst();
                focusFavNav();
            }
        });
    }

    private boolean filtersAvailable() {
        if (tab == TAB_REPLAY || tab == TAB_FAVORITE) {
            return false;
        }
        if (tab == TAB_ANIME) {
            return !"每日放送".equals(primary);
        }
        return ALL.equals(primary);
    }

    private void buildSecondary() {
        subRow.removeAllViews();

        if (tab == TAB_ANIME && "每日放送".equals(primary)) {
            subScroll.setVisibility(View.VISIBLE);
            for (int i = 0; i < WEEK.length; i++) {
                final int idx = i;
                TextView chip = makeChip(WEEK[i], i == weekday);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (weekday != idx) {
                            weekday = idx;
                            buildSecondary();
                            loadFirst();
                            View target = subRow.getChildAt(idx);
                            if (target != null) {
                                target.requestFocus();
                            }
                        }
                    }
                });
                subRow.addView(chip);
            }
            return;
        }

        if (!filtersAvailable()) {
            subScroll.setVisibility(View.GONE);
            return;
        }

        subScroll.setVisibility(View.VISIBLE);
        if (tab == TAB_ANIME) {
            addFilterButton(1);
            addFilterButton(2);
            addFilterButton(3);
        } else {
            addFilterButton(0);
            addFilterButton(1);
            addFilterButton(2);
            addFilterButton(3);
        }
    }

    private void addFilterButton(final int which) {
        String name;
        if (which == 0) {
            name = "类型";
        } else if (which == 1) {
            name = "地区";
        } else if (which == 2) {
            name = "年代";
        } else {
            name = "排序";
        }
        String value = which == 3 ? sortLabel(fSort) : valueOf(which);
        TextView chip = makeSelect(name + "  " + value, filterOpen == which);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (filterOpen == which) {
                    closeDropdown(true);
                    return;
                }
                filterOpen = which;
                buildSecondary();
                openFilter(which);
            }
        });
        subRow.addView(chip);
    }

    private int childIndexFor(int which) {
        return tab == TAB_ANIME ? which - 1 : which;
    }

    private String sortLabel(String value) {
        for (Opt o : SORT) {
            if (o.value.equals(value)) {
                return o.label;
            }
        }
        return "综合排序";
    }

    private Opt[] optionsOf(int which) {
        if (which == 0) {
            if (tab == TAB_SHOW) {
                return TYPE_SHOW;
            }
            if (tab == TAB_TV) {
                return TYPE_TV;
            }
            return TYPE_MOVIE;
        }
        if (which == 1) {
            return REGION;
        }
        if (which == 2) {
            return YEAR;
        }
        return SORT;
    }

    private String valueOf(int which) {
        if (which == 0) {
            return fType;
        }
        if (which == 1) {
            return fRegion;
        }
        if (which == 2) {
            return fYear;
        }
        return fSort;
    }

    private void openFilter(final int which) {
        filterOptions.removeAllViews();
        final Opt[] list = optionsOf(which);
        final String current = valueOf(which);
        int selectedIndex = 0;
        for (int i = 0; i < list.length; i++) {
            final Opt o = list[i];
            boolean selected = o.value.equals(current);
            if (selected) {
                selectedIndex = i;
            }
            TextView row = new TextView(this);
            row.setText(selected ? "✓  " + o.label : "     " + o.label);
            row.setTextSize(14);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 16), 0);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setSelected(selected);
            row.setBackgroundResource(R.drawable.bg_dropdown_row);
            row.setTextColor(getResources().getColorStateList(R.color.dropdown_text));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (which == 0) {
                        fType = o.value;
                    } else if (which == 1) {
                        fRegion = o.value;
                    } else if (which == 2) {
                        fYear = o.value;
                    } else {
                        fSort = o.value;
                    }
                    closeDropdown(false);
                    if (tab == TAB_MOVIE || tab == TAB_TV || tab == TAB_SHOW) {
                        if (!ALL.equals(primary)) {
                            primary = ALL;
                            buildPrimaryChips();
                        }
                    }
                    buildSecondary();
                    loadFirst();
                    View btn = subRow.getChildAt(childIndexFor(which));
                    if (btn != null) {
                        btn.requestFocus();
                    }
                }
            });
            filterOptions.addView(row);
        }

        filterPanel.setVisibility(View.VISIBLE);
        filterOverlay.setVisibility(View.VISIBLE);
        filterOverlay.setAlpha(0f);
        filterOverlay.animate().alpha(1f).setDuration(120).start();

        final View anchor = subRow.getChildAt(childIndexFor(which));
        final int target = selectedIndex;
        filterPanel.post(new Runnable() {
            @Override
            public void run() {
                layoutDropdown(anchor);
                if (filterOptions.getChildCount() > target) {
                    filterOptions.getChildAt(target).requestFocus();
                }
            }
        });
    }

    private void layoutDropdown(View anchor) {
        if (anchor == null) {
            return;
        }
        int[] a = new int[2];
        int[] b = new int[2];
        anchor.getLocationInWindow(a);
        filterOverlay.getLocationInWindow(b);

        int rowH = Ui.dp(this, 44);
        int rows = Math.min(filterOptions.getChildCount(), 7);
        int panelH = rows * rowH + Ui.dp(this, 14);
        int panelW = Math.max(anchor.getWidth(), Ui.dp(this, 210));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) filterPanel.getLayoutParams();
        lp.width = panelW;
        lp.height = panelH;
        lp.leftMargin = Math.max(Ui.dp(this, 8), a[0] - b[0]);
        int top = a[1] - b[1] + anchor.getHeight() + Ui.dp(this, 6);
        int maxTop = filterOverlay.getHeight() - panelH - Ui.dp(this, 8);
        lp.topMargin = Math.min(top, Math.max(Ui.dp(this, 8), maxTop));
        filterPanel.setLayoutParams(lp);

        LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) filterScrollView.getLayoutParams();
        sp.height = panelH - Ui.dp(this, 14);
        filterScrollView.setLayoutParams(sp);
    }

    private void moveDropdownFocus(int delta) {
        if (filterOptions.getChildCount() == 0) {
            return;
        }
        View focused = getCurrentFocus();
        int index = filterOptions.indexOfChild(focused);
        if (index < 0) {
            index = 0;
        } else {
            index = Math.max(0, Math.min(filterOptions.getChildCount() - 1, index + delta));
        }
        filterOptions.getChildAt(index).requestFocus();
    }

    private void closeDropdown(boolean returnFocus) {
        int which = filterOpen;
        filterOpen = -1;
        if (filterOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        filterOverlay.setVisibility(View.GONE);
        if (returnFocus) {
            buildSecondary();
            View btn = which >= 0 ? subRow.getChildAt(childIndexFor(which)) : null;
            if (btn != null) {
                btn.requestFocus();
            }
        }
    }

    private TextView makeChip(String text, boolean selected) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(14);
        chip.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setSelected(selected);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        chip.setTextColor(getResources().getColorStateList(
                selected ? R.color.chip_text_sel : R.color.chip_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 10));
        chip.setLayoutParams(lp);
        return chip;
    }

    /** 一级分类页签：选中为绿色文字 + 底部指示条 */
    private TextView makeTab(String text, boolean selected) {
        TextView tab = new TextView(this);
        tab.setId(View.generateViewId());
        tab.setText(text);
        tab.setTextSize(15);
        tab.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 9));
        tab.setFocusable(true);
        // 触屏/鼠标单击即可生效；遥控器仍可通过 focusable 获取焦点
        tab.setFocusableInTouchMode(false);
        tab.setSelected(selected);
        tab.setBackgroundResource(selected ? R.drawable.bg_tab_sel : R.drawable.bg_tab_focus);
        if (selected) {
            tab.setTextColor(getResources().getColor(R.color.accent));
        } else {
            tab.setTextColor(getResources().getColorStateList(R.color.tab_text));
        }
        tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 24));
        tab.setLayoutParams(lp);
        tab.setNextFocusUpId(NAV_IDS[MainActivity.this.tab]);
        tab.setNextFocusLeftId(NAV_IDS[MainActivity.this.tab]);
        return tab;
    }

    /** 筛选下拉按钮：类型 / 地区 / 年代 / 排序 */
    private TextView makeSelect(String text, boolean active) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setBackgroundResource(active ? R.drawable.bg_select_active : R.drawable.bg_select);
        v.setTextColor(getResources().getColorStateList(
                active ? R.color.select_text_active : R.color.select_text));
        android.graphics.drawable.Drawable arrow = getResources()
                .getDrawable(R.drawable.ic_arrow_down, getTheme()).mutate();
        v.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null);
        v.setCompoundDrawablePadding(Ui.dp(this, 7));
        v.setCompoundDrawableTintList(active
                ? android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.accent))
                : getResources().getColorStateList(R.color.select_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 10));
        v.setLayoutParams(lp);
        return v;
    }

    @Override
    public void onBackPressed() {
        if (filterOpen >= 0) {
            closeDropdown(true);
            return;
        }
        super.onBackPressed();
    }

    /* ------------------------------------------------------------------ */
    /* 数据加载                                                            */
    /* ------------------------------------------------------------------ */

    private void loadFirst() {
        requestSeq++;
        if (!prefs.configured()) {
            loading.setVisibility(View.GONE);
            adapter.setItems(new ArrayList<CardItem>());
            emptyText.setText("请先在设置中配置服务器并登录");
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        page = 0;
        hasMore = true;
        loadingMore = false;
        autoFill = 0;
        emptyText.setBackground(null);
        emptyText.setPadding(0, 0, 0, 0);
        emptyText.setFocusable(false);
        adapter.setItems(new ArrayList<CardItem>());
        loading.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        loadPage(true);
    }

    private void loadPage(final boolean first) {
        final int seq = ++requestSeq;
        loadingMore = true;
        if (!first) {
            page++;
        }
        final int offset = page * 25;

        if (tab == TAB_REPLAY) {
            hasMore = false;
            loadRecords(seq);
            return;
        }
        if (tab == TAB_FAVORITE) {
            hasMore = false;
            loadFavorites(seq);
            return;
        }
        if (tab == TAB_ANIME && "每日放送".equals(primary)) {
            hasMore = false;
            App.get().api().bangumiCalendar(WEEK_EN[weekday], new Api.Cb<List<VideoItem>>() {
                @Override
                public void done(List<VideoItem> data, String err) {
                    onList(seq, first, data, err);
                }
            });
            return;
        }

        final Api.Cb<List<VideoItem>> cb = new Api.Cb<List<VideoItem>>() {
            @Override
            public void done(List<VideoItem> data, String err) {
                onList(seq, first, data, err);
            }
        };

        String kind;
        String category;
        String format;
        if (tab == TAB_MOVIE) {
            if (!ALL.equals(primary)) {
                App.get().api().douban("movie", primary, offset, cb);
                return;
            }
            kind = "movie";
            category = blank(fType);
            format = "";
        } else if (tab == TAB_TV) {
            if (!ALL.equals(primary)) {
                App.get().api().recommends("tv", "", "电视剧", blank(fRegion), blank(fYear),
                        "", "U", "", offset, cb);
                return;
            }
            kind = "tv";
            category = blank(fType);
            format = "电视剧";
        } else if (tab == TAB_SHOW) {
            if (!ALL.equals(primary)) {
                App.get().api().recommends("tv", "", "综艺", blank(fRegion), blank(fYear),
                        "", "U", "", offset, cb);
                return;
            }
            kind = "tv";
            category = blank(fType);
            format = "综艺";
        } else {
            if ("番剧".equals(primary)) {
                kind = "tv";
                category = "动画";
                format = "电视剧";
            } else {
                kind = "movie";
                category = "动画";
                format = "";
            }
        }
        App.get().api().recommends(kind, category, format, blank(fRegion), blank(fYear),
                "", fSort, "", offset, cb);
    }

    private static String blank(String v) {
        return v == null || ALL.equals(v) ? "" : v;
    }

    private void onList(int seq, boolean first, List<VideoItem> data, String err) {
        if (seq != requestSeq) {
            return;
        }
        loadingMore = false;
        if (first) {
            loading.setVisibility(View.GONE);
        }
        if (err != null) {
            if (first) {
                emptyText.setText(err);
                emptyText.setVisibility(View.VISIBLE);
            }
            hasMore = false;
            return;
        }
        List<CardItem> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CardItem c : adapter.items()) {
            seen.add(cardKey(c));
        }
        if (data != null) {
            for (VideoItem v : data) {
                CardItem c = CardItem.of(v);
                if (seen.add(cardKey(c))) {
                    cards.add(c);
                }
            }
        }
        if (first) {
            adapter.setItems(cards);
            grid.scrollToPosition(0);
        } else {
            adapter.addItems(cards);
        }
        hasMore = data != null && !data.isEmpty() && adapter.getItemCount() < 200;
        if (first && adapter.getItemCount() == 0) {
            emptyText.setText(emptyHint());
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
        if (first && hasMore && autoFill < 4 && adapter.getItemCount() < 12) {
            autoFill++;
            loadingMore = true;
            clockHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadPage(false);
                }
            }, 200);
        }
    }

    private static String cardKey(CardItem c) {
        return c.title + "|" + c.id;
    }

    private String emptyHint() {
        if (tab == TAB_REPLAY) {
            return "还没有回看记录";
        }
        if (tab == TAB_FAVORITE) {
            return "还没有收藏";
        }
        if (tab == TAB_ANIME) {
            return "今天没有放送内容，换个星期看看";
        }
        return "暂无内容，换个筛选条件试试";
    }

    private void loadRecords(final int seq) {
        App.get().api().playRecords(new Api.Cb<JSONObject>() {
            @Override
            public void done(JSONObject data, String err) {
                if (seq != requestSeq) {
                    return;
                }
                loading.setVisibility(View.GONE);
                loadingMore = false;
                if (err != null) {
                    emptyText.setText(err);
                    emptyText.setVisibility(View.VISIBLE);
                    return;
                }
                List<CardItem> cards = new ArrayList<>();
                if (data != null) {
                    List<String> keys = new ArrayList<>();
                    Iterator<String> it = data.keys();
                    while (it.hasNext()) {
                        String key = it.next();
                        if (data.optJSONObject(key) != null) {
                            keys.add(key);
                        }
                    }
                    Collections.sort(keys, new Comparator<String>() {
                        @Override
                        public int compare(String a, String b) {
                            JSONObject oa = data.optJSONObject(a);
                            JSONObject ob = data.optJSONObject(b);
                            long ta = oa == null ? 0 : oa.optLong("save_time", 0);
                            long tb = ob == null ? 0 : ob.optLong("save_time", 0);
                            return Long.compare(tb, ta);
                        }
                    });
                    for (String key : keys) {
                        JSONObject rec = data.optJSONObject(key);
                        if (rec == null) {
                            continue;
                        }
                        CardItem c = new CardItem();
                        c.title = rec.optString("title", "");
                        c.poster = rec.optString("cover", "");
                        int idx = rec.optInt("index", 1);
                        int total = rec.optInt("total_episodes", 1);
                        c.sub = "看到第" + idx + "集 · " + Ui.time(rec.optLong("play_time", 0) * 1000);
                        c.badge = total > 1 ? (idx + "/" + total) : "";
                        String[] parts = key.split("\\+");
                        if (parts.length == 2) {
                            c.source = parts[0];
                            c.id = parts[1];
                        }
                        c.searchTitle = rec.optString("search_title", rec.optString("title", ""));
                        c.year = rec.optString("year", "");
                        c.index = idx - 1;
                        c.playTime = rec.optInt("play_time", 0);
                        c.resume = !c.source.isEmpty() && !c.id.isEmpty();
                        cards.add(c);
                    }
                }
                adapter.setItems(cards);
                if (cards.isEmpty()) {
                    emptyText.setText("还没有回看记录");
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    emptyText.setVisibility(View.GONE);
                }
            }
        });
    }

    private void loadFavorites(final int seq) {
        App.get().api().favorites(new Api.Cb<JSONObject>() {
            @Override
            public void done(JSONObject data, String err) {
                if (seq != requestSeq) {
                    return;
                }
                loading.setVisibility(View.GONE);
                loadingMore = false;
                if (err != null) {
                    emptyText.setText(err);
                    emptyText.setVisibility(View.VISIBLE);
                    return;
                }
                List<CardItem> cards = new ArrayList<>();
                if (data != null) {
                    Iterator<String> it = data.keys();
                    while (it.hasNext()) {
                        String key = it.next();
                        JSONObject fav = data.optJSONObject(key);
                        if (fav == null) {
                            continue;
                        }
                        CardItem c = new CardItem();
                        c.title = fav.optString("title", "");
                        c.poster = fav.optString("cover", fav.optString("poster", ""));
                        c.sub = fav.optString("source_name", "");
                        c.badge = fav.optString("year", "");
                        c.searchTitle = fav.optString("search_title", c.title);
                        c.year = fav.optString("year", "");
                        String[] parts = key.split("\\+");
                        if (parts.length == 2) {
                            c.source = parts[0];
                            c.id = parts[1];
                        }
                        cards.add(c);
                    }
                }
                adapter.setItems(cards);
                if (tab == TAB_FAVORITE) {
                    buildFavChips();
                }
                if (cards.isEmpty()) {
                    emptyText.setText("还没有收藏");
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    emptyText.setVisibility(View.GONE);
                }
            }
        });
    }

    private void openCard(CardItem item) {
        Intent i = new Intent(this, DetailActivity.class);
        if (item.source != null && !item.source.isEmpty() && item.id != null && !item.id.isEmpty()) {
            i.putExtra(DetailActivity.EXTRA_SOURCE, item.source);
            i.putExtra(DetailActivity.EXTRA_ID, item.id);
        }
        i.putExtra(DetailActivity.EXTRA_TITLE, item.title);
        i.putExtra(DetailActivity.EXTRA_YEAR, item.year);
        if (item.resume) {
            i.putExtra(DetailActivity.EXTRA_AUTOPLAY_INDEX, item.index);
            i.putExtra(DetailActivity.EXTRA_PLAYTIME, item.playTime);
        }
        startActivity(i);
    }
}
