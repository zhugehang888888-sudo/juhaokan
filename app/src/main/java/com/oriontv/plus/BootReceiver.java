package com.oriontv.plus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启动广播（普通开机、快速开机、部分厂商的快速开机广播） */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!new Prefs(context).bootAutoStart()) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        // 启动流程可能涉及 su / 闹钟，放到后台线程，避免阻塞广播主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                AutoStart.launch(app, action);
            }
        }).start();
    }
}
