package com.dieam.reactnativepushnotification.modules;

import java.util.Map;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;

import org.json.JSONObject;

import java.util.List;
import java.util.Random;

import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;

public class RNPushNotificationListenerService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        final String deviceToken = token;
        Log.d(LOG_TAG, "Refreshed token: " + deviceToken);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
                ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notificationre
                if (context != null) {
                    handleNewToken((ReactApplicationContext) context, deviceToken);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
                            handleNewToken((ReactApplicationContext) context, deviceToken);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    private void handleNewToken(ReactApplicationContext context, String token) {
        RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);

        WritableMap params = Arguments.createMap();
        params.putString("deviceToken", token);
        jsDelivery.sendEvent("remoteNotificationsRegistered", params);
    }


    @Override
    public void onMessageReceived(RemoteMessage message) {
        String from = message.getFrom();
        RemoteMessage.Notification remoteNotification = message.getNotification();
        final Bundle bundle = new Bundle();
        // Putting it from remoteNotification first so it can be overriden if message
        // data has it
        if (remoteNotification != null) {
            // ^ It's null when message is from GCM
            bundle.putString("title", remoteNotification.getTitle());
            bundle.putString("message", remoteNotification.getBody());
            bundle.putString("sound", remoteNotification.getSound());
            bundle.putString("color", remoteNotification.getColor());
        }

        Map<String, String> notificationData = message.getData();

        // Copy `twi_body` to `message` to support Twilio
        if (notificationData.containsKey("twi_body")) {
            bundle.putString("message", notificationData.get("twi_body"));
        }
        JSONObject data = getPushData(notificationData.get("data"));

        if (data != null) {
            if (!bundle.containsKey("message")) {
                bundle.putString("message", data.optString("alert", null));
            }
            if (!bundle.containsKey("title")) {
                bundle.putString("title", data.optString("title", null));
            }
            if (!bundle.containsKey("sound")) {
                bundle.putString("soundName", data.optString("sound", null));
            }
            if (!bundle.containsKey("color")) {
                bundle.putString("color", data.optString("color", null));
            }

            final int badge = data.optInt("badge", -1);
            if (badge >= 0) {
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(this, badge);
            }
        }

        Bundle dataBundle = new Bundle();
        for(Map.Entry<String, String> entry : notificationData.entrySet()) {
            dataBundle.putString(entry.getKey(), entry.getValue());
        }
        bundle.putParcelable("data", dataBundle);

        Log.v(LOG_TAG, "onMessageReceived: " + bundle);

        // We need to run this on the main thread, as the React code assumes that is true.
        // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
        // "Can't create handler inside thread that has not called Looper.prepare()"
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
                ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notificationre
                if (context != null) {
                    checkForPendingAlarm((ReactApplicationContext) context, bundle);
                    handleRemotePushNotification((ReactApplicationContext) context, bundle);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
                            checkForPendingAlarm((ReactApplicationContext) context, bundle);
                            handleRemotePushNotification((ReactApplicationContext) context, bundle);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    private boolean checkForPendingAlarm(ReactApplicationContext context, Bundle bundle) {
        try {
          if(bundle.containsKey("type") && bundle.getString("type").equals("SYNC")) {
            Intent mScrapeServiceIntent = new Intent();
            mScrapeServiceIntent.setComponent(new ComponentName(context.getPackageName(),
                "com.truyu.plugins.scraper.ServiceWithWebView"));

            // boolean alarmUp = (PendingIntent.getBroadcast(context, 0, mScrapeServiceIntent,
            //     PendingIntent.FLAG_NO_CREATE) != null);
            //
            // if (alarmUp) {
            //   Log.d(LOG_TAG, "Alarm is already active");
            // } else {
            //   Log.d(LOG_TAG, "Alarm not active");
            // }
            context.startService(mScrapeServiceIntent);
            scheduleService(context);
          }
            return true;
        } catch (Exception e) {
          Log.d(LOG_TAG, "exception" + e);
            return false;
        }
    }

    private void scheduleService(ReactApplicationContext context) {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context.getPackageName(),
            "com.truyu.plugins.scraper.InitScrapeServiceReceiver"));
        // Intent intent = new Intent(context, InitScrapeServiceReceiver.class);
        PendingIntent alarmIntent = PendingIntent.getBroadcast(context, 0, intent, 0);
        // setRepeating() lets you specify a precise custom interval--in this case,
        // 2 Hours.
        /*alarmMgr.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(),
                INTERVAL_2_HOUR, alarmIntent);*/

        alarmMgr.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000 * 60 * 60 * 2,
                1000 * 60 * 60 * 2,
                alarmIntent);
    }
    
    private JSONObject getPushData(String dataString) {
        try {
            return new JSONObject(dataString);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleRemotePushNotification(ReactApplicationContext context, Bundle bundle) {

        // If notification ID is not provided by the user for push notification, generate one at random
        if (bundle.getString("id") == null) {
            Random randomNumberGenerator = new Random(System.currentTimeMillis());
            bundle.putString("id", String.valueOf(randomNumberGenerator.nextInt()));
        }

        RNPushNotificationConfig config = new RNPushNotificationConfig(getApplication());

        Boolean isForeground = isApplicationInForeground();

        RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);
        bundle.putBoolean("foreground", isForeground);
        bundle.putBoolean("userInteraction", false);
        jsDelivery.notifyNotification(bundle);

        // If contentAvailable is set to true, then send out a remote fetch event
        if (bundle.getString("contentAvailable", "false").equalsIgnoreCase("true")) {
            jsDelivery.notifyRemoteFetch(bundle);
        }

        Log.v(LOG_TAG, "sendNotification: " + bundle);

        if (config.getNotificationForeground() || !isForeground) {
            Application applicationContext = (Application) context.getApplicationContext();
            RNPushNotificationHelper pushNotificationHelper = new RNPushNotificationHelper(applicationContext);
            pushNotificationHelper.sendToNotificationCentre(bundle);
        }
    }

    private boolean isApplicationInForeground() {
        ActivityManager activityManager = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
        if (processInfos != null) {
            for (RunningAppProcessInfo processInfo : processInfos) {
                if (processInfo.processName.equals(getApplication().getPackageName())
                    && processInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && processInfo.pkgList.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
