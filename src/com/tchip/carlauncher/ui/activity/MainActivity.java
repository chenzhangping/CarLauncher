package com.tchip.carlauncher.ui.activity;

import java.io.File;

import com.tchip.carlauncher.Constant;
import com.tchip.carlauncher.Constant.Module;
import com.tchip.carlauncher.MyApp;
import com.tchip.carlauncher.MyApp.CameraState;
import com.tchip.carlauncher.R;
import com.tchip.carlauncher.model.DriveVideo;
import com.tchip.carlauncher.model.DriveVideoDbHelper;
import com.tchip.carlauncher.model.Typefaces;
import com.tchip.carlauncher.service.SensorWatchService;
import com.tchip.carlauncher.service.SleepOnOffService;
import com.tchip.carlauncher.util.HintUtil;
import com.tchip.carlauncher.util.ClickUtil;
import com.tchip.carlauncher.util.DateUtil;
import com.tchip.carlauncher.util.MyLog;
import com.tchip.carlauncher.util.NetworkUtil;
import com.tchip.carlauncher.util.OpenUtil;
import com.tchip.carlauncher.util.OpenUtil.MODULE_TYPE;
import com.tchip.carlauncher.util.SettingUtil;
import com.tchip.carlauncher.util.StorageUtil;
import com.tchip.carlauncher.view.AudioRecordDialog;
import com.tchip.carlauncher.view.MyScrollView;
import com.tchip.carlauncher.view.MyScrollViewListener;
import com.tchip.tachograph.TachographCallback;
import com.tchip.tachograph.TachographRecorder;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.hardware.Camera;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.SurfaceHolder.Callback;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import android.widget.TextView;

