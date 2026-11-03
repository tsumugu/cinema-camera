package com.example.cinemacamera;

import android.app.Application;
import android.util.Log;

public final class CameraApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // Includes starts initiated by JobScheduler after process death or reboot.
        new Thread(() -> {
            try { SaveScheduler.initialize(this); PendingPhotoStore.recover(this); }
            catch(Exception error) { Log.e("CinemaCamera","Save recovery will retry on next start",error); }
        },"photo-recovery").start();
    }
}
