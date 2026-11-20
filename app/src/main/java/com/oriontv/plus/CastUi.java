package com.oriontv.plus;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 投屏设备选择面板（直播、影视共用同一套实现）。
 * <p>
 * 显式状态机：搜索中 → 选择设备 → 连接中 → 已连接（显示真实设备名 + 已连接）；
 * 断开后进入「已断开」，不自动重连、不自动再搜，由用户点「重新搜索」重新发现设备。
 */
public class CastUi {

    public interface Listener {
        void onCastChanged();
    }

    private static final int ST_IDLE = 0;          // 已发现设备，等待选择
    private static final int ST_SEARCHING = 1;
    private static final int ST_CONNECTING = 2;
    private static final int ST_CONNECTED = 3;
    private static final int ST_DISCONNECTED = 4;

    /** 每次搜索/连接递增，用于丢弃过期的异步回调 */
    private static int generation = 0;
    /** 当前打开的面板，防止连点重复弹出 */
    private static java.lang.ref.WeakReference<Dialog> showingRef =
            new java.lang.ref.WeakReference<>(null);
    /** 最近断开的设备名：断开后重新打开面板仍显示「已断开」，不自动搜索 */
    private static volatile String lastName = "";
    /** 断开时投屏的内容地址：换一个视频再投屏时正常搜索，不沿用「已断开」 */
    private static volatile String lastUrl = "";

