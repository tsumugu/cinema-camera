package com.example.cinemacamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends ComponentActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();
    private static final int MAX_OUTSTANDING=3;
    private final ArrayDeque<PhotoJob> retries=new ArrayDeque<>();
    private int outstandingPhotos;
    private final AtomicBoolean converting = new AtomicBoolean();
    private volatile boolean active;
    private volatile int session;
    private long lastFrameTime;
    private ProcessCameraProvider provider;
    private boolean providerRequested, frameReady;
    private Camera camera;
    private PhotoLocation photoLocation;
    private ManualSettings manualSettings,committedManual;
    private ManualSettings.Limits manualLimits;
    private ManualSettingsDialog manualDialog;
    private boolean manualChanging;
    private long manualRequest;
    private Runnable pendingManual;
    private ImageCapture capture;
    private ImageAnalysis analysisUseCase;
    private CaptureMetadata captureMetadata;
    private CameraCharacteristics characteristics;
    private volatile boolean jpegOnly;
    private Shot currentShot;
    private LookSurface surface;
    private CameraScreen screen;
    private final CaptureQueue captureQueue=new CaptureQueue(4);
    private boolean exposureSupported;
    private int exposureIndex,exposureLow,exposureHigh,completedPhotos;
    private float exposureStep,desiredEv;
    private long focusRequest,exposureRequest;
    private final Runnable drainQueue=this::drainCaptures;
    private final BroadcastReceiver saveReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { refreshSavedPhotos(); }
    };
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),granted -> {
            if(granted) { startCamera(); maybeAskLocation(); }
            else {
                screen.permission("カメラを許可",true);
                setStatus("撮影するにはカメラの許可が必要です");
            }
        });

    private final ActivityResultLauncher<String[]> locationPermission=registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(),result -> {
            boolean granted=photoLocation.permitted();photoLocation.enabled(granted);
            if(active&&granted) photoLocation.start();
            setStatus(granted?"位置情報を記録します（測位できた写真のみ）":"位置情報なしで撮影します");
        });
    private void maybeAskLocation() {
        if(!photoLocation.enabled()) return;
        if(photoLocation.permitted()) { if(active)photoLocation.start(); return; }
        android.content.SharedPreferences p=getSharedPreferences("geotag",0);
        if(p.getBoolean("asked",false))return;
        p.edit().putBoolean("asked",true).apply();
        locationPermission.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION});
    }
    private void toggleLocation() {
        if(photoLocation.enabled()&&photoLocation.permitted()){photoLocation.enabled(false);setStatus("位置情報の記録をOFFにしました");return;}
        photoLocation.enabled(true);
        if(photoLocation.permitted()){if(active)photoLocation.start();setStatus("位置情報を記録します（測位できた写真のみ）");}
        else {
            if(getSharedPreferences("geotag",0).getBoolean("asked",false)&&!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)&&!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                setStatus("端末設定で位置情報を許可してください");
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));return;
            }
            getSharedPreferences("geotag",0).edit().putBoolean("asked",false).apply();maybeAskLocation();
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        photoLocation=new PhotoLocation(this); committedManual=ManualSettings.load(this);manualSettings=committedManual;
        ContextCompat.registerReceiver(this,saveReceiver,new IntentFilter(SaveScheduler.CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.CAMERA);
        else maybeAskLocation();
    }

    @Override protected void onResume() {
        super.onResume(); active = true;
        screen.resume(); photoLocation.start();
        surface.startFrames(); surface.onResume();
        if(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        refreshSavedPhotos();
    }

    private void refreshSavedPhotos() {
        if(isDestroyed()) return;
        outstandingPhotos=PendingPhotoStore.count(this);
        if(active) { updateCaptureStatus(); drainCaptures(); }
        processingExecutor.execute(() -> {
            try {
                PendingPhotoStore.recover(getApplicationContext());
                Uri latest=PhotoStore.latest(getContentResolver());
                if(latest==null) return;
                Bitmap thumbnail=getContentResolver().loadThumbnail(latest,new Size(128,128),null);
                main.post(() -> {
                    if(!isDestroyed()) { screen.thumbnail(thumbnail); completedPhotos=1; if(active) updateCaptureStatus(); }
                    else thumbnail.recycle();
                });
            } catch(Exception error) { Log.i("CinemaCamera","No thumbnail",error); }
        });
    }

    @Override protected void onPause() {
        active = false; session++; frameReady = false; screen.ready(false);
        photoLocation.stop();if(manualDialog!=null&&manualDialog.isShowing())manualDialog.dismiss();
        manualRequest++;manualChanging=false;if(pendingManual!=null)main.removeCallbacks(pendingManual);
        focusRequest++; exposureRequest++;
        captureQueue.clear(); main.removeCallbacks(drainQueue); screen.pause();
        Shot pausedShot=currentShot; currentShot=null;
        cancelPendingCapture(pausedShot,new IllegalStateException("Capture interrupted"));
        while(!retries.isEmpty()) finishJob(retries.removeFirst(),null,null,new IllegalStateException("Retry interrupted"));
        surface.stopFrames();
        if(provider != null) provider.unbindAll();
        capture = null; camera = null; analysisUseCase=null;
        surface.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        analysisExecutor.shutdown();
        // Durable save jobs are owned by JobScheduler and are not cancelled here.
        captureExecutor.shutdown(); processingExecutor.shutdown();
        unregisterReceiver(saveReceiver);
        screen.destroy();
        super.onDestroy();
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() {
        if(!active || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        screen.permission("カメラを許可",false);
        if(provider == null) {
            if(providerRequested) return;
            providerRequested = true;
            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                try { provider = future.get(); startCamera(); }
                catch(Exception error) { providerRequested = false; cameraError(error); }
            },main::post);
            return;
        }
        try {
            CameraInfo info=CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.getAvailableCameraInfos()).get(0);
            Set<Integer> formats=ImageCapture.getImageCaptureCapabilities(info).getSupportedOutputFormats();
            ArrayList<Integer> choices=new ArrayList<>();
            if(!jpegOnly) {
                if(formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)) choices.add(ImageCapture.OUTPUT_FORMAT_RAW_JPEG);
                if(formats.contains(ImageCapture.OUTPUT_FORMAT_RAW)) choices.add(ImageCapture.OUTPUT_FORMAT_RAW);
            }
            choices.add(ImageCapture.OUTPUT_FORMAT_JPEG);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            Exception lastError=null;
            for(int format:choices) {
                provider.unbindAll();
                frameReady=false; screen.ready(false); focusRequest++; exposureRequest++;
                screen.overlay.clearIndicator();
                int token=++session;
                CaptureMetadata metadata=new CaptureMetadata();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setResolutionSelector(resolution(640,480)).setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                ImageCapture.Builder builder = new ImageCapture.Builder()
                    .setResolutionSelector(resolution(2560,1920)).setTargetRotation(rotation)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(95)
                    .setFlashMode(screen.flashEnabled()?ImageCapture.FLASH_MODE_ON:ImageCapture.FLASH_MODE_OFF)
                    .setOutputFormat(format);
                Camera2Interop.Extender<ImageCapture> interop=new Camera2Interop.Extender<>(builder);
                interop.setSessionCaptureCallback(metadata);
                if(format!=ImageCapture.OUTPUT_FORMAT_JPEG)
                    interop.setCaptureRequestOption(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
                ImageCapture candidate=builder.build();
                analysis.setAnalyzer(analysisExecutor,image -> analyze(image,token));
                try {
                    Camera bound=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,analysis,candidate);
                    CameraManager manager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
                    characteristics=manager.getCameraCharacteristics(Camera2CameraInfo.from(bound.getCameraInfo()).getCameraId());
                    camera=bound; capture=candidate; analysisUseCase=analysis; captureMetadata=metadata; lastError=null;
                    break;
                } catch(Exception error) { lastError=error; analysis.clearAnalyzer(); }
            }
            if(lastError!=null) throw lastError;
            configureExposure();
            screen.flashAvailable(camera.getCameraInfo().hasFlashUnit());
            manualLimits=new ManualSettings.Limits(characteristics);
            committedManual=manualLimits.fit(ManualSettings.load(this));
            applyManual(committedManual,false);
            setStatus("カメラを起動しています…");
        } catch(Exception error) { cameraError(error); }
    }

    private ResolutionSelector resolution(int width, int height) {
        return new ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(new ResolutionStrategy(new Size(width,height),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
    }

    private void analyze(ImageProxy image, int token) {
        try {
            long now = SystemClock.elapsedRealtime();
            if(!active || token != session || now - lastFrameTime < 66
                    || !converting.compareAndSet(false,true)) return;
            try {
                lastFrameTime = now;
                Bitmap bitmap = PhotoBitmaps.from(image,960);
                if(active && token == session) {
                    Rect crop=image.getCropRect();
                    surface.offerFrame(bitmap,new PreviewFrameInfo(token,image.getWidth(),image.getHeight(),
                        crop.left,crop.top,crop.width(),crop.height(),image.getImageInfo().getRotationDegrees(),
                        bitmap.getWidth(),bitmap.getHeight()));
                } else bitmap.recycle();
            } finally { converting.set(false); }
        } catch(Throwable error) {
            Log.e("CinemaCamera","Preview conversion failed",error);
        } finally { image.close(); }
    }

    private void takePhoto() {
        if(capture==null||!active||!frameReady) return;
        if(!captureQueue.tap()) { setStatus("撮影待ちがいっぱいです"); return; }
        drainCaptures();
    }

    private void holdPhotos(boolean holding) {
        if(!holding) { captureQueue.stopHold(); return; }
        if(!active||!frameReady||capture==null) return;
        captureQueue.startHold(); drainCaptures();
    }

    /** Sensor acquisition is serialized; development/saving runs independently. */
    private void drainCaptures() {
        if(!active||!frameReady||capture==null||currentShot!=null||manualChanging||manualDialog!=null&&manualDialog.isShowing()) return;
        if(!retries.isEmpty()) {
            if(capture.getOutputFormat()!=ImageCapture.OUTPUT_FORMAT_JPEG) {
                surface.startFrames(); startCamera(); return;
            }
            beginShot(retries.removeFirst()); return;
        }
        outstandingPhotos=PendingPhotoStore.count(this);
        if(outstandingPhotos>=MAX_OUTSTANDING) { updateCaptureStatus(); return; }
        if(captureQueue.startNext()) {
            outstandingPhotos++;
            beginShot(new PhotoJob(screen.flashEnabled(),screen.captureRotation(),System.currentTimeMillis(),screen.lookSettings(),manualSettings,photoLocation.snapshot()));
        }
    }

    private void beginShot(PhotoJob job) {
        if(capture==null||!active) { finishJob(job,null,null,new IllegalStateException("Camera is not ready")); return; }
        try {
            if(job.retrying){job.location=photoLocation.snapshot();job.captureExif=CaptureExif.base(System.currentTimeMillis(),java.util.TimeZone.getDefault().getID());}
            SaveScheduler.initialize(this);
            PendingPhotoStore.create(this,job.id,job.capturedAt,job.lookSettings,job.captureExif,job.location,job.manual.json());
        } catch(Exception error) { PendingPhotoStore.ACTIVE_CAPTURES.remove(job.id); finishJob(job,null,null,error); return; }
        Shot shot=new Shot(session,capture.getOutputFormat(),job,captureMetadata,characteristics);
        currentShot=shot;
        shot.timeout=() -> enqueueCapture(() -> deliver(shot,new IllegalStateException("Capture timed out")));
        main.postDelayed(shot.timeout,20_000+(job.manual.exposure?job.manual.shutter/1_000_000:0));
        updateCaptureStatus();
        try {
            // The preview stays in the portrait UI; each photo uses the physical
            // orientation frozen for this shutter request, including JPEG retries.
            capture.setTargetRotation(job.rotation);
            capture.setFlashMode(job.flash?ImageCapture.FLASH_MODE_ON:ImageCapture.FLASH_MODE_OFF);
            capture.takePicture(captureExecutor,new ImageCapture.OnImageCapturedCallback() {
                @Override public void onCaptureSuccess(ImageProxy image) { receive(shot,image); }
                @Override public void onError(ImageCaptureException error) { deliver(shot,error); }
            });
        } catch(Exception error) { enqueueCapture(() -> deliver(shot,error)); }
    }

    /** Copy/spool camera-owned data and close its buffers before expensive work. */
    private void receive(Shot shot,ImageProxy image) {
        try {
            if(shot.cancelled||shot.delivered) return;
            if(image.getFormat()==ImageFormat.RAW_SENSOR) {
                shot.rawReceived=true;
                try {
                    android.hardware.camera2.CaptureResult result=shot.metadata.await(image.getImageInfo().getTimestamp());
                    recordCaptureExif(shot,result,null);
                    RawDeveloper.Input input=RawMetadata.input(image,shot.info,result);
                    PendingPhotoStore.writeRaw(getApplicationContext(),shot.job.id,input);
                    shot.rawAvailable=true;
                } catch(Throwable error) { shot.captureError=error; Log.w("CinemaCamera","RAW snapshot unavailable",error); }
            } else {
                shot.jpegReceived=true;
                try {
                    PhotoBitmaps.Encoded encoded=PhotoBitmaps.snapshot(image);
                    recordCaptureExif(shot,shot.hasSensorExif?null:shot.metadata.await(image.getImageInfo().getTimestamp()),encoded.bytes);
                    PendingPhotoStore.writeJpeg(getApplicationContext(),shot.job.id,encoded);
                    shot.jpegAvailable=true;
                } catch(Throwable error) { shot.captureError=error; }
            }
        } finally { image.close(); }
        if((shot.format==ImageCapture.OUTPUT_FORMAT_RAW_JPEG&&shot.rawReceived&&shot.jpegReceived)
                ||(shot.format==ImageCapture.OUTPUT_FORMAT_RAW&&shot.rawReceived)
                ||(shot.format==ImageCapture.OUTPUT_FORMAT_JPEG&&shot.jpegReceived)) deliver(shot,shot.captureError);
    }

    private void recordCaptureExif(Shot shot,android.hardware.camera2.CaptureResult result,byte[] jpeg) {
        if(shot.hasSensorExif)return;
        try {
            org.json.JSONObject data=result!=null?CaptureExif.fromResult(shot.job.captureExif,result,shot.info):
                jpeg!=null?CaptureExif.fromJpeg(shot.job.captureExif,jpeg):shot.job.captureExif;
            PendingPhotoStore.captureMetadata(this,shot.job.id,data);shot.hasSensorExif=result!=null;
        } catch(Exception error){Log.w("CinemaCamera","Capture metadata unavailable; saving available fields",error);}
    }

    /** Capture executor only. Release the sensor slot, retaining the processing slot. */
    private void deliver(Shot shot,Throwable reason) {
        synchronized(shot) {
            if(shot.cancelled||shot.delivered) return;
            shot.delivered=true;
        }
        main.removeCallbacks(shot.timeout);
        boolean usable=shot.rawAvailable||shot.jpegAvailable||PendingPhotoStore.hasInput(this,shot.job.id);
        if(usable) {
            try {
                PendingPhotoStore.ready(getApplicationContext(),shot.job.id);
                PendingPhotoStore.ACTIVE_CAPTURES.remove(shot.job.id);
                SaveScheduler.schedule(getApplicationContext(),shot.job.id);
            } catch(Exception error) {
                // The source files remain even if journal/scheduler handoff fails.
                PendingPhotoStore.error(getApplicationContext(),shot.job.id,error);
                Log.e("CinemaCamera","Photo retained for scheduled recovery",error);
            }
        }
        PendingPhotoStore.ACTIVE_CAPTURES.remove(shot.job.id);
        main.post(() -> {
            if(currentShot==shot) {
                currentShot=null; captureQueue.complete(true);
            }
            if(!usable) recoverJob(shot,reason==null?new IllegalStateException("No image captured"):reason);
            else updateCaptureStatus();
            main.removeCallbacks(drainQueue); main.postDelayed(drainQueue,40);
        });
        SaveScheduler.changed(getApplicationContext());
    }

    private void recoverJob(Shot shot,Throwable reason) {
        if(shot.job.done) return;
        if(shot.job.retrying||shot.format==ImageCapture.OUTPUT_FORMAT_JPEG||!active||isDestroyed()) {
            finishJob(shot.job,null,null,reason); return;
        }
        Log.w("CinemaCamera","Queue one JPEG recapture",reason);
        shot.job.retrying=true; jpegOnly=true; retries.addLast(shot.job);
        setStatus("JPEGで撮り直します…"); drainCaptures();
    }

    private void finishJob(PhotoJob job,Uri uri,Bitmap thumbnail,Throwable error) {
        if(job.done) { if(thumbnail!=null) thumbnail.recycle(); return; }
        job.done=true; PendingPhotoStore.ACTIVE_CAPTURES.remove(job.id);
        if(!PendingPhotoStore.hasInput(this,job.id)) {
            try { PendingPhotoStore.remove(this,job.id); } catch(Exception ignored) {}
        }
        outstandingPhotos=PendingPhotoStore.count(this);
        if(uri!=null) completedPhotos++;
        if(isDestroyed()) { if(thumbnail!=null) thumbnail.recycle(); return; }
        if(thumbnail!=null) screen.thumbnail(thumbnail);
        if(error!=null) {
            Log.e("CinemaCamera","Photo failed",error); captureQueue.stopHold();
            setStatus("撮影・保存できませんでした");
        } else if(active) updateCaptureStatus();
        if(active) { main.removeCallbacks(drainQueue); main.post(drainQueue); }
    }

    private void updateCaptureStatus() {
        if(!active||isDestroyed()) return;
        outstandingPhotos=PendingPhotoStore.count(this);
        if(currentShot!=null) setStatus(currentShot.job.retrying?"JPEGで撮り直しています…":
            "撮影中"+(outstandingPhotos>1?" · 現像・保存待ち "+(outstandingPhotos-1)+"枚":"…"));
        else if(outstandingPhotos>0&&PendingPhotoStore.waitingToRetry(this)) setStatus("未保存 "+outstandingPhotos+"枚 · データ保管済み／自動再試行待ち");
        else if(outstandingPhotos>0) setStatus("現像・保存中 "+outstandingPhotos+"枚"+
            (outstandingPhotos>=MAX_OUTSTANDING?" · 次の撮影は待機":" · 撮影できます"));
        else setStatus(completedPhotos>0?"保存しました":"");
    }

    private void cancelPendingCapture(Shot shot,Throwable error) {
        if(shot==null) return;
        // Keep any data already received, even if only one RAW/JPEG callback
        // completed before leaving the camera. Never delete acquired originals.
        enqueueCapture(() -> deliver(shot,error));
    }

    private void enqueueCapture(Runnable action) {
        try { captureExecutor.execute(action); }
        catch(java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private static final class PhotoJob {
        final String id=java.util.UUID.randomUUID().toString();
        final boolean flash;
        final int rotation;
        final long capturedAt;
        final LookSettings lookSettings;
        final ManualSettings manual;
        org.json.JSONObject location,captureExif;
        boolean retrying,done;
        PhotoJob(boolean flash,int rotation,long capturedAt,LookSettings lookSettings,ManualSettings manual,org.json.JSONObject location) {
            this.flash=flash;this.rotation=rotation;this.capturedAt=capturedAt;this.lookSettings=lookSettings;this.manual=manual;this.location=location;
            org.json.JSONObject base;
            try{base=CaptureExif.base(capturedAt,java.util.TimeZone.getDefault().getID());}catch(Exception error){base=new org.json.JSONObject();}
            captureExif=base;
        }
    }
    private static final class Shot {
        final int token,format;
        final PhotoJob job;
        final CaptureMetadata metadata;
        final CameraCharacteristics info;
        volatile boolean cancelled,delivered;
        boolean rawReceived,jpegReceived;
        boolean rawAvailable,jpegAvailable,hasSensorExif;
        Throwable captureError;
        Runnable timeout;
        Shot(int token,int format,PhotoJob job,CaptureMetadata metadata,CameraCharacteristics info) {
            this.token=token; this.format=format; this.job=job; this.metadata=metadata; this.info=info;
        }
    }

    private void showManualSettings() {
        if(camera==null||manualLimits==null)return;
        if(currentShot!=null||!retries.isEmpty()){setStatus("撮影が終わってから設定してください");return;}
        if(manualDialog!=null&&manualDialog.isShowing())return;
        captureQueue.stopHold();
        ManualSettings initial=committedManual;
        if(!getSharedPreferences("manual-camera",0).contains("iso")&&captureMetadata.latest()!=null) {
            Long time=captureMetadata.latest().get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso=captureMetadata.latest().get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY);
            if(time!=null&&iso!=null) initial=manualLimits.fit(new ManualSettings(false,time,iso,initial.focus,initial.distance));
        }
        manualDialog=new ManualSettingsDialog(this,initial,manualLimits,x -> applyManual(x,false),x -> applyManual(x,true));
        manualDialog.show();
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void applyManual(ManualSettings requested,boolean persist) {
        if(!active||camera==null||manualLimits==null)return;
        if(pendingManual!=null)main.removeCallbacks(pendingManual);
        ManualSettings next=manualLimits.fit(requested);Camera target=camera;long generation=++manualRequest;manualChanging=true;
        pendingManual=() -> {
            try {
                CaptureRequestOptions.Builder options=new CaptureRequestOptions.Builder();
                int[] edges=characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
                if(edges!=null)for(int edge:edges)if(edge==CaptureRequest.EDGE_MODE_OFF){options.setCaptureRequestOption(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF);break;}
                if(next.exposure) {
                    options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,next.shutter)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,next.iso)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION,Math.min(manualLimits.frameMax,Math.max(33_333_333L,next.shutter+Math.min(1_000_000L,manualLimits.frameMax-next.shutter))))
                        .setCaptureRequestOption(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
                }
                if(next.focus)options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,next.distance);
                ListenableFuture<Void> future=Camera2CameraControl.from(target.getCameraControl()).setCaptureRequestOptions(options.build());
                future.addListener(() -> {
                    if(!active||camera!=target||generation!=manualRequest)return;
                    try {future.get();manualSettings=next;manualChanging=false;screen.manualMode(next.exposure,next.focus);
                        if(persist){committedManual=next;next.save(this);}drainCaptures();}
                    catch(Exception error){manualChanging=false;setStatus("撮影設定を適用できませんでした");if(persist)applyManual(committedManual,false);}
                },main::post);
            } catch(Exception error){manualChanging=false;setStatus("この撮影設定は利用できません");}
        };
        main.postDelayed(pendingManual,80);
    }

    private void configureExposure() {
        ExposureState state = camera.getCameraInfo().getExposureState();
        exposureStep=state.getExposureCompensationStep().floatValue();
        exposureSupported=state.isExposureCompensationSupported()
            &&Float.isFinite(exposureStep)&&exposureStep>0;
        if(!exposureSupported) { exposureIndex=0; exposureStep=0; desiredEv=0; return; }
        Range<Integer> range = state.getExposureCompensationRange();
        exposureLow=range.getLower(); exposureHigh=range.getUpper();
        applyExposure(Math.max(exposureLow,Math.min(exposureHigh,Math.round(desiredEv/exposureStep))));
    }

    private void adjustExposure(int startIndex,float deltaY) {
        if(!active||camera==null||!exposureSupported||manualSettings.exposure) return;
        int index=ExposureDrag.index(startIndex,deltaY,dp(100),exposureStep,exposureLow,exposureHigh);
        if(index!=exposureIndex) applyExposure(index);
    }

    private void applyExposure(int index) {
        Camera target=camera;
        long request=++exposureRequest;
        exposureIndex=index; desiredEv=index*exposureStep;
        screen.overlay.exposure(desiredEv);
        try {
            ListenableFuture<Integer> future=target.getCameraControl().setExposureCompensationIndex(index);
            future.addListener(() -> {
                if(!active||target!=camera||request!=exposureRequest) return;
                try { future.get(); }
                catch(Exception error) {
                    Log.i("CinemaCamera","Exposure adjustment unavailable",error);
                    exposureIndex=target.getCameraInfo().getExposureState().getExposureCompensationIndex();
                    desiredEv=exposureIndex*exposureStep; screen.overlay.exposure(desiredEv);
                }
            },main::post);
        } catch(Exception error) {
            Log.i("CinemaCamera","Exposure adjustment unavailable",error);
            exposureIndex=target.getCameraInfo().getExposureState().getExposureCompensationIndex();
            desiredEv=exposureIndex*exposureStep; screen.overlay.exposure(desiredEv);
        }
    }

    private boolean focusAt(float x,float y) {
        if(!active||!frameReady||camera==null||analysisUseCase==null) return false;
        PreviewFrameInfo info=surface.getPreviewInfo();
        if(info==null||info.session!=session) return false;
        float[] point=info.bufferPoint(x,y,screen.overlay.getWidth(),screen.overlay.getHeight());
        if(point==null) return false;
        Camera target=camera;
        long request=++focusRequest;
        screen.overlay.showFocus(x,y,desiredEv,exposureLow*exposureStep,
            exposureHigh*exposureStep,exposureSupported&&!manualSettings.exposure);
        try {
            SurfaceOrientedMeteringPointFactory factory=new SurfaceOrientedMeteringPointFactory(
                info.sourceWidth,info.sourceHeight,analysisUseCase);
            MeteringPoint metering=factory.createPoint(point[0]*info.sourceWidth,point[1]*info.sourceHeight,.15f);
            int flags=(manualSettings.focus?0:FocusMeteringAction.FLAG_AF)|(manualSettings.exposure?0:FocusMeteringAction.FLAG_AE);
            if(flags==0)return true;
            FocusMeteringAction action=new FocusMeteringAction.Builder(metering,flags)
                .setAutoCancelDuration(5,TimeUnit.SECONDS).build();
            ListenableFuture<FocusMeteringResult> future=target.getCameraControl().startFocusAndMetering(action);
            future.addListener(() -> {
                if(!active||camera!=target||request!=focusRequest) return;
                try { screen.overlay.focusResult(future.get().isFocusSuccessful()); }
                catch(Exception error) { screen.overlay.focusResult(false); }
            },main::post);
        } catch(Exception error) {
            Log.i("CinemaCamera","Focus metering unavailable",error); screen.overlay.focusResult(false);
        }
        return true;
    }

    private void cameraError(Throwable error) {
        Log.e("CinemaCamera","Camera unavailable",error);
        session++; focusRequest++; exposureRequest++;
        if(provider != null) provider.unbindAll();
        capture = null; camera = null; analysisUseCase=null;
        frameReady = false; screen.ready(false); screen.overlay.clearIndicator();
        Shot failed=currentShot; currentShot=null;
        cancelPendingCapture(failed,error);
        while(!retries.isEmpty()) finishJob(retries.removeFirst(),null,null,error);
        captureQueue.clear(); main.removeCallbacks(drainQueue);
        setStatus("カメラを起動できませんでした。もう一度お試しください");
        screen.permission("再試行",true);
    }

    private final ActivityResultLauncher<Intent> folderPicker=registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),result -> {
            if(result.getResultCode()!=RESULT_OK||result.getData()==null) return;
            Uri image=result.getData().getData();
            if(image==null) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(image,"image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch(Exception error) { setStatus("写真を開けるアプリがありません"); }
        });

    private void openPhotoFolder() {
        Uri folder=android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents","primary:"+PhotoStore.RELATIVE_PATH);
        try {
            // Directory-aware viewers (including Files) can browse the actual album.
            // Google Photos has no public intent for a named local device folder.
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(folder,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,folder));
        } catch(Exception unsupported) {
            try {
                // Standard system file browser starts in the same folder; selecting
                // a photo then opens the user's photo viewer with its URI grant.
                folderPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/jpeg")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,folder));
            } catch(Exception error) { setStatus("写真は Pictures/CinemaCamera に保存されています"); }
        }
    }

    private void setStatus(String message) { if(!isDestroyed()) screen.status(message); }

    private void buildUi() {
        surface=new LookSurface(this,() -> {
            if(!active) return;
            frameReady=true; screen.ready(true); screen.overlay.invalidate();
            updateCaptureStatus(); drainCaptures();
        },this::cameraError);
        screen=new CameraScreen(this,surface,new CameraScreen.Actions() {
            @Override public void tapShutter() { takePhoto(); }
            @Override public void holdShutter(boolean holding) { holdPhotos(holding); }
            @Override public boolean focus(float x,float y) { return focusAt(x,y); }
            @Override public int exposureIndex() { return exposureIndex; }
            @Override public void exposureDrag(int startIndex,float deltaY) { adjustExposure(startIndex,deltaY); }
            @Override public void gallery() { openPhotoFolder(); }
            @Override public void manualSettings() { showManualSettings(); }
            @Override public String locationLabel() { return photoLocation.label(); }
            @Override public void toggleLocation() { MainActivity.this.toggleLocation(); }
            @Override public void retrySaving() {
                setStatus("未保存の写真を再試行します…");
                processingExecutor.execute(() -> {
                    PendingPhotoStore.recover(getApplicationContext());
                    SaveScheduler.retryAll(getApplicationContext()); SaveScheduler.changed(getApplicationContext());
                });
            }
            @Override public void permission() {
                if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
                    surface.startFrames(); startCamera();
                } else if(!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:"+getPackageName())));
                } else permission.launch(Manifest.permission.CAMERA);
            }
        });
        setContentView(screen.root); screen.root.requestApplyInsets();
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
