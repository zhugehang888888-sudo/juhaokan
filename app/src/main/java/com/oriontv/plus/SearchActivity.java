package com.oriontv.plus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SearchActivity extends Activity {

    private EditText input;
    private ProgressBar loading;
    private TextView hint;
    private PosterAdapter adapter;
    private RecyclerView grid;
    private LinearLayout keyboard;
    private TextView kbToggle;
    private boolean keyboardVisible = true;
    private int cols = 5;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int searchSeq = 0;

    private final Runnable autoSearch = new Runnable() {
        @Override
        public void run() {
            if (input.getText().toString().trim().isEmpty()) {
                return;
            }
            doSearch(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        input = findViewById(R.id.searchInput);
        loading = findViewById(R.id.searchLoading);
        hint = findViewById(R.id.searchHint);
        keyboard = findViewById(R.id.searchKeyboard);
        kbToggle = findViewById(R.id.kbToggle);
        final TextView searchBtn = findViewById(R.id.searchBtn);

        buildKeyboard();
        kbToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setKeyboardVisible(!keyboardVisible);
            }
        });

        grid = findViewById(R.id.searchGrid);
        adapter = new PosterAdapter(new PosterAdapter.Listener() {
            @Override
            public void onCard(CardItem item, int position) {
                Intent i = new Intent(SearchActivity.this, DetailActivity.class);
                i.putExtra(DetailActivity.EXTRA_SOURCE, item.source);
                i.putExtra(DetailActivity.EXTRA_ID, item.id);
                i.putExtra(DetailActivity.EXTRA_TITLE, item.title);
                i.putExtra(DetailActivity.EXTRA_YEAR, item.year);
                startActivity(i);
            }
        });
        grid.setLayoutManager(new GridLayoutManager(this, 4));
        grid.setAdapter(adapter);
        grid.post(new Runnable() {
            @Override
            public void run() {
                updateColumns();
            }
        });

        // 遥控器：搜索框
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 优先进入字母键盘，方便遥控器选拼音首字母
                    View first = keyboardFirst();
                    if (first != null && first.requestFocus()) {
                        return true;
                    }
                    return focusFirstResult();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 光标已在输入末尾时，再按「右」切到「搜索」按钮
                    int len = input.getText() == null ? 0 : input.getText().length();
                    if (input.getSelectionStart() >= len) {
                        searchBtn.requestFocus();
                        return true;
                    }
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    // 遥控器直接按 OK 即可搜索
                    doSearch();
                    return true;
                }
                return false;
            }
        });

        // 遥控器：搜索按钮（左回输入框、下进结果、OK 触发搜索）
        searchBtn.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return focusFirstResult();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    input.requestFocus();
                    return true;
                }
                return false;
            }
        });

        // 重新回到搜索框时自动弹出键盘，方便改词再搜
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    showIme();
                }
            }
        });
        // 键盘收起后，点输入框即可展开，方便继续修改关键词
        input.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setKeyboardVisible(true);
            }
        });

        // 遥控器：结果首行按「上」回到搜索框
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    return false;
                }
                View focus = getCurrentFocus();
                if (focus == null || focus == grid) {
                    return false;
                }
                RecyclerView.ViewHolder vh = grid.findContainingViewHolder(focus);
                if (vh != null && vh.getAdapterPosition() < cols) {
                    input.requestFocus();
                    return true;
                }
                return false;
            }
        });

        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
        input.requestFocus();
    }

    /* ------------------------------------------------------------------ */
    /* 26 字母键盘（拼音首字母检索）                                       */
    /* ------------------------------------------------------------------ */

    private void buildKeyboard() {
        if (keyboard == null) {
            return;
        }
        keyboard.removeAllViews();
        final boolean land = isLandscape();
        String[] rows = {"ABCDEFGHI", "JKLMNOPQR", "STUVWXYZ"};
        for (String letters : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(this, land ? 3 : 4);
            row.setLayoutParams(rlp);
            for (int i = 0; i < letters.length(); i++) {
                final char c = letters.charAt(i);
                row.addView(keyButton(String.valueOf(c), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        appendChar(Character.toLowerCase(c));
                        scheduleSearch();
                    }
                }), keyLp());
            }
            keyboard.addView(row);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 4);
        actions.setLayoutParams(alp);
        actions.addView(keyButton("退格", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CharSequence text = input.getText();
                if (text.length() > 0) {
                    input.getText().delete(text.length() - 1, text.length());
                    scheduleSearch();
                }
            }
        }), keyLp());
        actions.addView(keyButton("清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                hint.setText("支持片名搜索，也可直接选择拼音首字母（如 xyj → 西游记）");
            }
        }), keyLp());
        actions.addView(keyButton("搜索", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(true);
            }
        }), keyLp());
        keyboard.addView(actions);
    }

    private LinearLayout.LayoutParams keyLp() {
        boolean land = isLandscape();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                Ui.dp(this, land ? 30 : 38), 1f);
        lp.setMarginStart(Ui.dp(this, 3));
        lp.setMarginEnd(Ui.dp(this, 3));
        return lp;
    }

    private TextView keyButton(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(isLandscape() ? 13 : 14);
        b.setTextColor(0xFFF2F6F9);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_menu_item);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                    v.requestFocusFromTouch();
                }
                return false;
            }
        });
        return b;
    }

    /** 竖屏固定两列；横屏按宽度分列，保证卡片和片名都能看清 */
    private void updateColumns() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int wDp = (int) (dm.widthPixels / dm.density);
        int hDp = (int) (dm.heightPixels / dm.density);
        boolean land = wDp > hDp;
        cols = land ? Math.max(3, Math.min(6, wDp / 165)) : 2;
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            ((GridLayoutManager) lm).setSpanCount(cols);
        }
    }

    private boolean isLandscape() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    private void setKeyboardVisible(boolean visible) {
        keyboardVisible = visible;
        if (keyboard != null) {
            keyboard.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (kbToggle != null) {
            kbToggle.setText(visible ? "收起键盘" : "显示键盘");
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildKeyboard();
        setKeyboardVisible(keyboardVisible);
        updateColumns();
    }

    private View keyboardFirst() {
        if (keyboard == null || keyboard.getChildCount() == 0) {
            return null;
        }
        View row = keyboard.getChildAt(0);
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 0) {
            return ((LinearLayout) row).getChildAt(0);
        }
        return null;
    }

    private void appendChar(char c) {
        String text = input.getText().toString() + c;
        input.setText(text);
        input.setSelection(text.length());
    }

    private void scheduleSearch() {
        hideIme();
        handler.removeCallbacks(autoSearch);
        handler.postDelayed(autoSearch, 700);
    }

    private void showIme() {
        input.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && input.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    /** 把遥控器焦点移到搜索结果的第一张卡片上 */
    private boolean focusFirstResult() {
        if (adapter.getItemCount() == 0) {
            return false;
        }
        hideIme();
        grid.scrollToPosition(0);
        grid.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
                if (vh != null && vh.itemView.requestFocusFromTouch()) {
                    return;
                }
                for (int i = 0; i < grid.getChildCount(); i++) {
                    if (grid.getChildAt(i).requestFocusFromTouch()) {
                        return;
                    }
                }
            }
        });
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void doSearch() {
        doSearch(true);
    }

    private void doSearch(final boolean focusResults) {
        final String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            Ui.toast(this, "请输入影视名称或选择拼音首字母");
            return;
        }
        final int seq = ++searchSeq;
        loading.setVisibility(View.VISIBLE);
        hint.setText("正在搜索：" + q);
        if (focusResults) {
            // 主动搜索后收起键盘，把屏幕让给结果（横屏尤其需要）
            setKeyboardVisible(false);
        }

        if (!Pinyin.isLetters(q) || q.length() < 2) {
            App.get().api().search(q, new Api.Cb<List<VideoItem>>() {
                @Override
                public void done(List<VideoItem> data, String err) {
                    if (seq != searchSeq) {
                        return;
                    }
                    renderResults(q, data, err, false, focusResults, null);
                }
            });
            return;
        }

        // 纯字母查询：先用本地片名索引把 jsxs 还原成候选中文名，
        // 再并发用中文名查各源；不同源对首字母的支持并不一致，不能只靠服务端。
        // 本地没见过的片名由内置词库兜底（观察索引优先），词库只用来产生查询词。
        final List<String> seeds = pinyinSeeds(q);
        final List<String> queries = new ArrayList<>();
        if (seeds.isEmpty()) {
            queries.add(q);
        } else {
            queries.addAll(seeds);
        }
        final List<VideoItem> all = new ArrayList<>();
        final int[] remain = new int[]{queries.size()};
        final String[] firstError = new String[]{null};
        final boolean[] rendered = new boolean[]{false};
        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (rendered[0]) {
                    return;
                }
                rendered[0] = true;
                if (seq == searchSeq) {
                    renderResults(q, all, firstError[0], true, focusResults, seeds);
                }
            }
        };
        final Api.Cb<List<VideoItem>> cb = new Api.Cb<List<VideoItem>>() {
            @Override
            public void done(List<VideoItem> data, String err) {
                if (seq != searchSeq) {
                    return;
                }
                if (data != null) {
                    all.addAll(data);
                }
                if (err != null && firstError[0] == null) {
                    firstError[0] = err;
                }
                remain[0]--;
                if (remain[0] <= 0) {
                    hint.removeCallbacks(finish);
                    finish.run();
                }
            }
        };
        // 单个源卡住时不能一直停在“正在搜索”
        hint.postDelayed(finish, 15000);
        for (String query : queries) {
            App.get().api().search(query, cb);
        }
    }

    /** 纯字母查询的候选中文名：本地观察索引优先，内置词库兜底，最多 6 个 */
    private List<String> pinyinSeeds(String q) {
        final String key = q.toLowerCase(Locale.US);
        List<String> raw = new ArrayList<>();
        for (String t : TitleIndex.match(q, 3)) {
            // 观察索引里可能混入超长剧集标题，别拿它当查询词
            if (t.length() <= 16 && !raw.contains(t)) {
                raw.add(t);
            }
        }
        for (String t : BuiltinTitles.match(this, q, 6)) {
            if (t.length() <= 16 && !raw.contains(t)) {
                raw.add(t);
            }
        }
        if (raw.isEmpty()) {
            return raw;
        }
        // 完全相等 > 前缀 > 包含；只要存在相等/前缀候选，就丢掉「包含」的弱候选，
        // 避免把 多喜一家人 这类首字母里恰好含 xyj 的片名也拿去查源。
        List<String> exact = new ArrayList<>();
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String t : raw) {
            String ini = Pinyin.initials(t);
            if (ini.equals(key)) {
                exact.add(t);
            } else if (ini.startsWith(key)) {
                prefix.add(t);
            } else if (ini.contains(key)) {
                contains.add(t);
            }
        }
        List<String> out = new ArrayList<>();
        // 有完全匹配就只查完全匹配：txdy 命中「天下第一」时，
        // 不应把「天下第一恋爱脑」这类同前缀片名也当成候选。
        if (!exact.isEmpty()) {
            out.addAll(exact);
        } else if (!prefix.isEmpty()) {
            out.addAll(prefix);
        } else {
            out.addAll(contains);
        }
        while (out.size() > 6) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private void renderResults(String q, List<VideoItem> data, String err,
                               boolean pinyin, boolean focusResults, List<String> seeds) {
        loading.setVisibility(View.GONE);
        if (err != null && (data == null || data.isEmpty())) {
            hint.setText(err);
            return;
        }
        List<CardItem> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (data != null) {
            for (VideoItem v : data) {
                String k = v.title + "|" + v.source;
                if (!seen.add(k)) {
                    continue;
                }
                cards.add(CardItem.of(v));
            }
        }
        if (pinyin) {
            // 只保留首字母真正命中的结果，避免把服务端的无关结果混进来
            cards = seeds.isEmpty()
                    ? filterInitials(cards, q.toLowerCase(Locale.US))
                    : filterBySeeds(cards, seeds);
            adapter.setItems(cards);
            if (cards.isEmpty()) {
                hint.setText("请输入片名搜索");
            } else {
                String recognized = seedText(seeds, 1);
                if (recognized.length() > 12) {
                    recognized = recognized.substring(0, 12) + "…";
                }
                hint.setText("拼音首字母 " + q.toUpperCase(Locale.US)
                        + (recognized.isEmpty() ? "" : "（" + recognized + "）")
                        + " 命中 " + cards.size() + " 个播放源 · 方向键选择，OK 进入");
            }
        } else {
            adapter.setItems(cards);
            if (cards.isEmpty()) {
                hint.setText("没有找到相关影视，换个关键词试试");
            } else {
                hint.setText("共 " + cards.size() + " 个播放源 · 方向键选择，OK 进入");
            }
        }
        // 出结果后自动选中第一项，遥控器可直接按「确认」进入
        if (focusResults && !cards.isEmpty()) {
            focusFirstResult();
        }
    }

    /** 把还原出的候选片名拼成提示文案，最多 max 个 */
    private String seedText(List<String> seeds, int max) {
        if (seeds == null || seeds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seeds.size() && i < max; i++) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(seeds.get(i));
        }
        return sb.toString();
    }

    /**
     * 已经还原出中文名时，只保留片名与候选一致的源结果（完全相等 > 前缀 > 包含），
     * 避免 txdy 把「天下第一恋爱脑」这类首字母恰好包含 txdy 的无关片带进来。
     */
    private List<CardItem> filterBySeeds(List<CardItem> cards, List<String> seeds) {
        List<String> normalized = new ArrayList<>();
        for (String s : seeds) {
            String n = normTitle(s);
            if (!n.isEmpty() && !normalized.contains(n)) {
                normalized.add(n);
            }
        }
        List<CardItem> exact = new ArrayList<>();
        List<CardItem> prefix = new ArrayList<>();
        List<CardItem> contains = new ArrayList<>();
        for (CardItem c : cards) {
            String t = normTitle(c.title);
            if (t.isEmpty()) {
                continue;
            }
            int best = 3;
            for (String s : normalized) {
                if (t.equals(s)) {
                    best = 0;
                    break;
                }
                if (t.startsWith(s) && best > 1) {
                    best = 1;
                } else if (t.contains(s) && best > 2) {
                    best = 2;
                }
            }
            if (best == 0) {
                exact.add(c);
            } else if (best == 1) {
                prefix.add(c);
            } else if (best == 2) {
                contains.add(c);
            }
        }
        List<CardItem> out = new ArrayList<>(exact.size() + prefix.size() + contains.size());
        out.addAll(exact);
        out.addAll(prefix);
        out.addAll(contains);
        return out;
    }

    private static String normTitle(String t) {
        if (t == null) {
            return "";
        }
        return t.replaceAll("[\\s·:：!！?？,，。.、\\-_—()（）\\[\\]【】]", "").toLowerCase(Locale.US);
    }

    /** 纯字母查询只保留拼音首字母命中的结果，按 完全相等 → 前缀 → 包含 排序 */
    private List<CardItem> filterInitials(List<CardItem> cards, String key) {
        List<CardItem> exact = new ArrayList<>();
        List<CardItem> prefix = new ArrayList<>();
        List<CardItem> contains = new ArrayList<>();
        for (CardItem c : cards) {
            String ini = Pinyin.initials(c.title);
            if (ini.equals(key)) {
                exact.add(c);
            } else if (ini.startsWith(key)) {
                prefix.add(c);
            } else if (ini.contains(key)) {
                contains.add(c);
            }
        }
        List<CardItem> out = new ArrayList<>(exact.size() + prefix.size() + contains.size());
        out.addAll(exact);
        out.addAll(prefix);
        out.addAll(contains);
        return out;
    }
}