    public static void show(final Activity activity, final String url, final String title,
                            final Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            Dialog open = showingRef.get();
            if (open != null && open.isShowing()) {
                return;
            }
        } catch (Exception ignored) {
        }
        if (url == null || url.trim().isEmpty()) {
            Ui.toast(activity, "当前没有可投屏的地址");
            return;
        }
        try {
            final Dialog dialog = new Dialog(activity, R.style.DialogTheme);
            View box = LayoutInflater.from(activity).inflate(R.layout.dialog_cast, null);
            dialog.setContentView(box);
            dialog.setCanceledOnTouchOutside(true);

            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.dimAmount = 0.62f;
                w.setAttributes(lp);
                int screenW = activity.getResources().getDisplayMetrics().widthPixels;
                w.setLayout(Math.min(Ui.dp(activity, 520), (int) (screenW * 0.92f)),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            final Panel panel = new Panel(activity, dialog, box, url, title, listener);
            panel.bind();
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    // 关闭面板后让过期搜索自然结束，不再弹任何内容
                    showingRef.clear();
                }
            });

            showingRef = new java.lang.ref.WeakReference<>(dialog);
            dialog.show();
            panel.start();
        } catch (Exception e) {
            android.util.Log.e("CastUi", "open cast panel failed", e);
            Ui.toast(activity, "无法打开投屏：请稍后重试");
        }
    }

    private static final class Panel {
        final Activity activity;
        final Dialog dialog;
        final String url;
        final String title;
        final Listener listener;

        final TextView sub;
        final TextView searchingText;
        final TextView connectedName;
        final TextView disconnectedText;
        final TextView disconnectedTip;
        final TextView refresh;
        final TextView close;
        final TextView stop;
        final LinearLayout searching;
        final LinearLayout empty;
        final LinearLayout connected;
        final LinearLayout disconnected;
        final LinearLayout list;
        final View listScroll;

        int gen = 0;
        int state = ST_IDLE;
        String deviceName = "";

        Panel(Activity activity, Dialog dialog, View box, String url, String title,
              Listener listener) {
            this.activity = activity;
            this.dialog = dialog;
            this.url = url;
            this.title = title;
            this.listener = listener;
            sub = box.findViewById(R.id.castSub);
            searchingText = box.findViewById(R.id.castSearchingText);
            connectedName = box.findViewById(R.id.castConnectedName);
            disconnectedText = box.findViewById(R.id.castDisconnectedText);
            disconnectedTip = box.findViewById(R.id.castDisconnectedTip);
            refresh = box.findViewById(R.id.castRefresh);
            close = box.findViewById(R.id.castClose);
            stop = box.findViewById(R.id.castStop);
            searching = box.findViewById(R.id.castSearching);
            empty = box.findViewById(R.id.castEmpty);
            connected = box.findViewById(R.id.castConnected);
            disconnected = box.findViewById(R.id.castDisconnected);
            list = box.findViewById(R.id.castList);
            listScroll = box.findViewById(R.id.castListScroll);
        }

        void bind() {
            refresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    search();
                }
            });
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    disconnect();
                }
            });
        }

        /** 打开面板时的初始状态：已连接 → 直接显示；刚断开 → 显示已断开；否则开始搜索 */
        void start() {
            CastManager.Device c = CastManager.current();
            if (c != null) {
                deviceName = c.name;
                showConnected(c.name);
                return;
            }
            if (lastName != null && !lastName.isEmpty()
                    && url != null && url.equals(lastUrl)) {
                showDisconnected("投屏已断开",
                        "已断开与「" + lastName + "」的连接",
                        "需要继续投屏时，点「重新搜索」重新发现设备");
                return;
            }
            // 换了投屏内容时清掉上次的断开记录，正常开始搜索
            lastName = "";
            lastUrl = "";
            search();
        }

        /* ---------------------------- 状态渲染 ---------------------------- */

        private void showSearching(String subText, String line) {
            sub.setText(subText);
            searchingText.setText(line);
            searching.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            connected.setVisibility(View.GONE);
            disconnected.setVisibility(View.GONE);
            listScroll.setVisibility(View.GONE);
            list.removeAllViews();
            stop.setVisibility(View.GONE);
            refresh.setVisibility(View.VISIBLE);
            setRefreshEnabled(false);
            close.requestFocusFromTouch();
        }

        private void showEmpty() {
            state = ST_IDLE;
            searching.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            connected.setVisibility(View.GONE);
            disconnected.setVisibility(View.GONE);
            listScroll.setVisibility(View.GONE);
            sub.setText(CastManager.lastSeen > 0
                    ? "发现 " + CastManager.lastSeen + " 台设备，但没有支持投屏的电视"
                    : "未找到可投屏的电视：请确认电视已开机、已开启投屏，且与手机在同一 Wi-Fi");
            setRefreshEnabled(true);
            refresh.requestFocusFromTouch();
        }

        private void showDevices(List<CastManager.Device> devices) {
            state = ST_IDLE;
            searching.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            connected.setVisibility(View.GONE);
            disconnected.setVisibility(View.GONE);
            listScroll.setVisibility(View.VISIBLE);
            fillDevices(devices);
            sub.setText("共发现 " + list.getChildCount() + " 台设备，选择要投屏的电视");
            setRefreshEnabled(true);
        }

        private void showConnected(String name) {
            state = ST_CONNECTED;
            lastName = "";
            lastUrl = "";
            deviceName = name == null || name.trim().isEmpty() ? "电视" : name.trim();
            connectedName.setText(deviceName);
            connected.setVisibility(View.VISIBLE);
            searching.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            disconnected.setVisibility(View.GONE);
            listScroll.setVisibility(View.GONE);
            list.removeAllViews();
            refresh.setVisibility(View.GONE);
            setRefreshEnabled(true);
            stop.setVisibility(View.VISIBLE);
            stop.setEnabled(true);
            stop.setAlpha(1f);
            stop.requestFocusFromTouch();
            sub.setText("已连接到 " + deviceName + "，正在投屏");
        }

        private void showDisconnected(String subText, String text, String tip) {
            state = ST_DISCONNECTED;
            searching.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            connected.setVisibility(View.GONE);
            listScroll.setVisibility(View.GONE);
            list.removeAllViews();
            disconnected.setVisibility(View.VISIBLE);
            disconnectedText.setText(text);
            disconnectedTip.setText(tip);
            stop.setVisibility(View.GONE);
            stop.setEnabled(true);
            stop.setAlpha(1f);
            refresh.setVisibility(View.VISIBLE);
            setRefreshEnabled(true);
            refresh.requestFocusFromTouch();
            sub.setText(subText);
        }

        private void setRefreshEnabled(boolean enabled) {
            refresh.setEnabled(enabled);
            refresh.setAlpha(enabled ? 1f : 0.45f);
        }

        /* ---------------------------- 搜索 ---------------------------- */

        void search() {
            final int g = ++gen;
            state = ST_SEARCHING;
            showSearching("正在搜索局域网内的电视…", "正在搜索同一局域网内的电视…");
            CastManager.discover(activity, new CastManager.DeviceCb() {
                @Override
                public void done(final List<CastManager.Device> devices) {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 已连接/已断开/换设备后，丢弃上一轮搜索的迟到结果
                            if (g != gen || state != ST_SEARCHING
                                    || dialog == null || !dialog.isShowing()) {
                                return;
                            }
                            try {
                                if (devices == null || devices.isEmpty()) {
                                    showEmpty();
                                    return;
                                }
                                showDevices(devices);
                            } catch (Exception ignored) {
                            }
                        }
                    });
                }
            });
        }

        /* ---------------------------- 设备列表 ---------------------------- */

        private void fillDevices(List<CastManager.Device> devices) {
            LayoutInflater inflater = LayoutInflater.from(activity);
            List<String> seen = new ArrayList<>();
            for (final CastManager.Device d : devices) {
                String host = "";
                try {
                    host = new java.net.URL(d.location).getHost();
                } catch (Exception ignored) {
                }
                String key = d.name + "|" + host;
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);

                View row = inflater.inflate(R.layout.item_cast_device, list, false);
                TextView name = row.findViewById(R.id.castDeviceName);
                TextView hostView = row.findViewById(R.id.castDeviceHost);
                name.setText(d.name);
                hostView.setText(host.isEmpty() ? "DLNA 设备" : host);
                row.setFocusable(true);
                row.setFocusableInTouchMode(true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        connect(d);
                    }
                });
                row.setOnHoverListener(new View.OnHoverListener() {
                    @Override
                    public boolean onHover(View v, MotionEvent event) {
                        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) {
                            v.requestFocusFromTouch();
                        }
                        return false;
                    }
                });
                list.addView(row);
            }
            if (list.getChildCount() > 0) {
                ViewGroup.LayoutParams lp = listScroll.getLayoutParams();
                lp.height = list.getChildCount() > 3
                        ? Ui.dp(activity, 232) : ViewGroup.LayoutParams.WRAP_CONTENT;
                listScroll.setLayoutParams(lp);
                list.getChildAt(0).requestFocusFromTouch();
            }
        }

        /* ---------------------------- 连接 / 断开 ---------------------------- */

        private void connect(final CastManager.Device d) {
            if (state != ST_IDLE) {
                return;
            }
            final int g = ++gen;
            state = ST_CONNECTING;
            deviceName = d.name;
            showSearching("正在连接 " + d.name + "…", "正在连接 " + d.name + "，请稍候…");
            CastManager.play(d, url, title, new CastManager.ActionCb() {
                @Override
                public void done(final boolean ok, final String msg) {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(activity, msg);
                            if (listener != null) {
                                listener.onCastChanged();
                            }
                            if (g != gen || !dialog.isShowing()) {
                                return;
                            }
                            if (ok) {
                                showConnected(d.name);
                            } else {
                                // 连接失败也不自动再搜，交给用户决定
                                showDisconnected("连接失败",
                                        "未能连接到「" + d.name + "」",
                                        msg + "；可点「重新搜索」重新发现设备");
                            }
                        }
                    });
                }
            });
        }

        private void disconnect() {
            final String name = CastManager.currentName();
            final String label = name == null || name.isEmpty() ? deviceName : name;
            stop.setEnabled(false);
            stop.setAlpha(0.5f);
            sub.setText("正在断开投屏…");
            CastManager.stop(new CastManager.ActionCb() {
                @Override
                public void done(final boolean ok, final String msg) {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(activity, msg);
                            if (listener != null) {
                                listener.onCastChanged();
                            }
                            lastName = label;
                            lastUrl = url;
                            if (dialog.isShowing()) {
                                showDisconnected("投屏已断开",
                                        "已断开与「" + label + "」的连接",
                                        "需要继续投屏时，点「重新搜索」重新发现设备");
                            }
                        }
                    });
                }
            });
        }
    }
}
