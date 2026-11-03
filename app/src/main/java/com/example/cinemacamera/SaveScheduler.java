package com.example.cinemacamera;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

/** System-persisted jobs survive Activity/process death and device reboot. */
final class SaveScheduler {
    static final String CHANGED="com.example.cinemacamera.SAVE_CHANGED";
    private static final int RECOVERY_JOB=7301;
    static synchronized void initialize(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(RECOVERY_JOB)==null) {
            int result=scheduler.schedule(new JobInfo.Builder(RECOVERY_JOB,new ComponentName(context,SaveRecoveryService.class))
                .setPersisted(true).setPeriodic(15*60_000L).build());
            if(result!=JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Could not schedule save recovery");
        }
    }
    static synchronized void schedule(Context context,String id) {
        initialize(context);
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        for(JobInfo existing:scheduler.getAllPendingJobs()) if(id.equals(existing.getExtras().getString("photo"))) return;
        int number=10_000+(id.hashCode()&0x3fffffff);
        while(scheduler.getPendingJob(number)!=null) number++;
        PersistableBundle extras=new PersistableBundle(); extras.putString("photo",id);
        int result=scheduler.schedule(new JobInfo.Builder(number,new ComponentName(context,PhotoSaveJobService.class))
            .setExtras(extras).setPersisted(true).setMinimumLatency(0).setOverrideDeadline(0)
            .setBackoffCriteria(10_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        if(result!=JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Could not schedule photo save");
    }
    static synchronized void retryAll(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        for(PendingPhotoStore.Record record:PendingPhotoStore.records(context)) {
            if(!record.state.equals("ready")) continue;
            for(JobInfo existing:scheduler.getAllPendingJobs()) {
                if(record.id.equals(existing.getExtras().getString("photo"))&&!PhotoSaveJobService.RUNNING.contains(existing.getId()))
                    scheduler.cancel(existing.getId());
            }
            schedule(context,record.id);
        }
    }
    static void changed(Context context) { context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName())); }
}
