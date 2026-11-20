package com.oriontv.plus;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 多服务器账号管理：新增、编辑、切换默认、删除、停用 */
public class ServersActivity extends Activity {

    private final Prefs prefs = App.get().prefs();
    private LinearLayout listView;
    private TextView hint;
    private Dialog editDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_servers);
        listView = findViewById(R.id.serverList);
        hint = findViewById(R.id.serversHint);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnAddServer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editServer(null);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        listView.removeAllViews();
        List<ServerProfile> list = prefs.servers();
        ServerProfile active = prefs.activeServer();
        String activeName = active == null ? "未配置" : active.displayName();
        if (active != null && active.disabled) {
            activeName += "（已停用）";
        }
        hint.setText("当前默认：" + activeName);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有添加服务器\n点击下方「新增服务器地址」开始配置");
            empty.setTextColor(getResources().getColor(R.color.text2));
            empty.setTextSize(14);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 40), 0, Ui.dp(this, 40));
            listView.addView(empty);
            findViewById(R.id.btnAddServer).requestFocus();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final ServerProfile p : list) {
            View row = inflater.inflate(R.layout.item_server, listView, false);
            TextView name = row.findViewById(R.id.serverRowName);
            TextView url = row.findViewById(R.id.serverRowUrl);
            TextView account = row.findViewById(R.id.serverRowAccount);
            TextView badge = row.findViewById(R.id.serverRowBadge);

            name.setText(p.displayName());
            boolean isActive = active != null && p.id.equals(active.id);
            if (p.builtin) {
                url.setVisibility(View.GONE);
                account.setText("内置服务器 · 地址已隐藏");
            } else {
                url.setVisibility(View.VISIBLE);
                url.setText(p.url);
                account.setText(p.user == null || p.user.isEmpty() ? "未填写账号" : "账号：" + p.user);
            }
            if (p.disabled) {
                setBadge(badge, "已停用", R.color.text2);
            } else if (isActive) {
                setBadge(badge, "默认", R.color.accent);
            } else if (p.builtin) {
                setBadge(badge, "内置", R.color.accent);
            } else {
                badge.setVisibility(View.GONE);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActions(p);
                }
            });
            listView.addView(row);
        }
        findViewById(R.id.btnAddServer).setFocusable(true);
    }

    private void setBadge(TextView v, String text, int colorRes) {
        v.setText(text);
        v.setTextColor(getResources().getColor(colorRes));
        v.setVisibility(View.VISIBLE);
    }

    private void showActions(final ServerProfile p) {
        if (p.builtin) {
            final boolean disabled = p.disabled;
            String[] items = disabled
                    ? new String[]{"启用内置服务器"}
                    : new String[]{"重新登录", "停用内置服务器"};
            Ui.actions(this, p.displayName() + "（内置）", items, disabled ? -1 : 1,
                    new Ui.ActionPick() {
                        @Override
                        public void onPick(int which) {
                            if (disabled) {
                                p.disabled = false;
                                prefs.upsertServer(p);
                                render();
                                Ui.toast(ServersActivity.this, "内置服务器已启用");
                            } else if (which == 0) {
                                login(p, prefs.activeServer() == null ? "" : prefs.activeServer().id);
                            } else {
                                disableBuiltin(p);
                            }
                        }
                    });
            return;
        }

        final boolean isActive = prefs.activeServer() != null && prefs.activeServer().id.equals(p.id);
        Ui.actions(this, p.displayName(),
                new String[]{isActive ? "重新登录" : "设为默认并登录", "编辑", "删除"}, 2,
                new Ui.ActionPick() {
                    @Override
                    public void onPick(int which) {
                        if (which == 0) {
                            String prev = prefs.activeServer() == null ? "" : prefs.activeServer().id;
                            login(p, prev);
                        } else if (which == 1) {
                            editServer(p);
                        } else {
                            confirmDelete(p);
                        }
                    }
                });
    }

    private void disableBuiltin(ServerProfile p) {
        p.disabled = true;
        prefs.upsertServer(p);
        if (prefs.activeServer() != null && prefs.activeServer().id.equals(p.id)) {
            String next = "";
            for (ServerProfile s : prefs.servers()) {
                if (!s.builtin) {
                    next = s.id;
                    break;
                }
            }
            prefs.setActiveServer(next);
        }
        render();
        Ui.toast(this, "内置服务器已停用，启用前无法使用");
    }

    private void confirmDelete(final ServerProfile p) {
        Ui.confirm(this, "删除「" + p.displayName() + "」？", "删除后本地不再保留该账号。", "删除",
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.removeServer(p.id);
                        render();
                    }
                });
    }

    /** 新增/编辑服务器弹窗：字段与按钮都在同一个布局里，遥控器上下可逐个选择 */
    private void editServer(final ServerProfile old) {
        if (editDialog != null && editDialog.isShowing()) {
            return;
        }
        final boolean isNew = old == null;
        View box = getLayoutInflater().inflate(R.layout.dialog_server_edit, null);
        final EditText name = box.findViewById(R.id.dialogName);
        final EditText url = box.findViewById(R.id.dialogUrl);
        final EditText user = box.findViewById(R.id.dialogUser);
        final EditText pass = box.findViewById(R.id.dialogPass);
        TextView title = box.findViewById(R.id.dialogTitle);
        TextView cancel = box.findViewById(R.id.dialogCancel);
        TextView save = box.findViewById(R.id.dialogSave);
        title.setText(isNew ? "新增服务器地址" : "编辑服务器");

        if (isNew) {
            name.setText("服务器 " + (prefs.servers().size() + 1));
            url.setText("");
            user.setText("");
            pass.setText("");
        } else {
            name.setText(old.name);
            url.setText(old.url);
            user.setText(old.user);
            pass.setText(old.pass);
        }

        // 遥控器：上下在四个输入框之间移动，OK 跳到下一个；到底部再到「保存」
        Ui.dpadInputs(name, url, user, pass);
        pass.setNextFocusDownId(R.id.dialogSave);
        save.setNextFocusUpId(R.id.dialogPass);
        cancel.setNextFocusRightId(R.id.dialogSave);
        save.setNextFocusLeftId(R.id.dialogCancel);

        final Dialog dlg = new Dialog(this, R.style.DialogTheme);
        dlg.setContentView(box);
        dlg.setCanceledOnTouchOutside(true);
        editDialog = dlg;
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                editDialog = null;
            }
        });

        View.OnClickListener submit = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = url.getText().toString().trim();
                if (u.isEmpty()) {
                    Ui.toast(ServersActivity.this, "请填写服务器地址");
                    url.requestFocus();
                    return;
                }
                if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    u = "http://" + u;
                }
                String prev = prefs.activeServer() == null ? "" : prefs.activeServer().id;
                ServerProfile p = isNew ? new ServerProfile() : old;
                if (isNew) {
                    p.id = "s" + System.currentTimeMillis();
                }
                String newName = name.getText().toString().trim();
                p.name = newName.isEmpty() ? "服务器" : newName;
                if (!u.equals(p.url)) {
                    p.cookie = "";
                }
                p.url = u;
                p.user = user.getText().toString().trim();
                p.pass = pass.getText().toString();
                p.builtin = false;
                p.disabled = false;
                prefs.upsertServer(p);
                prefs.setActiveServer(p.id);
                dlg.dismiss();
                login(p, prev);
            }
        };
        save.setOnClickListener(submit);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });

        try {
            dlg.show();
        } catch (Exception e) {
            editDialog = null;
            Ui.toast(this, "无法打开编辑窗口，请重试");
            return;
        }
        if (dlg.getWindow() != null) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.dimAmount = 0.62f;
            dlg.getWindow().setAttributes(lp);
            dlg.getWindow().setLayout(Math.min(Ui.dp(this, 620), (int) (w * 0.92f)),
                    (int) (h * 0.9f));
        }
        name.requestFocus();
        name.setSelection(name.getText().length());
    }

    /** 切换默认账号并登录 */
    private void login(final ServerProfile p, final String previousId) {
        String u = p.url == null ? "" : p.url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
            p.url = u;
            prefs.upsertServer(p);
        }
        prefs.setActiveServer(p.id);
        Ui.toast(this, "正在登录 " + p.displayName() + "…");
        App.get().api().login(p.url, p.user, p.pass, new Api.Cb<Boolean>() {
            @Override
            public void done(Boolean ok, String err) {
                if (ok != null && ok) {
                    Ui.toast(ServersActivity.this, "已切换到 " + p.displayName());
                } else {
                    if (previousId != null && !previousId.isEmpty() && !previousId.equals(p.id)) {
                        prefs.setActiveServer(previousId);
                    }
                    Ui.toast(ServersActivity.this, err == null ? "登录失败" : err);
                }
                render();
            }
        });
    }
}
