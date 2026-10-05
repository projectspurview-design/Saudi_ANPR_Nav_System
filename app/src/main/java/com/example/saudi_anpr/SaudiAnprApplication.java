package com.example.saudi_anpr;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.example.saudi_anpr.security.SecuritySession;

/**
 * Initializes Firebase and keeps the in-memory security session locked when the
 * whole application has genuinely left the foreground. A short grace period is
 * used so transitions such as LockActivity -> HomeActivity are not mistaken
 * for leaving the app.
 */
public final class SaudiAnprApplication extends Application {
    private static final String TAG = "SaudiAnprApplication";
    private static final long BACKGROUND_LOCK_DELAY_MS = 1500L;

    private int startedActivities = 0;
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockAfterBackground = () -> {
        if (startedActivities == 0) {
            SecuritySession.lock();
            Log.d(TAG, "Application backgrounded; security session locked");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            FirebaseApp.initializeApp(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "Firebase initialization failed", e);
        }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                startedActivities++;
                lifecycleHandler.removeCallbacks(lockAfterBackground);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0) {
                    lifecycleHandler.removeCallbacks(lockAfterBackground);
                    lifecycleHandler.postDelayed(lockAfterBackground, BACKGROUND_LOCK_DELAY_MS);
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}
