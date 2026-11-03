package com.example.cinemacamera;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** JobScheduler owns the lifetime and wake lock; original input remains until publication. */
public final class PhotoSaveJobService extends JobService {
    static final java.util.Set<Integer> RUNNING=ConcurrentHashMap.newKeySet();
    private static final ReentrantLock PROCESSING=new ReentrantLock();
    private final ExecutorService executor=Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<Integer,Task> tasks=new ConcurrentHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final class Task {
        final AtomicBoolean cancelled=new AtomicBoolean();
        volatile Future<?> future;
        boolean stopped() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
        void cancel() { cancelled.set(true); Future<?> f=future; if(f!=null) f.cancel(true); }
    }
    @Override public boolean onStartJob(JobParameters params) {
        Task task=new Task(); tasks.put(params.getJobId(),task); RUNNING.add(params.getJobId());
        task.future=executor.submit(() -> {
            boolean retry=true,locked=false;
            String id=params.getExtras().getString("photo");
            try {
                PROCESSING.lockInterruptibly(); locked=true;
                if(!task.stopped()) { process(id,task); retry=false; }
            } catch(Exception|OutOfMemoryError error) {
                if(!task.stopped()&&id!=null) {
                    Log.e("CinemaCamera","Photo retained for save retry",error); PendingPhotoStore.error(this,id,error);
                }
            } finally {
                if(locked) PROCESSING.unlock();
                SaveScheduler.changed(this);
                boolean reschedule=retry;
                main.post(() -> {
                    if(tasks.remove(params.getJobId(),task)) {
                        RUNNING.remove(params.getJobId());
                        if(!task.cancelled.get()) jobFinished(params,reschedule);
                    }
                });
            }
        });
        return true;
    }
    private void process(String id,Task task) throws Exception {
        if(id==null) throw new IllegalArgumentException("Missing pending photo");
        PendingPhotoStore.Record record;
        try { record=PendingPhotoStore.read(this,id); }
        catch(java.io.FileNotFoundException completed) { return; }
        if(record.state.equals("saved")) { PendingPhotoStore.remove(this,id); return; }
        if(!record.state.equals("ready")) throw new IllegalStateException("Capture is not yet committed");
        PhotoStore.Existing previous=PhotoStore.existing(getContentResolver(),record);
        if(previous!=null&&previous.published) {
            PendingPhotoStore.saved(this,id,previous.uri); PendingPhotoStore.remove(this,id); return;
        }
        Bitmap rendered=null; PhotoRenderer renderer=new PhotoRenderer(getApplicationContext());
        String source="JPEG"; float nearSaturated=-1;
        try {
            if(PendingPhotoStore.hasRaw(this,id)) {
                try {
                    RawDeveloper.Input raw=PendingPhotoStore.raw(this,id);
                    LinearPhoto linear=RawDeveloper.develop(raw,2560);
                    nearSaturated=linear.nearSaturatedFraction;
                    if(task.stopped()) throw new InterruptedException();
                    rendered=renderer.render(linear,record.look); source="RAW";
                } catch(Exception|OutOfMemoryError rawError) {
                    if(task.stopped()) throw new InterruptedException();
                    Log.w("CinemaCamera","Use persisted JPEG fallback",rawError);
                }
            }
            if(task.stopped()) throw new InterruptedException();
            if(rendered==null) {
                if(!PendingPhotoStore.hasJpeg(this,id)) throw new IllegalStateException("RAW needs retry; no same-shot JPEG available");
                Bitmap jpeg=PhotoBitmaps.from(PendingPhotoStore.jpeg(this,id),2560);
                rendered=renderer.render(jpeg,record.look);
            }
            if(task.stopped()) throw new InterruptedException();
            String software="Cinema Camera "+getPackageManager().getPackageInfo(getPackageName(),0).versionName;
            org.json.JSONObject diagnostics=new org.json.JSONObject().put("source",source).put("colorPipeline","highlight-v2")
                .put("rawNearSaturatedFraction",nearSaturated>=0?(Object)(double)nearSaturated:org.json.JSONObject.NULL)
                .put("look",record.json.getJSONObject("look"))
                .put("manualRequest",record.json.optJSONObject("manualRequest"));
            org.json.JSONObject capture=record.json.optJSONObject("capture");
            if(capture!=null){diagnostics.put("captureMetadataSource",capture.optString("metadataSource"));
                if(capture.has("focusDiopters"))diagnostics.put("actualFocusDiopters",capture.getDouble("focusDiopters"));}
            // GPS coordinates are written only into standard GPS tags, so stripping
            // geotags does not leave a second copy hidden in UserComment.
            Uri uri=PhotoStore.publish(getContentResolver(),record,rendered,task::stopped,software,diagnostics.toString());
            // Journal fsync precedes deleting either original. A process death
            // between publication and this write is handled by stable-name lookup.
            PendingPhotoStore.saved(this,id,uri); PendingPhotoStore.remove(this,id);
        } finally {
            if(rendered!=null) rendered.recycle(); renderer.close();
        }
    }
    @Override public boolean onStopJob(JobParameters params) {
        Task task=tasks.remove(params.getJobId()); RUNNING.remove(params.getJobId()); if(task!=null) task.cancel();
        return true;
    }
    @Override public void onDestroy() {
        for(Integer id:tasks.keySet()) { Task task=tasks.get(id); if(task!=null) task.cancel(); RUNNING.remove(id); }
        tasks.clear(); executor.shutdownNow(); super.onDestroy();
    }
}
