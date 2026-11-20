package com.oriontv.plus;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.util.Locale;

public class Ui {

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 弹窗宽度上限：不超过屏幕宽度的 92%，避免竖屏手机上固定宽度弹窗右侧被裁切 */
    public static void fitDialogWidth(android.app.Dialog d, Context c, int maxDp) {
        if (d == null || c == null) {
            return;
        }
        android.view.Window w = d.getWindow();
        if (w == null) {
            return;
        }
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min(dp(c, maxDp), (int) (screenW * 0.92f)),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static void toast(final Context c, final String msg) {
        if (c == null || msg == null) {
            return;
        }
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static String time(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String speedLabel(float value) {
        if (Math.abs(value - 1f) < 0.01f) {
            return "正常";
        }
        String s = String.format(Locale.US, "%.2f", value);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "x";
    }

    public static void visible(View v, boolean visible) {
        if (v != null) {
            v.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 遥控器输入：上下左右键在多个输入框之间切换，OK/回车跳到下一个输入框，
     * 最后一个输入框按 OK 时把焦点交给下面的按钮。鼠标、手指点击不受影响。
     */
    public static void dpadInputs(final EditText... fields) {
        if (fields == null || fields.length == 0) {
            return;
        }
        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            final EditText field = fields[i];
            if (index + 1 < fields.length) {
                field.setNextFocusDownId(fields[index + 1].getId());
            }
            if (index > 0) {
                field.setNextFocusUpId(fields[index - 1].getId());
            }
            field.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    boolean next = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
                    boolean prev = keyCode == KeyEvent.KEYCODE_DPAD_UP
                            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
                    if (next) {
                        if (index + 1 < fields.length) {
                            fields[index + 1].requestFocus();
                            return true;
                        }
                        View n = field.focusSearch(View.FOCUS_DOWN);
                        if (n != null && n != field) {
                            n.requestFocus();
                            return true;
                        }
                        return false;
                    }
                    if (prev) {
                        if (index > 0) {
                            fields[index - 1].requestFocus();
                            return true;
                        }
                        View p = field.focusSearch(View.FOCUS_UP);
                        if (p != null && p != field) {
                            p.requestFocus();
                            return true;
                        }
                        return false;
                    }
                    return false;
                }
            });
        }
    }

    public interface ActionPick {
        void onPick(int index);
    }

    /**
     * 统一样式的操作菜单（深色圆角面板），遥控器上下选择、OK 确认，鼠标/触屏可直接点击。
     * @param dangerIndex 需要标红的条目下标，-1 表示没有
     */
    public static void actions(final android.app.Activity a, String title, final String[] items,
                               final int dangerIndex, final ActionPick cb) {
        if (a == null || a.isFinishing() || a.isDestroyed() || items == null || items.length == 0) {
            return;
        }
        final android.app.Dialog dlg = new android.app.Dialog(a, R.style.DialogTheme);
        View box = android.view.LayoutInflater.from(a).inflate(R.layout.dialog_actions, null);
        dlg.setContentView(box);
        dlg.setCanceledOnTouchOutside(true);
        ((android.widget.TextView) box.findViewById(R.id.actionTitle)).setText(title);
        android.widget.LinearLayout list = box.findViewById(R.id.actionList);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            android.widget.TextView row = new android.widget.TextView(a);
            row.setText(items[i]);
            row.setTextSize(15);
            row.setTextColor(a.getResources().getColor(i == dangerIndex ? R.color.danger : R.color.text));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_cast_item);
            row.setClickable(true);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setPadding(dp(a, 14), 0, dp(a, 14), 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 46));
            lp.bottomMargin = dp(a, 8);
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    if (cb != null) {
                        cb.onPick(index);
                    }
                }
            });
            list.addView(row);
        }
        showFit(dlg, a, 420, 0f);
        if (list.getChildCount() > 0) {
            list.getChildAt(0).requestFocusFromTouch();
        }
    }

    /** 统一样式的确认框 */
    public static void confirm(final android.app.Activity a, String title, String message,
                               String positive, final Runnable onOk) {
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            return;
        }
        final android.app.Dialog dlg = new android.app.Dialog(a, R.style.DialogTheme);
        View box = android.view.LayoutInflater.from(a).inflate(R.layout.dialog_confirm, null);
        dlg.setContentView(box);
        dlg.setCanceledOnTouchOutside(true);
        ((android.widget.TextView) box.findViewById(R.id.confirmTitle)).setText(title);
        ((android.widget.TextView) box.findViewById(R.id.confirmMessage)).setText(message);
        android.widget.TextView ok = box.findViewById(R.id.confirmOk);
        ok.setText(positive);
        box.findViewById(R.id.confirmCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                if (onOk != null) {
                    onOk.run();
                }
            }
        });
        showFit(dlg, a, 440, 0f);
        ok.requestFocusFromTouch();
    }

    /** 统一弹出：透明窗体 + 暗背景 + 限定宽高，避免在小屏/横屏被裁切 */
    public static void showFit(final android.app.Dialog dlg, android.app.Activity a,
                               int maxWidthDp, float heightRatio) {
        if (dlg == null || a == null) {
            return;
        }
        try {
            dlg.show();
        } catch (Exception e) {
            return;
        }
        android.view.Window w = dlg.getWindow();
        if (w == null) {
            return;
        }
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.62f;
        w.setAttributes(lp);
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        int sh = a.getResources().getDisplayMetrics().heightPixels;
        int height = heightRatio <= 0f ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                : (int) (sh * heightRatio);
        w.setLayout(Math.min(dp(a, maxWidthDp), (int) (sw * 0.92f)), height);
    }
}
