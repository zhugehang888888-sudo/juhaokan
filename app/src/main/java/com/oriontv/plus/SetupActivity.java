package com.oriontv.plus;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public class SetupActivity extends Activity {

    private final Prefs prefs = App.get().prefs();
    private EditText name;
    private EditText server;
    private EditText user;
    private EditText pass;
    private TextView status;
    private TextView btn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        name = findViewById(R.id.setupName);
        server = findViewById(R.id.setupServer);
        user = findViewById(R.id.setupUser);
        pass = findViewById(R.id.setupPass);
        status = findViewById(R.id.setupStatus);
        btn = findViewById(R.id.setupBtn);

        server.setText(prefs.server());
        user.setText(prefs.user());
        pass.setText(prefs.pass());

        // 手动版首次配置时允许给服务器起名，方便以后多服务器切换
        if (!BuildConfig.BUILT_IN_SERVER && prefs.servers().isEmpty()) {
            findViewById(R.id.setupNameLabel).setVisibility(View.VISIBLE);
            name.setVisibility(View.VISIBLE);
            name.setText("我的服务器");
        }

        // 品牌版在登录页展示出品方
        if (BuildConfig.SHOW_BRAND) {
            findViewById(R.id.setupBrand).setVisibility(View.VISIBLE);
        }

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                login();
            }
        });
        // 遥控器：上下左右切换输入框，OK 跳到下一个
        Ui.dpadInputs(name, server, user, pass);
        server.requestFocus();
    }

    private void login() {
        String s = server.getText().toString().trim();
        String u = user.getText().toString().trim();
        String p = pass.getText().toString().trim();
        if (s.isEmpty()) {
            status.setText("请填写服务器地址");
            return;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
            server.setText(s);
        }
        status.setText("正在登录…");
        btn.setEnabled(false);
        if (!BuildConfig.BUILT_IN_SERVER) {
            ServerProfile profile = prefs.activeServer();
            if (profile == null) {
                profile = new ServerProfile();
            }
            if (profile.id == null || profile.id.isEmpty()) {
                profile.id = "s" + System.currentTimeMillis();
            }
            String n = name.getText().toString().trim();
            profile.name = n.isEmpty() ? "我的服务器" : n;
            profile.url = s;
            profile.user = u;
            profile.pass = p;
            profile.builtin = false;
            prefs.upsertServer(profile);
            prefs.setActiveServer(profile.id);
        }
        App.get().api().login(s, u, p, new Api.Cb<Boolean>() {
            @Override
            public void done(Boolean ok, String err) {
                btn.setEnabled(true);
                if (ok != null && ok) {
                    status.setText("登录成功");
                    Ui.toast(SetupActivity.this, "登录成功");
                    finish();
                } else {
                    status.setText(err == null ? "登录失败" : err);
                }
            }
        });
    }
}