public class MainActivity extends Activity implements TachographCallback,
		Callback {

	private SharedPreferences sharedPreferences;
	private Editor editor;
	private DriveVideoDbHelper videoDb;

	/** 录像按钮 */
	private ImageView smallVideoRecord, largeVideoRecord;
	/** 拍照按钮 */
	private ImageView smallVideoCamera, largeVideoCamera;
	/** 加锁按钮 */
	private ImageView smallVideoLock, largeVideoLock;
	/** 静音按钮 */
	private ImageView largeVideoMute;
	/** 视频尺寸 */
	private ImageView largeVideoSize;
	/** 视频分段 */
	private ImageView largeVideoTime;
	/** 前后切换 */
	private ImageView imageCameraSwitch;
	/** 前后摄像头切换 */
	private LinearLayout layoutCameraSwitch;
	/** WiFi状态监听器 */
	private IntentFilter wifiIntentFilter;
	/** WiFi状态图标 */
	private ImageView imageWifiLevel;
	private ImageView imageSignalLevel, image3GType;
	/** 飞行模式图标 */
	private ImageView imageAirplane;
	/** 外置蓝牙图标 */
	private ImageView imageBluetooth;

	private RelativeLayout layoutLargeButton;
	private TextView textRecordTime;

	private MyScrollView hsvMain;

	private Camera camera;
	private SurfaceView surfaceCamera;
	private SurfaceHolder surfaceHolder;
	private TachographRecorder carRecorder;
	private boolean isSurfaceLarge = false;

	private int resolutionState, recordState, intervalState, pathState,
			secondaryState, overlapState, muteState;

	private LinearLayout layoutVideoSize, layoutVideoTime, layoutVideoLock,
			layoutVideoMute, layoutVideoRecord, layoutVideoCamera,
			layoutVideoRecordSmall, layoutVideoCameraSmall,
			layoutVideoLockSmall;

	private AudioRecordDialog audioRecordDialog;

	private TelephonyManager telephonyManager;
	private int simState;
	private MyPhoneStateListener myPhoneStateListener;
	private WifiManager wifiManager;
	private ConnectivityManager connManager;
	private NetworkInfo networkInfoWifi;
	private PowerManager powerManager;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.activity_main);

		sharedPreferences = getSharedPreferences(Constant.MySP.NAME,
				Context.MODE_PRIVATE);
		editor = sharedPreferences.edit();

		videoDb = new DriveVideoDbHelper(getApplicationContext()); // 视频数据库
		audioRecordDialog = new AudioRecordDialog(MainActivity.this); // 提示框
		powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE); // 获取屏幕状态
		// 3G信号
		myPhoneStateListener = new MyPhoneStateListener();
		telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		// SIM卡状态
		simState = telephonyManager.getSimState();
		telephonyManager.listen(myPhoneStateListener,
				PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

		initialLayout();
		initialCameraButton();
		SettingUtil.initialNodeState(MainActivity.this);
		setupRecordDefaults();
		setupRecordViews();

		// ACC上下电侦测服务
		Intent intentSleepOnOff = new Intent(MainActivity.this,
				SleepOnOffService.class);
		startService(intentSleepOnOff);

		// 首次启动是否需要自动录像
		if (1 == SettingUtil.getAccStatus()) {
			MyApp.isAccOn = true; // 同步ACC状态
			sendBroadcast(new Intent(Constant.Broadcast.AIRPLANE_OFF)); // 关闭飞行模式
			sendBroadcast(new Intent(Constant.Broadcast.GPS_ON)); // 打开GPS

			new Thread(new AutoThread()).start(); // 序列任务线程
		} else {
			MyApp.isAccOn = false; // 同步ACC状态
			MyApp.isSleeping = true; // ACC未连接,进入休眠
			MyLog.v("[MainActivity]ACC Check:OFF, Send Broadcast:com.tchip.SLEEP_ON.");

			sendBroadcast(new Intent(Constant.Broadcast.SLEEP_ON)); // 通知其他应用进入休眠
			sendBroadcast(new Intent(Constant.Broadcast.AIRPLANE_ON)); // 打开飞行模式
			sendBroadcast(new Intent(Constant.Broadcast.GPS_OFF)); // 关闭GPS
			SettingUtil.setEDogEnable(false); // 关闭电子狗电源
		}

		new Thread(new BackThread()).start(); // 后台线程
	}

	private NetworkStateReceiver networkStateReceiver;

	/** 监听飞行模式，外置蓝牙广播 */
	private class NetworkStateReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
				boolean isAirplaneOn = intent.getBooleanExtra("state", false);
				MyLog.v("[Main]AirplaneReceiver, isAirplaneOn:" + isAirplaneOn);
				setAirplaneIcon(isAirplaneOn);
				if (isAirplaneOn) {
					imageSignalLevel.setBackground(getResources().getDrawable(
							R.drawable.ic_qs_signal_no_signal));
					image3GType.setVisibility(View.GONE);
				} else {
					new Thread(new updateNetworkIconThread()).start();
				}
			} else if (action.equals(Constant.Broadcast.BT_CONNECTED)) {
				setBluetoothIcon(1);
			} else if (action.equals(Constant.Broadcast.BT_DISCONNECTED)) {
				setBluetoothIcon(0);
			}
		}

	}

	/** 设置飞行模式图标 */
	private void setAirplaneIcon(boolean isAirplaneOn) {
		imageAirplane.setBackground(getResources().getDrawable(
				isAirplaneOn ? R.drawable.ic_qs_airplane_on
						: R.drawable.ic_qs_airplane_off));
	}

	/** 设置外置蓝牙图标 */
	private void setBluetoothIcon(int bluetoothState) {
		boolean isExtBluetoothOn = NetworkUtil
				.isExtBluetoothOn(getApplicationContext());
		if (isExtBluetoothOn) {
			if (bluetoothState == -1) {
				bluetoothState = 0;
			}
		} else {
			bluetoothState = -1;
		}

		switch (bluetoothState) {
		case 0: // 打开未连接
			imageBluetooth.setBackground(getResources().getDrawable(
					R.drawable.ic_qs_bluetooth_not_connected));
			break;

		case 1: // 打开并连接
			imageBluetooth.setBackground(getResources().getDrawable(
					R.drawable.ic_qs_bluetooth_on));
			break;

		case -1: // 关闭
		default:
			imageBluetooth.setBackground(getResources().getDrawable(
					R.drawable.ic_qs_bluetooth_off));
			break;

		}
	}

	/**
	 * 序列任务线程，分步执行：
	 * 
	 * 1.初次启动清空录像文件夹
	 * 
	 * 2.自动录像
	 * 
	 * 3.初始化服务：轨迹记录
	 */
	public class AutoThread implements Runnable {

		@Override
		public void run() {
			try {
				// 初次启动清空录像文件夹
				if (sharedPreferences.getBoolean("isFirstLaunch", true)
						&& StorageUtil.isVideoCardExists()) {
					String sdcardPath = Constant.Path.SDCARD_2 + File.separator; // "/storage/sdcard2/";

					// File file = new File(sdcardPath + "tachograph/");
					// StorageUtil.RecursionDeleteFile(file);
					// MyLog.e("Delete video directory:tachograph !!!");

					editor.putBoolean("isFirstLaunch", false);
					editor.commit();
				} else {
					MyLog.e("Video card not exist or isn't first launch");
				}

				if (StorageUtil.isVideoCardExists()) {
					CheckErrorFile(); // 检查并删除异常视频文件
				}

				// 自动录像:如果已经在录像则不处理
				if (Constant.Record.autoRecord && !MyApp.isVideoReording) {
					Thread.sleep(Constant.Record.autoRecordDelay);
					Message message = new Message();
					message.what = 1;
					autoHandler.sendMessage(message);
				}
				Thread.sleep(1000);
				initialService();
			} catch (InterruptedException e) {
				e.printStackTrace();
				MyLog.e("[Main]AutoThread: Catch Exception!");
			}
		}
	}

	final Handler autoHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				startRecord();
				break;

			default:
				break;
			}
		}
	};

	/** 后台线程，用以监测是否需要录制碰撞加锁视频(停车侦测) */
	public class BackThread implements Runnable {

		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				Message message = new Message();
				message.what = 1;
				backHandler.sendMessage(message);
				// 修正标志：不对第二段视频加锁
				if (MyApp.isVideoLockSecond && !MyApp.isVideoReording) {
					MyApp.isVideoLockSecond = false;
				}
			}
		}
	}

	/**
	 * 后台线程的Handler,监测：
	 * 
	 * 1.是否需要休眠唤醒
	 * 
	 * 2.停车守卫侦测，启动录像
	 * 
	 * 3.ACC下电，拍照
	 * 
	 * 4.插入录像卡，若ACC在，启动录像
	 */
	final Handler backHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				if (!MyApp.isSleeping) {
					if (NetworkUtil.isWifiConnected(getApplicationContext())) {
						updateWiFiState();
					}

					if (MyApp.shouldWakeRecord) {
						// 序列任务线程
						new Thread(new AutoThread()).start();
						MyApp.shouldWakeRecord = false;
					}
				}

				if (MyApp.shouldMountRecord) {
					MyApp.shouldMountRecord = false;
					if (MyApp.isAccOn && !MyApp.isVideoReording) {
						new Thread(new RecordWhenMountThread()).start();
					}
				}

				if (MyApp.shouldCrashRecord) { // 停车侦测录像
					if (Constant.Record.parkVideoLock) { // 是否需要加锁
						MyApp.isVideoLock = true;
					}
					MyApp.shouldCrashRecord = false;
					if (!MyApp.isMainForeground) { // 发送Home键，回到主界面
						sendKeyCode(KeyEvent.KEYCODE_HOME);
					}
					new Thread(new RecordWhenCrashThread()).start();
				}

				if (MyApp.shouldTakePhotoWhenAccOff) { // ACC下电拍照
					MyApp.shouldTakePhotoWhenAccOff = false;
					MyApp.shouldSendPathToDSA = true;
					new Thread(new TakePhotoWhenAccOffThread()).start();
				}

				if (MyApp.shouldTakeVoicePhoto) { // 语音拍照
					MyApp.shouldTakeVoicePhoto = false;
					new Thread(new TakeVoicePhotoThread()).start();
				}
				break;

			default:
				break;
			}
		}
	};

	public class TakeVoicePhotoThread implements Runnable {
		@Override
		public void run() {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Message messageTakePhotoWhenAccOff = new Message();
			messageTakePhotoWhenAccOff.what = 2;
			takePhotoWhenEventHappenHandler
					.sendMessage(messageTakePhotoWhenAccOff);
		}
	}

	/** ACC下电拍照线程 */
	public class TakePhotoWhenAccOffThread implements Runnable {

		@Override
		public void run() {
			try {
				Thread.sleep(1500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Message messageTakePhotoWhenAccOff = new Message();
			messageTakePhotoWhenAccOff.what = 1;
			takePhotoWhenEventHappenHandler
					.sendMessage(messageTakePhotoWhenAccOff);
		}

	}

	/**
	 * 处理需要拍照事件：
	 * 
	 * 1.ACC_OFF，拍照给DSA
	 * 
	 * 2.语音拍照
	 */
	final Handler takePhotoWhenEventHappenHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				takePhotoWhenAccOff();
				break;

			case 2:
				takePhotoWhenVoiceCommand();
				break;

			default:
				break;
			}
		}
	};

	/** 插入录像卡录制一个视频线程 */
	public class RecordWhenMountThread implements Runnable {

		@Override
		public void run() {
			MyLog.v("[Main]run RecordWhenMountThread");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Message message = new Message();
			message.what = 1;
			recordWhenMountHandler.sendMessage(message);
		}

	}

	/** 底层碰撞后录制一个视频线程 */
	public class RecordWhenCrashThread implements Runnable {

		@Override
		public void run() {
			MyLog.v("[Thread]run RecordWhenCrashThread");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Message message = new Message();
			message.what = 1;
			recordWhenCrashHandler.sendMessage(message);
		}
	}

	/**
	 * 以下事件发生时录制视频：
	 * 
	 * 停车守卫：底层碰撞
	 */
	final Handler recordWhenCrashHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				try {
					if (recordState == Constant.Record.STATE_RECORD_STOPPED) {

						if (!MyApp.isMainForeground) { // 发送Home键，回到主界面
							sendKeyCode(KeyEvent.KEYCODE_HOME);
						}

						setInterval(3 * 60); // 防止在分段一分钟的时候，停车守卫录出1分和0秒两段视频

						new Thread(new StartRecordThread()).start(); // 开始录像
					}
					setupRecordViews();
					MyLog.v("[Record]isVideoReording:" + MyApp.isVideoReording);
				} catch (Exception e) {
					MyLog.e("[EventRecord]recordWhenEventHappenHandler catch exception: "
							+ e.toString());
				}
				break;

			default:
				break;
			}
		}
	};

	/** 插入视频卡时录制视频 */
	final Handler recordWhenMountHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				try {
					if (recordState == Constant.Record.STATE_RECORD_STOPPED) {

						if (!MyApp.isMainForeground) { // 发送Home键，回到主界面
							sendKeyCode(KeyEvent.KEYCODE_HOME);
						}

						new Thread(new StartRecordThread()).start(); // 开始录像
					}
					setupRecordViews();
					MyLog.v("[Record]isVideoReording:" + MyApp.isVideoReording);
				} catch (Exception e) {
					MyLog.e("[EventRecord]recordWhenEventHappenHandler catch exception: "
							+ e.toString());
				}
				break;

			default:
				break;
			}
		}
	};

	/** 更改分辨率后重启录像 */
	public class StartRecordWhenChangeSizeThread implements Runnable {

		@Override
		public void run() {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Message message = new Message();
			message.what = 1;
			startRecordWhenChangeSize.sendMessage(message);
		}
	}

	/** 更改录音/静音状态后重启录像 */
	public class StartRecordWhenChangeMuteThread implements Runnable {
		@Override
		public void run() {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Message message = new Message();
			message.what = 1;
			startRecordWhenChangeSize.sendMessage(message);
		}
	}

	final Handler startRecordWhenChangeSize = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				startRecord();
				break;

			default:
				break;
			}
		}
	};

	private class MyPhoneStateListener extends PhoneStateListener {

		/** 更新3G信号强度 */
		@Override
		public void onSignalStrengthsChanged(SignalStrength signalStrength) {

			update3GSignalStrength(signalStrength.getGsmSignalStrength());
			update3GType();
			super.onSignalStrengthsChanged(signalStrength);
		}

		@Override
		public void onDataConnectionStateChanged(int state) {

			switch (state) {
			case TelephonyManager.DATA_DISCONNECTED:// 网络断开
				MyLog.v("3G TelephonyManager.DATA_DISCONNECTED");
				break;

			case TelephonyManager.DATA_CONNECTING:// 网络正在连接
				MyLog.v("3G TelephonyManager.DATA_CONNECTING");
				break;

			case TelephonyManager.DATA_CONNECTED:// 网络连接上
				MyLog.v("3G TelephonyManager.DATA_CONNECTED");
				break;
			}

			update3GType();
		}
	}

	/**
	 * 更新3G状态
	 * 
	 * SIM_STATE_UNKNOWN = 0:Unknown.
	 * 
	 * SIM_STATE_ABSENT = 1:no SIM card is available in the device
	 * 
	 * SIM_STATE_PIN_REQUIRED = 2:requires the user's SIM PIN to unlock
	 * 
	 * SIM_STATE_PUK_REQUIRED = 3:requires the user's SIM PUK to unlock
	 * 
	 * SIM_STATE_NETWORK_LOCKED = 4:requires a network PIN to unlock
	 * 
	 * SIM_STATE_READY = 5:Ready
	 * 
	 */
	private void update3GSignalStrength(int signal) {
		// imageSignalLevel,image3G.setVisibility(View.GONE);
		simState = telephonyManager.getSimState();
		MyLog.v("[update3GState]SIM State:" + simState);

		if (NetworkUtil.isAirplaneModeOn(getApplicationContext())) {
			imageSignalLevel.setBackground(getResources().getDrawable(
					R.drawable.ic_qs_signal_no_signal));
			image3GType.setVisibility(View.GONE);
		} else if (simState == TelephonyManager.SIM_STATE_READY) {
			imageSignalLevel.setBackground(getResources().getDrawable(
					NetworkUtil.get3GLevelImageByGmsSignalStrength(signal)));

			if (signal > 31 || signal < 0) {
				image3GType.setVisibility(View.GONE);
			} else {
				image3GType.setVisibility(View.VISIBLE);
			}
		} else if (simState == TelephonyManager.SIM_STATE_UNKNOWN
				|| simState == TelephonyManager.SIM_STATE_ABSENT) {
			imageSignalLevel.setBackground(getResources().getDrawable(
					R.drawable.ic_qs_signal_no_signal));
			image3GType.setVisibility(View.GONE);
		}
	}

	/** 更新3G图标 */
	private void update3GType() {
		int networkType = telephonyManager.getNetworkType();

		MyLog.v("[update3Gtype]NetworkType:" + networkType);

		// image3GType.setBackground(getResources().getDrawable(
		// NetworkUtil.get3GTypeImageByNetworkType(networkType)));
	}

	/**
	 * 初始化服务:
	 * 
	 * 1.碰撞侦测服务
	 * 
	 * 2.轨迹记录服务
	 * 
	 * 3.天气播报服务
	 */
	private void initialService() {
		// 碰撞侦测服务
		Intent intentSensor = new Intent(this, SensorWatchService.class);
		startService(intentSensor);

		// 轨迹记录
		if (Constant.Module.hasRoute) {
			Intent intentRoute = new Intent();
			intentRoute.setClassName("com.tchip.route",
					"com.tchip.route.service.RouteRecordService");
			startService(intentRoute);
		}

		// 天气播报
		Intent intentWeather = new Intent();
		intentWeather.setClassName("com.tchip.weather",
				"com.tchip.weather.service.TimeTickService");
		startService(intentWeather);
	}

	private void initialCameraSurface() {
		surfaceCamera = (SurfaceView) findViewById(R.id.surfaceCamera);
		surfaceCamera.setOnClickListener(new MyOnClickListener());
		surfaceCamera.getHolder().addCallback(this);
	}

	/** 初始化布局 */
	private void initialLayout() {
		// 录像窗口
		initialCameraSurface();

		textRecordTime = (TextView) findViewById(R.id.textRecordTime);
		textRecordTime.setTypeface(Typefaces.get(this, Constant.Path.FONT
				+ "Font-Quartz-Regular.ttf"));

		// 增大点击区域
		layoutVideoSize = (LinearLayout) findViewById(R.id.layoutVideoSize);
		layoutVideoSize.setOnClickListener(new MyOnClickListener());

		layoutVideoTime = (LinearLayout) findViewById(R.id.layoutVideoTime);
		layoutVideoTime.setOnClickListener(new MyOnClickListener());

		layoutVideoLock = (LinearLayout) findViewById(R.id.layoutVideoLock);
		layoutVideoLock.setOnClickListener(new MyOnClickListener());

		layoutVideoMute = (LinearLayout) findViewById(R.id.layoutVideoMute);
		layoutVideoMute.setOnClickListener(new MyOnClickListener());

		layoutVideoRecord = (LinearLayout) findViewById(R.id.layoutVideoRecord);
		layoutVideoRecord.setOnClickListener(new MyOnClickListener());

		layoutVideoCamera = (LinearLayout) findViewById(R.id.layoutVideoCamera);
		layoutVideoCamera.setOnClickListener(new MyOnClickListener());

		layoutVideoRecordSmall = (LinearLayout) findViewById(R.id.layoutVideoRecordSmall);
		layoutVideoRecordSmall.setOnClickListener(new MyOnClickListener());

		layoutVideoCameraSmall = (LinearLayout) findViewById(R.id.layoutVideoCameraSmall);
		layoutVideoCameraSmall.setOnClickListener(new MyOnClickListener());

		layoutVideoLockSmall = (LinearLayout) findViewById(R.id.layoutVideoLockSmall);
		layoutVideoLockSmall.setOnClickListener(new MyOnClickListener());

		layoutCameraSwitch = (LinearLayout) findViewById(R.id.layoutCameraSwitch);
		layoutCameraSwitch.setOnClickListener(new MyOnClickListener());

		// 天气预报和时钟,状态图标
		RelativeLayout layoutWeather = (RelativeLayout) findViewById(R.id.layoutWeather);
		layoutWeather.setOnClickListener(new MyOnClickListener());
		TextClock textClock = (TextClock) findViewById(R.id.textClock);
		textClock.setTypeface(Typefaces.get(this, Constant.Path.FONT
				+ "Font-Helvetica-Neue-LT-Pro.otf"));

		TextClock textDate = (TextClock) findViewById(R.id.textDate);
		textDate.setTypeface(Typefaces.get(this, Constant.Path.FONT
				+ "Font-Helvetica-Neue-LT-Pro.otf"));

		TextClock textWeek = (TextClock) findViewById(R.id.textWeek);
		textWeek.setTypeface(Typefaces.get(this, Constant.Path.FONT
				+ "Font-Helvetica-Neue-LT-Pro.otf"));

		LinearLayout layoutWiFi = (LinearLayout) findViewById(R.id.layoutWiFi);
		layoutWiFi.setOnClickListener(new MyOnClickListener());

		// WiFi状态信息
		imageWifiLevel = (ImageView) findViewById(R.id.imageWifiLevel);

		wifiIntentFilter = new IntentFilter();
		wifiIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		wifiIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
		wifiIntentFilter.setPriority(Integer.MAX_VALUE);
		updateWiFiState();

		// 3G状态信息
		imageSignalLevel = (ImageView) findViewById(R.id.imageSignalLevel);
		image3GType = (ImageView) findViewById(R.id.image3GType);
		image3GType.setVisibility(View.GONE);

		imageAirplane = (ImageView) findViewById(R.id.imageAirplane);
		imageBluetooth = (ImageView) findViewById(R.id.imageBluetooth);

		// 导航
		ImageView imageNavi = (ImageView) findViewById(R.id.imageNavi);
		imageNavi.setOnClickListener(new MyOnClickListener());

		// 在线音乐
		ImageView imageMusicOL = (ImageView) findViewById(R.id.imageMusicOL);
		imageMusicOL.setOnClickListener(new MyOnClickListener());

		// 电子狗
		ImageView imageEDog = (ImageView) findViewById(R.id.imageEDog);
		imageEDog.setOnClickListener(new MyOnClickListener());

		// 网络电台：喜马拉雅
		RelativeLayout layoutXimalaya = (RelativeLayout) findViewById(R.id.layoutXimalaya);
		layoutXimalaya.setVisibility(Constant.Module.hasOnlineFM ? View.VISIBLE
				: View.GONE);
		ImageView imageXimalaya = (ImageView) findViewById(R.id.imageXimalaya);
		imageXimalaya.setOnClickListener(new MyOnClickListener());

		// 多媒体
		ImageView imageMultimedia = (ImageView) findViewById(R.id.imageMultimedia);
		imageMultimedia.setOnClickListener(new MyOnClickListener());

		// 文件管理
		ImageView imageFileExplore = (ImageView) findViewById(R.id.imageFileExplore);
		imageFileExplore.setOnClickListener(new MyOnClickListener());

		// 行驶轨迹
		ImageView imageRouteTrack = (ImageView) findViewById(R.id.imageRouteTrack);
		imageRouteTrack.setOnClickListener(new MyOnClickListener());
		RelativeLayout layoutRoute = (RelativeLayout) findViewById(R.id.layoutRoute);
		layoutRoute.setVisibility(Constant.Module.hasRoute ? View.VISIBLE
				: View.GONE);

		// FM发射
		ImageView imageFmTransmit = (ImageView) findViewById(R.id.imageFmTransmit);
		imageFmTransmit.setOnClickListener(new MyOnClickListener());

		// 拨号
		ImageView imageDialer = (ImageView) findViewById(R.id.imageDialer);
		imageDialer.setOnClickListener(new MyOnClickListener());

		if (Constant.Module.hasDialer) {
			// 短信
			ImageView imageMessage = (ImageView) findViewById(R.id.imageMessage);
			imageMessage.setOnClickListener(new MyOnClickListener());
		}

		// 微信助手
		RelativeLayout layoutWechat = (RelativeLayout) findViewById(R.id.layoutWechat);
		layoutWechat.setVisibility(Constant.Module.hasWechat ? View.VISIBLE
				: View.GONE);
		ImageView imageWechat = (ImageView) findViewById(R.id.imageWechat);
		imageWechat.setOnClickListener(new MyOnClickListener());

		// 微密
		RelativeLayout layoutWeme = (RelativeLayout) findViewById(R.id.layoutWeme);
		layoutWeme.setVisibility(Constant.Module.hasWeme ? View.VISIBLE
				: View.GONE);
		ImageView imageWeme = (ImageView) findViewById(R.id.imageWeme);
		imageWeme.setOnClickListener(new MyOnClickListener());

		hsvMain = (MyScrollView) findViewById(R.id.hsvMain);
		hsvMain.setDrawingCacheEnabled(true);
		if (Constant.Module.isIconAtom) {
			hsvMain.setMyScrollViewListener(new MyScrollViewListener() {

				@Override
				public void onScrollChanged(MyScrollView scrollView, int x,
						int y, int oldx, int oldy) {

					MyLog.v("x:" + x + ",oldx:" + oldx + ",isScroll:"
							+ hsvMain.isScroll() + ",getScrollX:"
							+ hsvMain.getScrollX());

					if (!hsvMain.isScroll()) {
						hsvMain.fancyScroll(hsvMain.getScrollX(), true);
					}

				}
			});

		}
	}

	/** 初始化录像按钮 */
	private void initialCameraButton() {
		// ********** 小视图 **********
		// 录制
		smallVideoRecord = (ImageView) findViewById(R.id.smallVideoRecord);
		smallVideoRecord.setOnClickListener(new MyOnClickListener());

		// 锁定
		smallVideoLock = (ImageView) findViewById(R.id.smallVideoLock);
		smallVideoLock.setOnClickListener(new MyOnClickListener());

		// 拍照
		smallVideoCamera = (ImageView) findViewById(R.id.smallVideoCamera);
		smallVideoCamera.setOnClickListener(new MyOnClickListener());

		// ********** 大视图 **********
		layoutLargeButton = (RelativeLayout) findViewById(R.id.layoutLargeButton);

		// 视频尺寸
		largeVideoSize = (ImageView) findViewById(R.id.largeVideoSize);
		largeVideoSize.setOnClickListener(new MyOnClickListener());

		// 视频分段长度
		largeVideoTime = (ImageView) findViewById(R.id.largeVideoTime);
		largeVideoTime.setOnClickListener(new MyOnClickListener());

		// 锁定
		largeVideoLock = (ImageView) findViewById(R.id.largeVideoLock);
		largeVideoLock.setOnClickListener(new MyOnClickListener());

		// 静音
		largeVideoMute = (ImageView) findViewById(R.id.largeVideoMute);
		largeVideoMute.setOnClickListener(new MyOnClickListener());

		// 录制
		largeVideoRecord = (ImageView) findViewById(R.id.largeVideoRecord);
		largeVideoRecord.setOnClickListener(new MyOnClickListener());

		// 拍照
		largeVideoCamera = (ImageView) findViewById(R.id.largeVideoCamera);
		largeVideoCamera.setOnClickListener(new MyOnClickListener());

		// 切换前后图标
		imageCameraSwitch = (ImageView) findViewById(R.id.imageCameraSwitch);
		imageCameraSwitch.setOnClickListener(new MyOnClickListener());

		updateButtonState(isSurfaceLarge());
	}

	/** 切换录像预览窗口的大小 */
	private void updateSurfaceState() {
		if (!isSurfaceLarge) {
			// 16/9 = 1.7778;854/480 = 1.7791
			int widthFull = 854; // 854;
			int heightFull = 480;
			surfaceCamera.setLayoutParams(new RelativeLayout.LayoutParams(
					widthFull, heightFull));
			isSurfaceLarge = true;

			updateButtonState(true);
		} else {
			int widthSmall = 490; // 480
			int heightSmall = 276; // 270
			surfaceCamera.setLayoutParams(new RelativeLayout.LayoutParams(
					widthSmall, heightSmall));
			isSurfaceLarge = false;

			// 更新HorizontalScrollView阴影
			hsvMain.scrollTo(0, 0);

			updateButtonState(false);
		}
	}

	/**
	 * 更新录像大小按钮显示状态
	 * 
	 * @param isSurfaceLarge
	 */
	private void updateButtonState(boolean isLarge) {
		smallVideoRecord.setVisibility(isLarge ? View.INVISIBLE : View.VISIBLE);
		smallVideoLock.setVisibility(isLarge ? View.INVISIBLE : View.VISIBLE);
		smallVideoCamera.setVisibility(isLarge ? View.INVISIBLE : View.VISIBLE);
		layoutLargeButton
				.setVisibility(isLarge ? View.VISIBLE : View.INVISIBLE);
	}

	private boolean isSurfaceLarge() {
		return isSurfaceLarge;
	}

	private int secondCount = -1;

	/**
	 * 录制时间秒钟复位:
	 * 
	 * 1.停止录像{@link #stopRecorder()}
	 * 
	 * 2.录像过程中更改录像分辨率
	 * 
	 * 3.录像过程中更改静音状态
	 * 
	 * 4.视频保存失败{@link #onError(int)}
	 * 
	 * 5.开始录像{@link #startRecordTask()}
	 * 
	 */
	private void resetRecordTimeText() {
		secondCount = -1;
		textRecordTime.setText("00 : 00");
	}

	public class updateRecordTimeThread implements Runnable {

		@Override
		public void run() {
			// 解决录像时，快速点击录像按钮两次，线程叠加跑秒过快的问题
			synchronized (updateRecordTimeHandler) {
				do {
					if (MyApp.isCrashed) {
						Message messageVideoLock = new Message();
						messageVideoLock.what = 4;
						updateRecordTimeHandler.sendMessage(messageVideoLock);
					}

					if (MyApp.isVideoCardEject) { // 录像时视频SD卡拔出停止录像
						MyLog.e("SD card remove badly or power unconnected, stop record!");
						Message messageEject = new Message();
						messageEject.what = 2;
						updateRecordTimeHandler.sendMessage(messageEject);
						return;
					} else if (!MyApp.isPowerConnect) { // 电源断开
						MyLog.e("Stop Record:Power is unconnected");
						Message messagePowerUnconnect = new Message();
						messagePowerUnconnect.what = 3;
						updateRecordTimeHandler
								.sendMessage(messagePowerUnconnect);
						return;
					} else if (!MyApp.isAccOn
							&& !MyApp.shouldStopWhenCrashVideoSave) { // ACC下电停止录像
						MyLog.e("Stop Record:isSleeping = true");
						Message messageSleep = new Message();
						messageSleep.what = 5;
						updateRecordTimeHandler.sendMessage(messageSleep);
						return;
					} else if (MyApp.shouldStopRecordFromVoice) {
						MyApp.shouldStopRecordFromVoice = false;
						Message messageStopRecordFromVoice = new Message();
						messageStopRecordFromVoice.what = 6;
						updateRecordTimeHandler
								.sendMessage(messageStopRecordFromVoice);
						return;
					} else {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
						Message messageSecond = new Message();
						messageSecond.what = 1;
						updateRecordTimeHandler.sendMessage(messageSecond);
					}
				} while (MyApp.isVideoReording);
			}
		}
	}

	final Handler updateRecordTimeHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1: // 处理停车守卫录像
				secondCount++;
				if (MyApp.shouldStopWhenCrashVideoSave && MyApp.isVideoReording) {
					if (secondCount == Constant.Record.parkVideoLength) {
						String videoTimeStr = sharedPreferences.getString(
								"videoTime", "3");
						intervalState = "1".equals(videoTimeStr) ? Constant.Record.STATE_INTERVAL_1MIN
								: Constant.Record.STATE_INTERVAL_3MIN;

						// 停止录像
						if (stopRecorder() == 0) {
							recordState = Constant.Record.STATE_RECORD_STOPPED;
							MyApp.isVideoReording = false;

							setInterval(("1".equals(videoTimeStr)) ? 1 * 60
									: 3 * 60); // 重设视频分段

							setupRecordViews();
							releaseCameraZone();
						} else {
							if (stopRecorder() == 0) {
								recordState = Constant.Record.STATE_RECORD_STOPPED;
								MyApp.isVideoReording = false;

								setInterval(("1".equals(videoTimeStr)) ? 1 * 60
										: 3 * 60); // 重设视频分段

								setupRecordViews();
								releaseCameraZone();
							}
						}

						// 熄灭屏幕,判断当前屏幕是否关闭
						PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
						boolean isScreenOn = powerManager.isScreenOn();
						if (isScreenOn) {
							sendBroadcast(new Intent("com.tchip.SLEEP_ON"));
							// sendBroadcast(new Intent("com.tchip.powerKey")
							// .putExtra("value", "power"));
						}
					}
				}

				switch (intervalState) { // 重置时间
				case Constant.Record.STATE_INTERVAL_3MIN:
					if (secondCount >= 180) {
						secondCount = 0;
					}
					break;

				case Constant.Record.STATE_INTERVAL_1MIN:
					if (secondCount >= 60) {
						secondCount = 0;
					}
					break;

				default:
					break;
				}

				textRecordTime.setText(DateUtil
						.getFormatTimeBySecond(secondCount));

				break;

			case 2: // SD卡异常移除：停止录像
				if (stopRecorder() == 0) {
					recordState = Constant.Record.STATE_RECORD_STOPPED;
					MyApp.isVideoReording = false;
					setupRecordViews();
					releaseCameraZone();
				} else {
					if (stopRecorder() == 0) {
						recordState = Constant.Record.STATE_RECORD_STOPPED;
						MyApp.isVideoReording = false;
						setupRecordViews();
						releaseCameraZone();
					}
				}

				String strVideoCardEject = getResources().getString(
						R.string.hint_sd_remove_badly);
				HintUtil.showToast(MainActivity.this, strVideoCardEject);

				MyLog.e("CardEjectReceiver:Video SD Removed");
				HintUtil.speakVoice(MainActivity.this, strVideoCardEject);
				audioRecordDialog.showErrorDialog(strVideoCardEject);
				new Thread(new dismissDialogThread()).start();
				break;

			case 3: // 电源断开，停止录像
				if (stopRecorder() == 0) {
					recordState = Constant.Record.STATE_RECORD_STOPPED;
					MyApp.isVideoReording = false;
					setupRecordViews();

					releaseCameraZone();
				} else {
					if (stopRecorder() == 0) {
						recordState = Constant.Record.STATE_RECORD_STOPPED;
						MyApp.isVideoReording = false;
						setupRecordViews();

						releaseCameraZone();
					}
				}

				String strPowerUnconnect = getResources().getString(
						R.string.hint_stop_record_power_unconnect);
				HintUtil.showToast(MainActivity.this, strPowerUnconnect);
				HintUtil.speakVoice(MainActivity.this, strPowerUnconnect);

				MyLog.e("Record Stop:power unconnect.");
				audioRecordDialog.showErrorDialog(strPowerUnconnect);
				new Thread(new dismissDialogThread()).start();
				break;

			case 4:
				MyApp.isCrashed = false;

				// 碰撞后判断是否需要加锁第二段视频
				if (intervalState == Constant.Record.STATE_INTERVAL_1MIN) {
					if (secondCount > 45) {
						MyApp.isVideoLockSecond = true;
					}
				} else if (intervalState == Constant.Record.STATE_INTERVAL_3MIN) {
					if (secondCount > 165) {
						MyApp.isVideoLockSecond = true;
					}
				}
				setupRecordViews();
				break;

			case 5: // 进入休眠，停止录像
				if (stopRecorder() == 0) {
					recordState = Constant.Record.STATE_RECORD_STOPPED;
					MyApp.isVideoReording = false;
					setupRecordViews();
					releaseCameraZone();
				} else {
					if (stopRecorder() == 0) {
						recordState = Constant.Record.STATE_RECORD_STOPPED;
						MyApp.isVideoReording = false;
						setupRecordViews();
						releaseCameraZone();
					}
				}
				// 如果此时屏幕为点亮状态，则不回收
				boolean isScreenOn = powerManager.isScreenOn();
				if (!isScreenOn) {
					releaseCameraZone();
				}

				MyApp.shouldResetRecordWhenResume = true;
				break;

			case 6:// 语音命令：停止录像
				if (stopRecorder() == 0) {
					recordState = Constant.Record.STATE_RECORD_STOPPED;
					MyApp.isVideoReording = false;
					setupRecordViews();

					releaseCameraZone();
				} else {
					if (stopRecorder() == 0) {
						recordState = Constant.Record.STATE_RECORD_STOPPED;
						MyApp.isVideoReording = false;
						setupRecordViews();

						releaseCameraZone();
					}
				}

				break;

			default:
				break;
			}
		}
	};

	/** 更新WiF状态 */
	private void updateWiFiState() {
		wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		networkInfoWifi = connManager
				.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

		if (wifiManager.isWifiEnabled() && networkInfoWifi.isConnected()) {
			int level = ((WifiManager) getSystemService(WIFI_SERVICE))
					.getConnectionInfo().getRssi();// Math.abs()
			imageWifiLevel.setImageResource(NetworkUtil
					.getWifiImageBySignal(level));
		} else {
			imageWifiLevel.setImageResource(R.drawable.ic_qs_wifi_no_network);
		}
	}

	class MyOnClickListener implements View.OnClickListener {
		@Override
		public void onClick(View v) {
			switch (v.getId()) {
			case R.id.surfaceCamera:
				updateSurfaceState();
				break;

			case R.id.smallVideoRecord:
			case R.id.largeVideoRecord:
			case R.id.layoutVideoRecord:
			case R.id.layoutVideoRecordSmall:
				if (!ClickUtil.isQuickClick(1000)) {
					if (recordState == Constant.Record.STATE_RECORD_STOPPED) {
						if (StorageUtil.isVideoCardExists()) {
							HintUtil.speakVoice(
									MainActivity.this,
									getResources().getString(
											R.string.hint_record_start));
							startRecord();
						} else {
							noVideoSDHint();
						}
					} else if (recordState == Constant.Record.STATE_RECORD_STARTED) {
						HintUtil.speakVoice(MainActivity.this, getResources()
								.getString(R.string.hint_record_stop));
						stopRecord();
					}

				}
				break;

			case R.id.smallVideoLock:
			case R.id.largeVideoLock:
			case R.id.layoutVideoLock:
			case R.id.layoutVideoLockSmall:
				if (!ClickUtil.isQuickClick(800)) {
					if (MyApp.isVideoReording) {
						lockOrUnlockVideo();
					} else {
						HintUtil.showToast(MainActivity.this, "未录像");
					}
				}
				break;

			case R.id.largeVideoSize:
			case R.id.layoutVideoSize:
				if (!ClickUtil.isQuickClick(1500)) {
					// 切换分辨率录像停止，需要重置时间
					MyApp.shouldVideoRecordWhenChangeSize = MyApp.isVideoReording;
					MyApp.isVideoReording = false;
					resetRecordTimeText();
					textRecordTime.setVisibility(View.INVISIBLE);

					if (resolutionState == Constant.Record.STATE_RESOLUTION_1080P) {
						setResolution(Constant.Record.STATE_RESOLUTION_720P);
						editor.putString("videoSize", "720");
						recordState = Constant.Record.STATE_RECORD_STOPPED;
						HintUtil.speakVoice(MainActivity.this, getResources()
								.getString(R.string.hint_video_size_720));
					} else if (resolutionState == Constant.Record.STATE_RESOLUTION_720P) {
						setResolution(Constant.Record.STATE_RESOLUTION_1080P);
						editor.putString("videoSize", "1080");
						recordState = Constant.Record.STATE_RECORD_STOPPED;
						HintUtil.speakVoice(MainActivity.this, getResources()
								.getString(R.string.hint_video_size_1080));
					}
					editor.commit();
					setupRecordViews();

					// 修改分辨率后按需启动录像
					if (MyApp.shouldVideoRecordWhenChangeSize) {
						new Thread(new StartRecordWhenChangeSizeThread())
								.start();
						MyApp.shouldVideoRecordWhenChangeSize = false;
					}
				}
				break;

			case R.id.largeVideoTime:
			case R.id.layoutVideoTime:
				if (!ClickUtil.isQuickClick(800)) {
					if (intervalState == Constant.Record.STATE_INTERVAL_3MIN) {
						if (setInterval(1 * 60) == 0) {
							intervalState = Constant.Record.STATE_INTERVAL_1MIN;
							editor.putString("videoTime", "1");
							HintUtil.speakVoice(
									MainActivity.this,
									getResources().getString(
											R.string.hint_video_time_1));
						}
					} else if (intervalState == Constant.Record.STATE_INTERVAL_1MIN) {
						if (setInterval(3 * 60) == 0) {
							intervalState = Constant.Record.STATE_INTERVAL_3MIN;
							editor.putString("videoTime", "3");
							HintUtil.speakVoice(
									MainActivity.this,
									getResources().getString(
											R.string.hint_video_time_3));
						}
					}
					editor.commit();
					setupRecordViews();
				}
				break;

			case R.id.largeVideoMute:
			case R.id.layoutVideoMute:
				if (!ClickUtil.isQuickClick(800)) {
					// 切换录音/静音状态停止录像，需要重置时间
					MyApp.shouldVideoRecordWhenChangeSize = MyApp.isVideoReording;

					if (MyApp.isVideoReording) {
						resetRecordTimeText();
						textRecordTime.setVisibility(View.INVISIBLE);
						MyApp.isVideoReording = false;

						if (StorageUtil.isVideoCardExists()) {
							startRecord();
						} else {
							noVideoSDHint();
						}
					}
					if (muteState == Constant.Record.STATE_MUTE) {
						if (setMute(false) == 0) {
							muteState = Constant.Record.STATE_UNMUTE;
							HintUtil.speakVoice(
									MainActivity.this,
									getResources().getString(
											R.string.hint_video_mute_off));
							editor.putBoolean("videoMute", false);
							editor.commit();
						}
					} else if (muteState == Constant.Record.STATE_UNMUTE) {
						if (setMute(true) == 0) {
							muteState = Constant.Record.STATE_MUTE;
							HintUtil.speakVoice(
									MainActivity.this,
									getResources().getString(
											R.string.hint_video_mute_on));
							editor.putBoolean("videoMute", true);
							editor.commit();
						}
					}

					setupRecordViews();

					// 修改录音/静音后按需还原录像状态
					if (MyApp.shouldVideoRecordWhenChangeSize) {
						new Thread(new StartRecordWhenChangeMuteThread())
								.start();
						MyApp.shouldVideoRecordWhenChangeSize = false;
					}
				}
				break;

			case R.id.smallVideoCamera:
			case R.id.largeVideoCamera:
			case R.id.layoutVideoCamera:
			case R.id.layoutVideoCameraSmall:
				if (!ClickUtil.isQuickClick(500)) {
					takePhoto();
				}
				break;

			case R.id.imageCameraSwitch:
			case R.id.layoutCameraSwitch:
				sendBroadcast(new Intent("com.tchip.showUVC"));
				break;

			case R.id.layoutWeather:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.WEATHER);
				break;

			case R.id.imageNavi:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.NAVI_GAODE);
				break;

			case R.id.imageMusicOL:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.MUSIC);
				break;

			case R.id.imageEDog:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.EDOG);
				break;

			case R.id.imageXimalaya:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.XIMALAYA);
				break;

			case R.id.imageMultimedia:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.MULTIMEDIA);
				break;

			case R.id.imageRouteTrack:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.ROUTE);
				break;

			case R.id.imageFmTransmit:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.FMTRANSMIT);
				break;

			case R.id.imageFileExplore:
				OpenUtil.openModule(MainActivity.this,
						MODULE_TYPE.FILE_MANAGER_MTK);
				break;

			case R.id.imageDialer:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.DIALER);
				break;

			case R.id.imageMessage:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.MMS);
				break;

			case R.id.imageWeme:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.WEME);
				break;

			case R.id.imageWechat:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.WECHAT);
				break;

			case R.id.layoutWiFi:
				OpenUtil.openModule(MainActivity.this, MODULE_TYPE.WIFI);
				break;

			default:
				break;
			}
		}
	}

	/** 启动录像 */
	private void startRecord() {
		try {
			if (recordState == Constant.Record.STATE_RECORD_STOPPED) {
				if (MyApp.isSleeping) {
					HintUtil.speakVoice(MainActivity.this, getResources()
							.getString(R.string.hint_stop_record_sleeping));
				} else {
					if (!powerManager.isScreenOn()) { // 点亮屏幕
						SettingUtil.lightScreen(getApplicationContext());
					}

					if (!MyApp.isMainForeground) { // 发送Home键，回到主界面
						sendKeyCode(KeyEvent.KEYCODE_HOME);
					}
					new Thread(new StartRecordThread()).start(); // 开始录像
				}
			} else {
				MyLog.v("[startRecord]Already record yet");
			}
			setupRecordViews();
			MyLog.v("MyApplication.isVideoReording:" + MyApp.isVideoReording);
		} catch (Exception e) {
			MyLog.e("[MainActivity]startOrStopRecord catch exception: "
					+ e.toString());
		}
	}

	/** 停止录像 */
	private void stopRecord() {
		try {
			if (recordState == Constant.Record.STATE_RECORD_STARTED) {
				if (stopRecorder() == 0) {
					recordState = Constant.Record.STATE_RECORD_STOPPED;
					MyApp.isVideoReording = false;
					if (MyApp.shouldStopWhenCrashVideoSave) {
						MyApp.shouldStopWhenCrashVideoSave = false;
					}
					releaseCameraZone();
				}
			}
			setupRecordViews();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/** 加锁或加锁视频 */
	private void lockOrUnlockVideo() {
		if (!MyApp.isVideoLock) {
			MyApp.isVideoLock = true;
			HintUtil.speakVoice(MainActivity.this,
					getResources().getString(R.string.hint_video_lock));
		} else {
			MyApp.isVideoLock = false;
			MyApp.isVideoLockSecond = false;
			HintUtil.speakVoice(MainActivity.this,
					getResources().getString(R.string.hint_video_unlock));
		}
		setupRecordViews();
	}

	/** WiFi状态Receiver */
	private BroadcastReceiver wifiIntentReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int wifi_state = intent.getIntExtra("wifi_state", 0);
			int level = ((WifiManager) getSystemService(WIFI_SERVICE))
					.getConnectionInfo().getRssi(); // Math.abs()
			updateWiFiState();
			MyLog.v("wifiIntentReceiver, Wifi Level:" + level);

			switch (wifi_state) {
			case WifiManager.WIFI_STATE_ENABLED:
				updateWiFiState();
				new Thread(new updateNetworkIconThread()).start();
				break;

			case WifiManager.WIFI_STATE_DISABLED:
			case WifiManager.WIFI_STATE_UNKNOWN:
				updateWiFiState();
				break;

			case WifiManager.WIFI_STATE_ENABLING:
			case WifiManager.WIFI_STATE_DISABLING:
			default:
				break;
			}
		}
	};

	/** 更新wifi图标和3G图标 */
	public class updateNetworkIconThread implements Runnable {
		@Override
		public void run() {
			synchronized (updateNetworkIconHandler) {
				int updateWifiTime = 1;
				boolean shouldUpdateWifi = true;
				while (shouldUpdateWifi) {
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					MyLog.v("[Main]updateWifiThread:Refresh Wifi! "
							+ updateWifiTime);
					Message messageWifi = new Message();
					messageWifi.what = 1;
					updateNetworkIconHandler.sendMessage(messageWifi);
					updateWifiTime++;
					if (updateWifiTime > 5) {
						shouldUpdateWifi = false;
					}
				}
			}
		}
	}

	final Handler updateNetworkIconHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				updateWiFiState();
				// 3G TODO:
				simState = telephonyManager.getSimState();
				MyLog.v("[update3GState]SIM State:" + simState);
				if (NetworkUtil.isAirplaneModeOn(getApplicationContext())) {
					imageSignalLevel.setBackground(getResources().getDrawable(
							R.drawable.ic_qs_signal_no_signal));
					image3GType.setVisibility(View.GONE);
				} else if (simState == TelephonyManager.SIM_STATE_READY) {
				} else if (simState == TelephonyManager.SIM_STATE_UNKNOWN
						|| simState == TelephonyManager.SIM_STATE_ABSENT) {
					imageSignalLevel.setBackground(getResources().getDrawable(
							R.drawable.ic_qs_signal_no_signal));
					image3GType.setVisibility(View.GONE);
				}
				break;

			default:
				break;
			}
		}
	};

	/**
	 * 如果录像界面不在前台且未在录像，则释放Camera，防止出现熄屏时未在录像仍在预览功耗高的问题
	 * 
	 * 调用地方：在成功执行{@link #stopRecorder}之后
	 */
	private void releaseCameraZone() {
		if (!MyApp.isMainForeground) {
			release();
			// surfaceHolder = null;
			if (camera != null) {
				camera.stopPreview();
			}
			MyApp.shouldResetRecordWhenResume = true;
			MyLog.v("[Record]releaseCameraZone");
		}
		MyApp.cameraState = CameraState.NULL;
	}

	@Override
	protected void onPause() {
		MyLog.v("[Main]onPause");
		MyApp.isMainForeground = false;
		telephonyManager.listen(myPhoneStateListener,
				PhoneStateListener.LISTEN_NONE); // 3G信号

		if (wifiIntentReceiver != null) { // 取消注册wifi消息处理器
			unregisterReceiver(wifiIntentReceiver);
		}

		if (networkStateReceiver != null) { // 飞行模式
			unregisterReceiver(networkStateReceiver);
		}

		MyLog.v("[onPause]MyApplication.isVideoReording:"
				+ MyApp.isVideoReording);

		// ACC在的时候不频繁释放录像区域：ACC在的时候Suspend？
		if (!MyApp.isAccOn && !MyApp.isVideoReording) {
			releaseCameraZone();
			MyApp.isVideoLockSecond = false;
		}

		super.onPause();
	}

	@Override
	protected void onResume() {
		try {
			MyLog.v("[Main]onResume");
			hsvMain.smoothScrollTo(0, 0);

			if (!MyApp.isBTPlayMusic) { // 触摸声音
				Settings.System.putString(getContentResolver(),
						Settings.System.SOUND_EFFECTS_ENABLED, "1");
			}

			if (isSurfaceLarge()) { // 按HOME键将预览区域还原为小窗口
				updateSurfaceState();
			}

			MyApp.isMainForeground = true;
			if (!MyApp.isFirstLaunch) {
				if (!MyApp.isVideoReording || MyApp.shouldResetRecordWhenResume) {
					MyApp.shouldResetRecordWhenResume = false;
					if (camera == null) { // 重置预览区域
						// surfaceHolder = holder;
						setup();
					} else {
						try {
							camera.lock();
							camera.setPreviewDisplay(surfaceHolder);
							camera.startPreview();
							camera.unlock();
						} catch (Exception e) {
							// e.printStackTrace();
						}
					}
				}
			} else {
				MyApp.isFirstLaunch = false;
			}

			refreshRecordButton(); // 更新录像界面按钮状态
			setupRecordViews();

			telephonyManager.listen(myPhoneStateListener,
					PhoneStateListener.LISTEN_SIGNAL_STRENGTHS); // 3G信号
			registerReceiver(wifiIntentReceiver, wifiIntentFilter); // 注册wifi消息处理器
			updateWiFiState(); // WiFi信号

			networkStateReceiver = new NetworkStateReceiver();
			IntentFilter networkFilter = new IntentFilter();
			networkFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
			networkFilter.addAction(Constant.Broadcast.BT_CONNECTED);
			networkFilter.addAction(Constant.Broadcast.BT_DISCONNECTED);
			registerReceiver(networkStateReceiver, networkFilter);

			setAirplaneIcon(NetworkUtil
					.isAirplaneModeOn(getApplicationContext())); // 飞行模式

			boolean isExtBluetoothConnected = NetworkUtil
					.isExtBluetoothConnected(getApplicationContext()); // 外置蓝牙
			setBluetoothIcon(isExtBluetoothConnected ? 1 : 0);
		} catch (Exception e) {
			e.printStackTrace();
			MyLog.e("[Main]onResume catch Exception:" + e.toString());
		}
		super.onResume();
	}

	@Override
	protected void onStop() {
		MyLog.v("[Main]onStop");
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		MyLog.v("[Main]onDestroy");
		release(); // 释放录像区域

		videoDb.close();

		super.onDestroy();
	}

	// *********** Record ***********

	/** 设置录制初始值 */
	private void setupRecordDefaults() {
		refreshRecordButton();

		recordState = Constant.Record.STATE_RECORD_STOPPED;
		MyApp.isVideoReording = false;

		pathState = Constant.Record.STATE_PATH_ZERO;
		secondaryState = Constant.Record.STATE_SECONDARY_DISABLE;
		overlapState = Constant.Record.STATE_OVERLAP_ZERO;

		// 录音,静音;默认录音
		boolean videoMute = sharedPreferences.getBoolean("videoMute",
				Constant.Record.muteDefault);
		muteState = videoMute ? Constant.Record.STATE_MUTE
				: Constant.Record.STATE_UNMUTE;
	}

	private void refreshRecordButton() {
		// 视频尺寸：公版默认720P，善领默认1080P
		String videoSizeStr = sharedPreferences.getString("videoSize",
				Constant.Module.isPublic ? "720" : "1080");
		resolutionState = "1080".equals(videoSizeStr) ? Constant.Record.STATE_RESOLUTION_1080P
				: Constant.Record.STATE_RESOLUTION_720P;

		// 视频分段
		String videoTimeStr = sharedPreferences.getString("videoTime", "3");
		intervalState = "1".equals(videoTimeStr) ? Constant.Record.STATE_INTERVAL_1MIN
				: Constant.Record.STATE_INTERVAL_3MIN;
	}

	/** 绘制录像按钮 */
	private void setupRecordViews() {
		HintUtil.setRecordHintFloatWindowVisible(MainActivity.this,
				MyApp.isVideoReording);

		// 视频分辨率
		if (resolutionState == Constant.Record.STATE_RESOLUTION_720P) {
			largeVideoSize.setBackground(getResources().getDrawable(
					R.drawable.ui_camera_video_size_720));
		} else if (resolutionState == Constant.Record.STATE_RESOLUTION_1080P) {
			largeVideoSize.setBackground(getResources().getDrawable(
					R.drawable.ui_camera_video_size_1080));
		}

		// 录像按钮
		largeVideoRecord.setBackground(getResources().getDrawable(
				MyApp.isVideoReording ? R.drawable.ui_main_video_pause
						: R.drawable.ui_main_video_record));
		smallVideoRecord.setBackground(getResources().getDrawable(
				MyApp.isVideoReording ? R.drawable.ui_main_video_pause
						: R.drawable.ui_main_video_record));

		// 视频分段
		if (intervalState == Constant.Record.STATE_INTERVAL_1MIN) {
			largeVideoTime.setBackground(getResources().getDrawable(
					R.drawable.ui_camera_video_time_1));
		} else if (intervalState == Constant.Record.STATE_INTERVAL_3MIN) {
			largeVideoTime.setBackground(getResources().getDrawable(
					R.drawable.ui_camera_video_time_3));
		}

		// 视频加锁
		smallVideoLock.setBackground(getResources().getDrawable(
				MyApp.isVideoLock ? R.drawable.ui_camera_video_lock
						: R.drawable.ui_camera_video_unlock));
		largeVideoLock.setBackground(getResources().getDrawable(
				MyApp.isVideoLock ? R.drawable.ui_camera_video_lock
						: R.drawable.ui_camera_video_unlock));

		// 静音按钮
		boolean videoMute = sharedPreferences.getBoolean("videoMute",
				Constant.Record.muteDefault);
		muteState = videoMute ? Constant.Record.STATE_MUTE
				: Constant.Record.STATE_UNMUTE;
		largeVideoMute.setBackground(getResources().getDrawable(
				videoMute ? R.drawable.ui_camera_video_sound_off
						: R.drawable.ui_camera_video_sound_on));
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		// surfaceHolder = holder;
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		MyLog.v("[Record]surfaceCreated");

		if (camera == null) {
			surfaceHolder = holder;
			setup();
		} else {
			try {
				camera.lock();
				camera.setPreviewDisplay(surfaceHolder);
				camera.startPreview();
				camera.unlock();
			} catch (Exception e) {
				// e.printStackTrace();
			}
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		MyLog.v("[Record]surfaceDestroyed");
	}

	/**
	 * 打开摄像头
	 * 
	 * @return
	 */
	private boolean openCamera() {
		if (camera != null) {
			closeCamera();
		}
		try {
			MyLog.v("[Record] Camera.open");
			camera = Camera.open(0);
			camera.lock();

			// 设置系统Camera参数
			Camera.Parameters para = camera.getParameters();
			para.unflatten(Constant.Record.CAMERA_PARAMS);
			camera.setParameters(para);

			camera.setPreviewDisplay(surfaceHolder);
			camera.startPreview();
			camera.unlock();
			return true;
		} catch (Exception ex) {
			closeCamera();
			MyLog.e("[Record]openCamera:Catch Exception!");
			return false;
		}
	}

	/**
	 * 关闭Camera
	 * 
	 * @return
	 */
	private boolean closeCamera() {
		if (camera == null)
			return true;
		try {
			camera.lock();
			camera.stopPreview();
			camera.setPreviewDisplay(null);
			camera.release();
			camera.unlock();
			camera = null;
			return true;
		} catch (Exception ex) {
			camera = null;
			MyLog.e("[MainActivity]closeCamera:Catch Exception!");
			return false;
		}
	}

	/**
	 * 录像线程， 调用此线程地方：
	 * 
	 * 1.首次启动录像{@link AutoThread }
	 * 
	 * 2.ACC上电录像 {@link BackThread}
	 * 
	 * 3.停车侦测，录制一个视频,时长:{@link Constant.Record.parkVideoLength}
	 * 
	 * 4.插卡自动录像
	 */
	private class StartRecordThread implements Runnable {

		@Override
		public void run() {

			CheckErrorFile();
			int i = 0;
			while (i < 6) {
				try {
					if (!StorageUtil.isVideoCardExists()) {
						// 如果是休眠状态，且不是停车侦测录像情况，避免线程执行过程中，ACC下电后仍然语音提醒“SD不存在”
						if (MyApp.isSleeping
								&& !MyApp.shouldStopWhenCrashVideoSave) {
							return;
						}
						Thread.sleep(1000);
						i++;
						MyLog.e("[StartRecordThread]No SD:try " + i);
						if (i == 5) {
							Message messageRetry = new Message();
							messageRetry.what = 2;
							startRecordHandler.sendMessage(messageRetry);
							return;
						}
					} else { // 开始录像
						Message messageRecord = new Message();
						messageRecord.what = 1;
						startRecordHandler.sendMessage(messageRecord);
						return;
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

	}

	final Handler startRecordHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				if (startRecordTask() == 0) {
					recordState = Constant.Record.STATE_RECORD_STARTED;
					MyApp.isVideoReording = true;

					textRecordTime.setVisibility(View.VISIBLE);
					new Thread(new updateRecordTimeThread()).start(); // 更新录制时间
					setupRecordViews();

					if (MyApp.isVideoReording
							&& MyApp.shouldStopWhenCrashVideoSave) {
						MyLog.v("[CrashRecord]LCD:" + SettingUtil.getLCDValue());
						if (powerManager.isScreenOn()) { // 发送PowerKey,将假熄屏更改为真熄屏
							sendKeyCode(KeyEvent.KEYCODE_POWER);
						}
					}
				} else {
					MyLog.e("Start Record Failed");
				}
				break;

			case 2:
				noVideoSDHint(); // SDCard2不存在
				break;

			default:
				break;
			}
		}
	};

	/**
	 * 开启录像
	 * 
	 * @return 0:成功 -1:失败
	 */
	public int startRecordTask() {
		if (carRecorder != null) {
			if (StorageUtil.deleteOldestUnlockVideo(MainActivity.this)) {
				MyLog.d("Record Start");
				setDirectory(Constant.Path.SDCARD_2); // 设置保存路径

				// 设置录像静音
				boolean videoMute = sharedPreferences.getBoolean("videoMute",
						Constant.Record.muteDefault);
				if (videoMute) {
					muteState = Constant.Record.STATE_MUTE; // 不录音
					setMute(true);
				} else {
					muteState = Constant.Record.STATE_UNMUTE;
					setMute(false);
				}
				resetRecordTimeText();

				if (!MyApp.shouldStopWhenCrashVideoSave) { // 停车守卫不播放录音
					HintUtil.playAudio(getApplicationContext(), FILE_TYPE_VIDEO);
				}
				return carRecorder.start();
			}
		}
		return -1;
	}

	/** 视频SD卡不存在提示 */
	private void noVideoSDHint() {
		String strNoSD = getResources().getString(R.string.hint_sd2_not_exist);
		audioRecordDialog.showErrorDialog(strNoSD);
		new Thread(new dismissDialogThread()).start();
		HintUtil.speakVoice(MainActivity.this, strNoSD);
	}

	/** 检查并删除异常视频文件：SD存在但数据库中不存在的文件 */
	private void CheckErrorFile() {
		MyLog.v("[CheckErrorFile]isVideoChecking:" + isVideoChecking);
		if (!isVideoChecking && StorageUtil.isVideoCardExists()) {
			new Thread(new CheckVideoThread()).start();
		}
	}

	/** 当前是否正在校验错误视频 */
	private boolean isVideoChecking = false;

	private class CheckVideoThread implements Runnable {

		@Override
		public void run() {
			MyLog.v("[CheckVideoThread]START:" + DateUtil.getTimeStr("mm:ss"));
			isVideoChecking = true;
			String sdcardPath = Constant.Path.SDCARD_2 + File.separator; // "/storage/sdcard2/";
			File file = new File(sdcardPath + "tachograph/");
			StorageUtil.RecursionCheckFile(MainActivity.this, file);
			MyLog.v("[CheckVideoThread]END:" + DateUtil.getTimeStr("mm:ss"));
			isVideoChecking = false;
		}

	}

	public class dismissDialogThread implements Runnable {
		@Override
		public void run() {
			synchronized (dismissDialogHandler) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				Message messageEject = new Message();
				messageEject.what = 1;
				dismissDialogHandler.sendMessage(messageEject);
			}
		}
	}

	final Handler dismissDialogHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 1:
				audioRecordDialog.dismissDialog();
				break;

			default:
				break;
			}
		}
	};

	/**
	 * 停止录像
	 * 
	 * @return 是否成功
	 */
	public int stopRecorder() {
		resetRecordTimeText();
		textRecordTime.setVisibility(View.INVISIBLE);
		if (carRecorder != null) {
			MyLog.d("Record Stop");
			// 停车守卫不播放声音
			if (MyApp.shouldStopWhenCrashVideoSave) {
				MyApp.shouldStopWhenCrashVideoSave = false;
			} else {
				HintUtil.playAudio(getApplicationContext(), FILE_TYPE_VIDEO);
			}
			return carRecorder.stop();
		}
		return -1;
	}

	/**
	 * 设置视频分段
	 * 
	 * @param seconds
	 * @return
	 */
	public int setInterval(int seconds) {
		return (carRecorder != null) ? carRecorder.setVideoSeconds(seconds)
				: -1;
	}

	/**
	 * 设置视频重叠
	 * 
	 * @param seconds
	 * @return
	 */
	public int setOverlap(int seconds) {
		return (carRecorder != null) ? carRecorder.setVideoOverlap(seconds)
				: -1;
	}

	/** 拍照 */
	public int takePhoto() {
		if (!StorageUtil.isVideoCardExists()) {
			noVideoSDHint(); // SDCard不存在
			return -1;
		} else if (carRecorder != null) {
			setDirectory(Constant.Path.SDCARD_2); // 设置保存路径，否则会保存到内部存储
			HintUtil.playAudio(getApplicationContext(), FILE_TYPE_IMAGE);
			return carRecorder.takePicture();
		}
		return -1;
	}

	/** ACC下电拍照 */
	public void takePhotoWhenAccOff() {
		if (carRecorder != null) {
			if (!MyApp.isAccOffPhotoTaking) {
				MyApp.isAccOffPhotoTaking = true;
				if (StorageUtil.isVideoCardExists()) {
					setDirectory(Constant.Path.SDCARD_2); // 如果录像卡不存在，则会保存到内部存储
				}
				HintUtil.playAudio(getApplicationContext(), FILE_TYPE_IMAGE);
				carRecorder.takePicture();

				if (sharedPreferences.getBoolean(Constant.MySP.STR_PARKING_ON,
						true)) {
					HintUtil.speakVoice(
							getApplicationContext(),
							getResources().getString(
									R.string.hint_start_park_monitor_after_90));
				}
			}
			if (powerManager.isScreenOn()) {
				sendKeyCode(KeyEvent.KEYCODE_POWER); // 熄屏
			}
		}
	}

	/** 语音拍照 */
	public void takePhotoWhenVoiceCommand() {
		if (carRecorder != null) {
			if (StorageUtil.isVideoCardExists()) { // 如果录像卡不存在，则会保存到内部存储
				setDirectory(Constant.Path.SDCARD_2);
			}
			HintUtil.playAudio(getApplicationContext(), FILE_TYPE_IMAGE);
			carRecorder.takePicture();
		}
	}

	/** 设置保存路径 */
	public int setDirectory(String dir) {
		if (carRecorder != null) {
			return carRecorder.setDirectory(dir);
		}
		return -1;
	}

	/** 设置录像静音，需要已经初始化carRecorder */
	private int setMute(boolean mute) {
		if (carRecorder != null) {
			return carRecorder.setMute(mute);
		}
		return -1;
	}

	/**
	 * 设置分辨率
	 * 
	 * @param state
	 * @return
	 */
	public int setResolution(int state) {
		if (state != resolutionState) {
			resolutionState = state;
			release();
			if (openCamera()) {
				setupRecorder();
			}
		}
		return -1;
	}

	public int setSecondary(int state) {
		if (state == Constant.Record.STATE_SECONDARY_DISABLE) {
			if (carRecorder != null) {
				return carRecorder.setSecondaryVideoEnable(false);
			}
		} else if (state == Constant.Record.STATE_SECONDARY_ENABLE) {
			if (carRecorder != null) {
				carRecorder.setSecondaryVideoSize(320, 240);
				carRecorder
						.setSecondaryVideoFrameRate(Constant.Record.FRAME_RATE);
				carRecorder
						.setSecondaryVideoBiteRate(Constant.Record.BIT_RATE_240P);
				return carRecorder.setSecondaryVideoEnable(true);
			}
		}
		return -1;
	}

	private void setupRecorder() {
		releaseRecorder();
		try {
			carRecorder = new TachographRecorder();
			carRecorder.setTachographCallback(this);
			carRecorder.setCamera(camera);
			carRecorder.setClientName(this.getPackageName());
			if (resolutionState == Constant.Record.STATE_RESOLUTION_1080P) {
				carRecorder.setVideoSize(1920, 1088); // 16倍数
				carRecorder.setVideoFrameRate(Constant.Record.FRAME_RATE);
				carRecorder.setVideoBiteRate(Constant.Record.BIT_RATE_1080P);
			} else {
				carRecorder.setVideoSize(1280, 720);
				carRecorder.setVideoFrameRate(Constant.Record.FRAME_RATE);
				carRecorder.setVideoBiteRate(Constant.Record.BIT_RATE_720P);
			}
			if (secondaryState == Constant.Record.STATE_SECONDARY_ENABLE) {
				carRecorder.setSecondaryVideoEnable(true);
				carRecorder.setSecondaryVideoSize(320, 240);
				carRecorder
						.setSecondaryVideoFrameRate(Constant.Record.FRAME_RATE);
				carRecorder
						.setSecondaryVideoBiteRate(Constant.Record.BIT_RATE_240P);
			} else {
				carRecorder.setSecondaryVideoEnable(false);
			}
			if (intervalState == Constant.Record.STATE_INTERVAL_1MIN) {
				carRecorder.setVideoSeconds(1 * 60);
			} else {
				carRecorder.setVideoSeconds(3 * 60);
			}
			if (overlapState == Constant.Record.STATE_OVERLAP_FIVE) {
				carRecorder.setVideoOverlap(5);
			} else {
				carRecorder.setVideoOverlap(0);
			}
			carRecorder.prepare();
		} catch (Exception e) {
			MyLog.e("[MainActivity]setupRecorder: Catch Exception!");
		}

		MyApp.cameraState = CameraState.OKAY;
	}

	/** 释放Recorder */
	private void releaseRecorder() {
		try {
			if (carRecorder != null) {
				carRecorder.stop();
				carRecorder.close();
				carRecorder.release();
				carRecorder = null;
				MyLog.d("Record Release");
			}
		} catch (Exception e) {
			MyLog.e("[MainActivity]releaseRecorder: Catch Exception!");
		}
	}

	@Override
	public void onError(int error) {
		switch (error) {
		case TachographCallback.ERROR_SAVE_VIDEO_FAIL:
			HintUtil.showToast(MainActivity.this, "视频保存失败");
			MyLog.e("Record Error : ERROR_SAVE_VIDEO_FAIL");

			// 视频保存失败，原因：存储空间不足，清空文件夹，视频被删掉
			resetRecordTimeText();
			if (stopRecorder() == 0) {
				recordState = Constant.Record.STATE_RECORD_STOPPED;
				MyApp.isVideoReording = false;
				setupRecordViews();
				releaseCameraZone();
			}
			break;

		case TachographCallback.ERROR_SAVE_IMAGE_FAIL:
			HintUtil.showToast(MainActivity.this, "图片保存失败");
			MyLog.e("Record Error : ERROR_SAVE_IMAGE_FAIL");

			if (MyApp.shouldSendPathToDSA) {
				MyApp.shouldSendPathToDSA = false;

				MyApp.isAccOffPhotoTaking = false;
			}
			break;

		case TachographCallback.ERROR_RECORDER_CLOSED:
			MyLog.e("Record Error : ERROR_RECORDER_CLOSED");
			break;

		default:
			break;
		}
	}

	@Override
	public void onFileStart(int type, String path) {

		if (type == 1) {
			MyApp.nowRecordVideoName = path.split("/")[5];
		}

	}

	/**
	 * 文件保存回调，注意：存在延时，不能用作重置录像跑秒时间
	 * 
	 * @param type
	 *            0-图片 1-视频
	 * 
	 * @param path
	 *            视频：/mnt/sdcard/tachograph/2015-07-01/2015-07-01_105536.mp4
	 *            图片:/mnt/sdcard/tachograph/camera_shot/2015-07-01_105536.jpg
	 */
	@Override
	public void onFileSave(int type, String path) {
		try {
			if (type == 1) { // 视频

				StorageUtil.deleteOldestUnlockVideo(MainActivity.this);

				String videoName = path.split("/")[5];
				editor.putString("sdcardPath", "/mnt/" + path.split("/")[2]
						+ "/");
				editor.commit();
				int videoResolution = (resolutionState == Constant.Record.STATE_RESOLUTION_720P) ? 720
						: 1080;
				int videoLock = 0;

				if (MyApp.isVideoLock) {
					videoLock = 1;
					MyApp.isVideoLock = false; // 还原
					if (MyApp.isVideoReording && MyApp.isVideoLockSecond) {
						MyApp.isVideoLock = true;
						MyApp.isVideoLockSecond = false; // 不录像时修正加锁图标
					}
				}
				setupRecordViews(); // 更新录制按钮状态
				DriveVideo driveVideo = new DriveVideo(videoName, videoLock,
						videoResolution);
				videoDb.addDriveVideo(driveVideo);

				MyLog.v("[onFileSave]videoLock:" + videoLock
						+ ", isVideoLockSecond:" + MyApp.isVideoLockSecond);
			} else { // 图片
				HintUtil.showToast(MainActivity.this,
						getResources().getString(R.string.hint_photo_save));

				StorageUtil.writeImageExif(MainActivity.this, path);

				if (MyApp.shouldSendPathToDSA) {
					MyApp.shouldSendPathToDSA = false;
					String[] picPaths = new String[2]; // 第一张保存前置的图片路径
														// ；第二张保存后置的，如无可以为空
					picPaths[0] = path;
					picPaths[1] = "";
					Intent intent = new Intent(Constant.Broadcast.SEND_PIC_PATH);
					intent.putExtra("picture", picPaths);
					sendBroadcast(intent);

					MyApp.isAccOffPhotoTaking = false;
				}

				// 通知语音
				Intent intentImageSave = new Intent(
						Constant.Broadcast.ACTION_IMAGE_SAVE);
				intentImageSave.putExtra("path", path);
				sendBroadcast(intentImageSave);

			}

			// 更新Media Database
			sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
					Uri.parse("file://" + path)));
			MyLog.d("[onFileSave]Type=" + type + ",Save path:" + path);

			CheckErrorFile(); // 执行onFileSave时，此file已经不隐藏，下个正在录的为隐藏
		} catch (Exception e) {
			e.printStackTrace();
			MyLog.e("[Main]onFileSave catch Exception:" + e.toString());
		}
	}

	public void setup() {
		release();
		if (openCamera()) {
			setupRecorder();
		}
	}

	public void release() {
		releaseRecorder();
		closeCamera();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (isSurfaceLarge()) { // 如果视频全屏预览开启，返回关闭
				int widthSmall = 480;
				int heightSmall = 270;
				surfaceCamera.setLayoutParams(new RelativeLayout.LayoutParams(
						widthSmall, heightSmall));
				isSurfaceLarge = false;

				hsvMain.scrollTo(0, 0); // 更新HorizontalScrollView阴影

				updateButtonState(false);
			}
			return true;
		} else
			return super.onKeyDown(keyCode, event);
	}

	private void sendKeyCode(final int keyCode) {
		new Thread() {
			public void run() {
				try {
					Instrumentation inst = new Instrumentation();
					inst.sendKeyDownUpSync(keyCode);
				} catch (Exception e) {
					MyLog.e("Exception when sendPointerSync:" + e.toString());
				}
			}
		}.start();
	}

}
