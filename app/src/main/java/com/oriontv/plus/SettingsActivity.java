package com.oriontv.plus;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class SettingsActivity extends Activity {

    /** 从直播页「管理订阅源」进来时，直接弹出订阅源管理弹窗 */
    public static final String EXTRA_OPEN_SOURCES = "openSources";

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f};

    private final Prefs prefs = App.get().prefs();
    private EditText server;
    private EditText name;
    private EditText user;
    private EditText pass;
    private TextView status;
    private View btnServers;
    private View btnSpeed;
    private View btnAdblock;
    private View btnAutoNext;
    private View btnClock;
    private View btnHomeMode;
    private View btnLiveSources;
    private View btnLiveDecode;
    private View btnLiveKernel;
    /** 首屏焦点只在窗口第一次拿到焦点时设置，避免打断后续弹窗 */
    private View focusTarget;
    private boolean initialFocusDone;
    private View btnBoot;
    private TextView valServers;
    private TextView valSpeed;
    private TextView valHomeMode;
    private TextView valLiveSources;
    private TextView valLiveDecode;
    private TextView valLiveKernel;
    private TextView valBoot;
    private View swAdblock;
    private View swAutoNext;
    private View swClock;
    /** 订阅源列表弹窗，重开前先关旧的，避免叠层 */
    private AlertDialog sourcesDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        server = findViewById(R.id.editServer);
        name = findViewById(R.id.editServerName);
        user = findViewById(R.id.editUser);
        pass = findViewById(R.id.editPass);
        status = findViewById(R.id.settingsStatus);
        btnServers = findViewById(R.id.btnServers);
        btnServers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, ServersActivity.class));
            }
        });

        // 品牌版在设置页展示出品方
        if (BuildConfig.SHOW_BRAND) {
            findViewById(R.id.shopBrand).setVisibility(View.VISIBLE);
        }

        server.setText(prefs.server());
        user.setText(prefs.user());
        pass.setText(prefs.pass());
        // 遥控器：上下左右切换输入框，OK 跳到下一个
        Ui.dpadInputs(name, server, user, pass);

        findViewById(R.id.btnLogin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                login();
            }
        });

        btnSpeed = findViewById(R.id.btnSpeed);
        btnAdblock = findViewById(R.id.btnAdblock);
        btnAutoNext = findViewById(R.id.btnAutoNext);
        btnClock = findViewById(R.id.btnClock);
        btnHomeMode = findViewById(R.id.btnHomeMode);
        btnLiveSources = findViewById(R.id.btnLiveSources);
        btnLiveDecode = findViewById(R.id.btnLiveDecode);
        btnLiveKernel = findViewById(R.id.btnLiveKernel);
        btnBoot = findViewById(R.id.btnBoot);

        valServers = findViewById(R.id.valServers);
        valSpeed = findViewById(R.id.valSpeed);
        valHomeMode = findViewById(R.id.valHomeMode);
        valLiveSources = findViewById(R.id.valLiveSources);
        valLiveDecode = findViewById(R.id.valLiveDecode);
        valLiveKernel = findViewById(R.id.valLiveKernel);
        valBoot = findViewById(R.id.valBoot);
        swAdblock = findViewById(R.id.swAdblock);
        swAutoNext = findViewById(R.id.swAutoNext);
        swClock = findViewById(R.id.swClock);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnHomeMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setHomeLive(!prefs.homeLive());
                refreshToggles();
            }
        });
        btnLiveSources.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLiveSources();
            }
        });
        btnLiveDecode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLiveDecode();
            }
        });
        btnLiveKernel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLiveKernel();
            }
        });
        btnBoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setBootAutoStart(!prefs.bootAutoStart());
                refreshToggles();
                if (prefs.bootAutoStart()) {
                    AutoStart.enable(SettingsActivity.this);
                    AutoStart.scheduleBootJob(SettingsActivity.this);
                }
            }
        });

        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSpeed();
            }
        });
        btnAdblock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setAdBlock(!prefs.adBlock());
                refreshToggles();
            }
        });
        btnAutoNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setAutoNext(!prefs.autoNext());
                refreshToggles();
            }
        });
        btnClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setClockEnabled(!prefs.clockEnabled());
                refreshToggles();
            }
        });
        findViewById(R.id.btnClearCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                App.get().clearMediaCache();
                Ui.toast(SettingsActivity.this, "播放缓存已清除");
            }
        });
        findViewById(R.id.btnClearRecords).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this, R.style.DarkDialog)
                        .setMessage("确定清空全部播放记录？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                App.get().api().clearPlayRecords();
                                Ui.toast(SettingsActivity.this, "已清空播放记录");
                            }
                        }).show();
            }
        });

        findViewById(R.id.btnSponsor).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSponsor();
            }
        });

        // 遥控器左右键：开关类设置行左右键直接切换，避免只能按确认
        addSideToggle(btnHomeMode);
        addSideToggle(btnAdblock);
        addSideToggle(btnAutoNext);
        addSideToggle(btnClock);
        ((TextView) findViewById(R.id.versionText)).setText("剧好看 v" + BuildConfig.VERSION_NAME
                + "：纯净聚合，去广告/线路测速/分类筛选；直播支持 m3u/txt 订阅源、默认订阅源、解码与播放内核切换、开机自启动；搜索支持拼音首字母。");
        refreshToggles();
        refreshServerSection();
        // 遥控器：收敛焦点到第一行可聚焦的设置项，避免落在输入框上或干脆没有焦点
        View firstRow = findViewById(R.id.btnServers);
        if (firstRow == null || firstRow.getVisibility() != View.VISIBLE) {
            firstRow = findViewById(R.id.btnSpeed);
        }
        focusTarget = firstRow;
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_SOURCES, false)) {
            initialFocusDone = true;
            btnLiveSources.post(new Runnable() {
                @Override
                public void run() {
                    showLiveSources();
                }
            });
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !initialFocusDone && focusTarget != null) {
            initialFocusDone = true;
            // 触屏模式下 requestFocus 不会生效，用 requestFocusFromTouch 退出触屏模式并高亮首行
            focusTarget.requestFocusFromTouch();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServerSection();
        // 账号可能刚被切换，订阅源数量等状态要按新账号刷新
        refreshToggles();
    }

    /** 开关类设置行：遥控器左右键等价于确认键切换 */
    private void addSideToggle(final View row) {
        if (row == null) {
            return;
        }
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    v.performClick();
                    return true;
                }
                return false;
            }
        });
    }

    /** 有账号时只显示「账号与服务器」入口；没有账号时才显示新增表单 */
    private void refreshServerSection() {
        if (btnServers == null) {
            return;
        }
        List<ServerProfile> list = prefs.servers();
        View section = findViewById(R.id.serverSection);
        if (!list.isEmpty()) {
            section.setVisibility(View.GONE);
            btnServers.setVisibility(View.VISIBLE);
            ServerProfile a = prefs.activeServer();
            String label = a == null ? "未配置" : a.displayName();
            if (a != null && a.disabled) {
                label += "（已停用）";
            }
            if (valServers != null) {
                valServers.setText(label);
                valServers.setTextColor(getResources().getColor(
                        a != null && a.disabled ? R.color.text2 : R.color.text));
            }
            return;
        }
        section.setVisibility(View.VISIBLE);
        btnServers.setVisibility(View.GONE);
        boolean builtin = BuildConfig.BUILT_IN_SERVER;
        findViewById(R.id.labelServerName).setVisibility(builtin ? View.GONE : View.VISIBLE);
        name.setVisibility(builtin ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.labelServerAddress)).setText("添加服务器地址");
        ((TextView) findViewById(R.id.btnLogin)).setText("添加并登录");
        if (!builtin && name.getText().toString().trim().isEmpty()) {
            name.setText("我的服务器");
        }
    }

    private void refreshToggles() {
        valSpeed.setText(Ui.speedLabel(prefs.speed()));
        swAdblock.setBackgroundResource(prefs.adBlock()
                ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        swAutoNext.setBackgroundResource(prefs.autoNext()
                ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        swClock.setBackgroundResource(prefs.clockEnabled()
                ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        valHomeMode.setText(prefs.homeLive() ? "直播" : "影视");
        int n = LiveSource.list(prefs).size();
        valLiveSources.setText(n == 0 ? "未添加" : n + " 个");
        valLiveDecode.setText(decodeLabel(prefs.liveDecode()));
        valLiveKernel.setText(prefs.liveKernel() == 1 ? "系统播放器" : "Media3");
        valBoot.setText(bootLabel());
        valBoot.setTextColor(getResources().getColor(
                prefs.bootAutoStart() ? R.color.accent : R.color.text2));
    }

    /** 开机自启状态，Android 14 缺少悬浮窗权限时会拦截后台自启，需要提示 */
    private String bootLabel() {
        if (!prefs.bootAutoStart()) {
            return "已关闭";
        }
        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            return "已开启（需授权悬浮窗）";
        }
        return "已开启";
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

    private void chooseLiveDecode() {
        String[] labels = {"自动（推荐）", "硬解（优先硬件解码）", "软解（兼容优先）"};
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("直播解码方式")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setLiveDecode(which);
                        refreshToggles();
                    }
                }).show();
    }

    private void chooseLiveKernel() {
        String[] labels = {"Media3 播放器（默认）", "系统播放器（兼容模式）"};
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("直播播放内核")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setLiveKernel(which);
                        refreshToggles();
                    }
                }).show();
    }

    /* ------------------------------------------------------------------ */
    /* 直播订阅源管理                                                      */
    /* ------------------------------------------------------------------ */

    private void showLiveSources() {
        // 保存/删除/设为默认后会重新打开列表，先把旧的列表弹窗关掉，避免叠层导致要点两次关闭
        if (sourcesDialog != null && sourcesDialog.isShowing()) {
            sourcesDialog.dismiss();
        }
        sourcesDialog = null;
        final List<LiveSource> list = LiveSource.list(prefs);
        final String def = list.isEmpty() ? ""
                : (prefs.liveDefaultSource().isEmpty() ? list.get(0).url : prefs.liveDefaultSource());
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 8);
        box.setPadding(pad, Ui.dp(this, 6), pad, pad);

        View firstFocus = null;
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            final LiveSource s = list.get(i);
            final boolean isDefault = s.url.equals(def);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

            TextView info = new TextView(this);
            // 列表里只显示订阅名称，地址默认隐藏，点「编辑」才看得到
            info.setText(isDefault
                    ? (s.name == null || s.name.isEmpty() ? "直播源" : s.name) + "  ·  默认"
                    : (s.name == null || s.name.isEmpty() ? "直播源" : s.name));
            info.setTextSize(14);
            info.setTextColor(0xFFF2F6F9);
            info.setBackgroundResource(R.drawable.bg_menu_item);
            info.setFocusable(true);
            info.setSingleLine(true);
            info.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            info.setLayoutParams(lp);
            info.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editLiveSource(index);
                }
            });

            TextView setDef = new TextView(this);
            setDef.setText(isDefault ? "默认 ✓" : "设为默认");
            setDef.setTextSize(13);
            setDef.setTextColor(isDefault ? 0xFF22C55E : 0xFF9DAAB6);
            setDef.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
            setDef.setFocusable(true);
            setDef.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isDefault) {
                        Ui.toast(SettingsActivity.this, "该订阅源已经是默认");
                        return;
                    }
                    prefs.setLiveDefaultSource(s.url);
                    refreshToggles();
                    Ui.toast(SettingsActivity.this, "已设为默认订阅源");
                    showLiveSources();
                }
            });

            TextView edit = new TextView(this);
            edit.setText("编辑");
            edit.setTextSize(13);
            edit.setTextColor(0xFF60A5FA);
            edit.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
            edit.setFocusable(true);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editLiveSource(index);
                }
            });

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setTextColor(0xFFEF4444);
            del.setPadding(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14));
            del.setFocusable(true);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<LiveSource> now = LiveSource.list(prefs);
                    if (index < now.size()) {
                        now.remove(index);
                        LiveSource.save(prefs, now);
                        if (s.url.equals(prefs.liveDefaultSource())) {
                            prefs.setLiveDefaultSource(now.isEmpty() ? "" : now.get(0).url);
                        }
                        refreshToggles();
                        Ui.toast(SettingsActivity.this, "已删除该订阅源");
                        showLiveSources();
                    }
                }
            });
            // 遥控器：上下选行，OK 编辑，右键到「设为默认 / 编辑 / 删除」，鼠标触摸照旧可用
            int idInfo = View.generateViewId();
            int idDef = View.generateViewId();
            int idEdit = View.generateViewId();
            int idDel = View.generateViewId();
            info.setId(idInfo);
            setDef.setId(idDef);
            edit.setId(idEdit);
            del.setId(idDel);
            setDef.setBackgroundResource(R.drawable.bg_menu_item);
            edit.setBackgroundResource(R.drawable.bg_menu_item);
            del.setBackgroundResource(R.drawable.bg_menu_item);
            info.setNextFocusRightId(idDef);
            setDef.setNextFocusRightId(idEdit);
            edit.setNextFocusRightId(idDel);
            setDef.setNextFocusLeftId(idInfo);
            edit.setNextFocusLeftId(idDef);
            del.setNextFocusLeftId(idEdit);
            if (firstFocus == null) {
                firstFocus = info;
            }
            row.addView(info);
            row.addView(setDef);
            row.addView(edit);
            row.addView(del);
            box.addView(row);
        }

        TextView add = new TextView(this);
        add.setText(list.isEmpty() ? "＋ 添加直播订阅源" : "＋ 添加新的订阅源");
        add.setTextSize(14);
        add.setTextColor(0xFFF2F6F9);
        add.setGravity(android.view.Gravity.CENTER);
        add.setBackgroundResource(R.drawable.bg_btn);
        add.setFocusable(true);
        add.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 12);
        add.setLayoutParams(alp);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editLiveSource(-1);
            }
        });
        box.addView(add);

        TextView tip = new TextView(this);
        tip.setText("支持 m3u / txt 订阅地址；地址默认隐藏，点「编辑」可查看和修改名称、链接。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF9DAAB6);
        tip.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(tip);

        sc.addView(box);
        final View focusTarget = firstFocus != null ? firstFocus : add;
        AlertDialog dlg = new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("直播订阅源（自动识别分组）")
                .setView(sc)
                .setNegativeButton("关闭", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                focusTarget.requestFocus();
            }
        });
        sourcesDialog = dlg;
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (sourcesDialog == dialog) {
                    sourcesDialog = null;
                }
            }
        });
        dlg.show();
    }

    /** 编辑 / 新增订阅源，index < 0 表示新增 */
    private void editLiveSource(final int index) {
        final List<LiveSource> list = LiveSource.list(prefs);
        LiveSource cur = index >= 0 && index < list.size() ? list.get(index) : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        final EditText name = new EditText(this);
        name.setHint("名称（可留空）");
        name.setSingleLine(true);
        name.setTextSize(15);
        name.setTextColor(0xFFF2F6F9);
        name.setHintTextColor(0xFF9DAAB6);
        name.setBackgroundResource(R.drawable.bg_input);
        name.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        name.setText(cur == null ? "" : cur.name);

        final EditText url = new EditText(this);
        url.setHint("粘贴 m3u / txt 订阅地址");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextSize(15);
        url.setTextColor(0xFFF2F6F9);
        url.setHintTextColor(0xFF9DAAB6);
        url.setBackgroundResource(R.drawable.bg_input);
        url.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        url.setText(cur == null ? "" : cur.url);

        // 遥控器：按 OK 从名称跳到地址栏，上下键也可在两栏之间切换
        int nameId = View.generateViewId();
        int urlId = View.generateViewId();
        name.setId(nameId);
        url.setId(urlId);
        name.setNextFocusDownId(urlId);
        url.setNextFocusUpId(nameId);
        name.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    url.requestFocus();
                    return true;
                }
                return false;
            }
        });
        final AlertDialog[] dlgRef = new AlertDialog[1];
        url.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    name.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 下键从地址栏落到「取消 / 保存」，左右键在这两个按钮间切换
                    if (dlgRef[0] != null) {
                        Button cancel = dlgRef[0].getButton(AlertDialog.BUTTON_NEGATIVE);
                        if (cancel != null) {
                            cancel.requestFocus();
                            return true;
                        }
                    }
                }
                return false;
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        box.addView(name);
        box.addView(url, lp);

        TextView tip = new TextView(this);
        tip.setText("填 m3u 或 txt 的订阅地址即可，例如 IPTV 管理端首页地址。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF9DAAB6);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 10);
        box.addView(tip, tlp);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle(index < 0 ? "添加直播订阅源" : "编辑直播订阅源")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String u = url.getText().toString().trim();
                        String n = name.getText().toString().trim();
                        if (u.isEmpty()) {
                            Ui.toast(SettingsActivity.this, "订阅地址不能为空");
                            return;
                        }
                        if (!u.startsWith("http://") && !u.startsWith("https://")) {
                            u = "http://" + u;
                        }
                        List<LiveSource> now = LiveSource.list(prefs);
                        LiveSource s = new LiveSource(n.isEmpty() ? LiveSource.guessName(u) : n, u);
                        if (index >= 0 && index < now.size()) {
                            now.set(index, s);
                        } else {
                            now.add(s);
                        }
                        LiveSource.save(prefs, now);
                        refreshToggles();
                        Ui.toast(SettingsActivity.this, "已保存，进入直播即可观看");
                        showLiveSources();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlgRef[0] = dlg;
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                AlertDialog d = (AlertDialog) dialog;
                Button cancel = d.getButton(AlertDialog.BUTTON_NEGATIVE);
                Button save = d.getButton(AlertDialog.BUTTON_POSITIVE);
                if (cancel != null && save != null) {
                    cancel.setNextFocusRightId(save.getId());
                    save.setNextFocusLeftId(cancel.getId());
                    cancel.setNextFocusUpId(url.getId());
                    save.setNextFocusUpId(url.getId());
                }
                name.requestFocus();
            }
        });
        dlg.show();
    }

    private void chooseSpeed() {
        final String[] labels = new String[SPEEDS.length + 1];
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = Ui.speedLabel(SPEEDS[i]);
        }
        labels[SPEEDS.length] = "自定义倍速…";
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("默认倍速")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < SPEEDS.length) {
                            prefs.setSpeed(SPEEDS[which]);
                            refreshToggles();
                        } else {
                            customSpeedDialog();
                        }
                    }
                }).show();
    }

    /** 赞助作者：展示微信 / 支付宝收款码，遥控器 OK 或返回键关闭 */
    private void showSponsor() {
        final Dialog dlg = new Dialog(this, R.style.DarkDialog);
        dlg.setContentView(R.layout.dialog_sponsor);
        // 高度也做约束：手机横屏、电视等矮屏首屏即可看全二维码与关闭按钮
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = Math.min(Ui.dp(this, 430), (int) (dm.widthPixels * 0.92f));
            int height = Math.min(Ui.dp(this, 430), (int) (dm.heightPixels * 0.88f));
            w.setLayout(width, height);
        }
        View close = dlg.findViewById(R.id.btnSponsorClose);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        dlg.show();
        close.requestFocus();
    }

    /** 自定义倍速：0.1 – 8 倍（播放内核上限），遥控器左右键微调、上下键到按钮区 */
    private void customSpeedDialog() {
        final Dialog dlg = new Dialog(this, R.style.DarkDialog);
        dlg.setContentView(R.layout.dialog_speed);
        Ui.fitDialogWidth(dlg, this, 380);
        final TextView tvValue = dlg.findViewById(R.id.tvSpeedValue);
        final float[] val = {Math.min(8f, Math.max(0.1f, prefs.speed()))};

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
                chooseSpeed();
            }
        });
        dlg.findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setSpeed(val[0]);
                refreshToggles();
                Ui.toast(SettingsActivity.this, "默认倍速：" + Ui.speedLabel(val[0]));
                dlg.dismiss();
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (btnSpeed != null) {
                    btnSpeed.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSpeed.requestFocus();
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

    private void login() {
        String s = server.getText().toString().trim();
        String u = user.getText().toString().trim();
        String p = pass.getText().toString().trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        server.setText(s);
        if (prefs.servers().isEmpty()) {
            ServerProfile profile = new ServerProfile();
            profile.id = "s" + System.currentTimeMillis();
            String n = name.getText().toString().trim();
            profile.name = n.isEmpty() ? "我的服务器" : n;
            profile.url = s;
            profile.user = u;
            profile.pass = p;
            prefs.upsertServer(profile);
            prefs.setActiveServer(profile.id);
        }
        status.setText("正在登录…");
        App.get().api().login(s, u, p, new Api.Cb<Boolean>() {
            @Override
            public void done(Boolean ok, String err) {
                if (ok != null && ok) {
                    status.setText("登录成功，配置已保存");
                    refreshServerSection();
                } else {
                    status.setText(err == null ? "登录失败" : err);
                }
            }
        });
    }
}
