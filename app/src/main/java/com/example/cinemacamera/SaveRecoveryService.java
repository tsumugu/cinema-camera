package com.example.cinemacamera;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repairs the small journal-to-scheduler handoff window after a killed process. */
public final class SaveRecoveryService extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    @Override public boolean onStartJob(JobParameters params) {
        executor.execute(() -> { PendingPhotoStore.recover(this); SaveScheduler.changed(this); jobFinished(params,false); });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
