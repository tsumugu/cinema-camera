package com.example.cinemacamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
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
    private PhotoRenderer photoRenderer;
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
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),granted -> {
            if(granted) startCamera();
            else {
                screen.permission("カメラを許可",true);
                setStatus("撮影するにはカメラの許可が必要です");
            }
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        photoRenderer=new PhotoRenderer(getApplicationContext());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.CAMERA);
    }

    @Override protected void onResume() {
        super.onResume(); active = true;
        screen.resume();
        surface.startFrames(); surface.onResume();
        if(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        processingExecutor.execute(() -> {
            try {
                Uri latest=PhotoStore.latest(getContentResolver());
                if(latest==null) return;
                Bitmap thumbnail=getContentResolver().loadThumbnail(latest,new Size(128,128),null);
                main.post(() -> { if(!isDestroyed()) screen.thumbnail(thumbnail); else thumbnail.recycle(); });
            } catch(Exception error) { Log.i("CinemaCamera","No thumbnail",error); }
        });
    }

    @Override protected void onPause() {
        active = false; session++; frameReady = false; screen.ready(false);
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
        // Finish capture callbacks already running before closing the processing queue.
        // Acquired photos continue when an external viewer pauses this Activity.
        enqueueCapture(() -> {
            processingExecutor.execute(photoRenderer::close); processingExecutor.shutdown();
        });
        captureExecutor.shutdown();
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
            reduceEdgeEnhancement();
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
        if(!active||!frameReady||capture==null||currentShot!=null) return;
        if(!retries.isEmpty()) {
            if(capture.getOutputFormat()!=ImageCapture.OUTPUT_FORMAT_JPEG) {
                surface.startFrames(); startCamera(); return;
            }
            beginShot(retries.removeFirst()); return;
        }
        if(outstandingPhotos>=MAX_OUTSTANDING) { updateCaptureStatus(); return; }
        if(captureQueue.startNext()) {
            outstandingPhotos++;
            beginShot(new PhotoJob(screen.flashEnabled(),screen.captureRotation(),System.currentTimeMillis(),screen.lookSettings()));
        }
    }

    private void beginShot(PhotoJob job) {
        if(capture==null||!active) { finishJob(job,null,null,new IllegalStateException("Camera is not ready")); return; }
        Shot shot=new Shot(session,capture.getOutputFormat(),job,captureMetadata,characteristics);
        currentShot=shot;
        shot.timeout=() -> enqueueCapture(() -> deliver(shot,new IllegalStateException("Capture timed out")));
        main.postDelayed(shot.timeout,20_000);
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
                    RawDeveloper.Input input=RawMetadata.input(image,shot.info,
                        shot.metadata.await(image.getImageInfo().getTimestamp()));
                    // Disk-backed RAW snapshots avoid retaining several full sensor
                    // buffers in the Java heap while the next exposure is acquired.
                    shot.rawFile=java.io.File.createTempFile("capture_",".raw",getCacheDir());
                    try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(shot.rawFile,"rw")) {
                        java.nio.ByteBuffer bytes=input.bytes.duplicate();
                        while(bytes.hasRemaining()) file.getChannel().write(bytes);
                        input.bytes=file.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,file.length());
                    }
                    shot.raw=input;
                } catch(Throwable error) { shot.captureError=error; Log.w("CinemaCamera","RAW snapshot unavailable",error); }
            } else {
                shot.jpegReceived=true;
                try { shot.jpeg=PhotoBitmaps.snapshot(image); }
                catch(Throwable error) { shot.captureError=error; }
            }
        } finally { image.close(); }
        if(shot.cancelled) { cleanup(shot); return; }
        if((shot.format==ImageCapture.OUTPUT_FORMAT_RAW_JPEG&&shot.rawReceived&&shot.jpegReceived)
                ||(shot.format==ImageCapture.OUTPUT_FORMAT_RAW&&shot.rawReceived)
                ||(shot.format==ImageCapture.OUTPUT_FORMAT_JPEG&&shot.jpegReceived)) deliver(shot,shot.captureError);
    }

    /** Capture executor only. Release the sensor slot, retaining the processing slot. */
    private void deliver(Shot shot,Throwable reason) {
        synchronized(shot) {
            if(shot.cancelled||shot.delivered) return;
            shot.delivered=true;
        }
        main.removeCallbacks(shot.timeout);
        boolean usable=shot.raw!=null||shot.jpeg!=null;
        main.post(() -> {
            if(currentShot==shot) {
                currentShot=null; captureQueue.complete(true);
            }
            if(!usable) recoverJob(shot,reason==null?new IllegalStateException("No image captured"):reason);
            else updateCaptureStatus();
            main.removeCallbacks(drainQueue); main.postDelayed(drainQueue,40);
        });
        if(usable) processingExecutor.execute(() -> developAndSave(shot));
        else cleanup(shot);
    }

    /** One worker owns its own offscreen EGL context, independent of live preview. */
    private void developAndSave(Shot shot) {
        Bitmap rendered=null,thumbnail=null;
        try {
            if(shot.raw!=null) {
                try {
                    LinearPhoto linear=RawDeveloper.develop(shot.raw,2560);
                    shot.raw=null; deleteRawFile(shot);
                    rendered=photoRenderer.render(linear,shot.job.lookSettings);
                } catch(Throwable error) { Log.w("CinemaCamera","RAW development failed; use JPEG",error); }
            }
            if(rendered==null) {
                if(shot.jpeg==null) throw new IllegalStateException("No usable JPEG fallback");
                Bitmap jpeg=PhotoBitmaps.from(shot.jpeg,2560);
                shot.jpeg=null;
                rendered=photoRenderer.render(jpeg,shot.job.lookSettings);
            }
            Uri uri=PhotoStore.save(getContentResolver(),rendered,shot.job.capturedAt);
            // A thumbnail failure must not turn a successfully saved photo into a retry.
            try { thumbnail=android.media.ThumbnailUtils.extractThumbnail(rendered,128,128); }
            catch(Throwable ignored) {}
            Bitmap resultThumbnail=thumbnail;
            main.post(() -> finishJob(shot.job,uri,resultThumbnail,null));
        } catch(Throwable error) { main.post(() -> recoverJob(shot,error)); }
        finally { if(rendered!=null&&rendered!=thumbnail) rendered.recycle(); cleanup(shot); }
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
        job.done=true; outstandingPhotos=Math.max(0,outstandingPhotos-1);
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
        if(currentShot!=null) setStatus(currentShot.job.retrying?"JPEGで撮り直しています…":
            "撮影中"+(outstandingPhotos>1?" · 現像・保存待ち "+(outstandingPhotos-1)+"枚":"…"));
        else if(outstandingPhotos>0) setStatus("現像・保存中 "+outstandingPhotos+"枚"+
            (outstandingPhotos>=MAX_OUTSTANDING?" · 次の撮影は待機":" · 撮影できます"));
        else setStatus(completedPhotos>0?"保存しました":"");
    }

    private void cancelPendingCapture(Shot shot,Throwable error) {
        if(shot==null) return;
        synchronized(shot) {
            if(shot.delivered) return;
            shot.cancelled=true;
        }
        main.removeCallbacks(shot.timeout); finishJob(shot.job,null,null,error);
        enqueueCapture(() -> cleanup(shot));
    }

    private void deleteRawFile(Shot shot) {
        if(shot.rawFile!=null) { shot.rawFile.delete(); shot.rawFile=null; }
    }
    private void cleanup(Shot shot) { shot.raw=null; shot.jpeg=null; deleteRawFile(shot); }
    private void enqueueCapture(Runnable action) {
        try { captureExecutor.execute(action); }
        catch(java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private static final class PhotoJob {
        final boolean flash;
        final int rotation;
        final long capturedAt;
        final LookSettings lookSettings;
        boolean retrying,done;
        PhotoJob(boolean flash,int rotation,long capturedAt,LookSettings lookSettings) {
            this.flash=flash; this.rotation=rotation; this.capturedAt=capturedAt; this.lookSettings=lookSettings;
        }
    }
    private static final class Shot {
        final int token,format;
        final PhotoJob job;
        final CaptureMetadata metadata;
        final CameraCharacteristics info;
        volatile boolean cancelled,delivered;
        boolean rawReceived,jpegReceived;
        RawDeveloper.Input raw;
        PhotoBitmaps.Encoded jpeg;
        java.io.File rawFile;
        Throwable captureError;
        Runnable timeout;
        Shot(int token,int format,PhotoJob job,CaptureMetadata metadata,CameraCharacteristics info) {
            this.token=token; this.format=format; this.job=job; this.metadata=metadata; this.info=info;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void reduceEdgeEnhancement() {
        // Best effort only. No assumption about RAW, manual WB or vendor HDR.
        try {
            int[] modes = Camera2CameraInfo.from(camera.getCameraInfo())
                .getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            if(modes == null) return;
            for(int mode : modes) if(mode == CaptureRequest.EDGE_MODE_OFF) {
                Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(
                    new CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF).build());
                break;
            }
        } catch(Exception error) { Log.i("CinemaCamera","Using device edge processing",error); }
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
        if(!active||camera==null||!exposureSupported) return;
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
            exposureHigh*exposureStep,exposureSupported);
        try {
            SurfaceOrientedMeteringPointFactory factory=new SurfaceOrientedMeteringPointFactory(
                info.sourceWidth,info.sourceHeight,analysisUseCase);
            MeteringPoint metering=factory.createPoint(point[0]*info.sourceWidth,point[1]*info.sourceHeight,.15f);
            FocusMeteringAction action=new FocusMeteringAction.Builder(metering,
                FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE)
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
