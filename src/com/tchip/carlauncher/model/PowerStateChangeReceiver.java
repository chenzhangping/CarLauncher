package com.tchip.carlauncher.model;

import com.tchip.carlauncher.Constant;
import com.tchip.carlauncher.MyApplication;
import com.tchip.carlauncher.service.RouteRecordService;
import com.tchip.carlauncher.service.SpeakService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.widget.Toast;

public class PowerStateChangeReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {

		if ("android.intent.action.ACTION_POWER_CONNECTED".equals(intent
				.getAction())) {
			// String strHintConnect = "";
			// Toast.makeText(context, "CONNECTED", Toast.LENGTH_SHORT).show();
			MyApplication.isPowerConnect = true;
			// 轨迹记录服务
			Intent intentRoute = new Intent(context, RouteRecordService.class);
			context.startService(intentRoute);
		} else if ("android.intent.action.ACTION_POWER_DISCONNECTED"
				.equals(intent.getAction())) {
			MyApplication.isPowerConnect = false;

			// 熄灭屏幕
			if (Constant.Module.autoCloseScreen) {
				context.sendBroadcast(new Intent("com.tchip.powerKey")
						.putExtra("value", "power"));
			}

			String strHintDisconnect = "电源断开";
			Toast.makeText(context, strHintDisconnect, Toast.LENGTH_SHORT)
					.show();
			Intent intentSpeak = new Intent(context, SpeakService.class);
			intentSpeak.putExtra("content", strHintDisconnect);
			context.startService(intentSpeak);

			// 停止轨迹记录服务，保存轨迹
			Intent intentRoute = new Intent(context, RouteRecordService.class);
			context.stopService(intentRoute);
		}
	}

}
