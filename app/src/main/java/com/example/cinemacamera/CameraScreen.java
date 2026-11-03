package com.example.cinemacamera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupMenu;

/** Camera controls, sensor orientation and guides. Gallery uses an external viewer. */
final class CameraScreen implements SensorEventListener {
    interface Actions {
        void tapShutter();
        void holdShutter(boolean holding);
        boolean focus(float x,float y);
        int exposureIndex();
        void exposureDrag(int startIndex,float deltaY);
        void permission();
        void gallery();
    }
    final LinearLayout root;
    final CameraOverlay overlay;
    private final Context context;
    private final Actions actions;
    private final LookSurface surface;
    private LookSettings lookSettings;
    private FilterSettingsDialog filterDialog;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final SensorManager sensors;
    private final Sensor levelSensor;
    private final Button shutter,flashButton,gridButton,levelButton,permissionButton;
    private final TextView status;
    private final ImageButton gallery;
    private Bitmap thumbnail;
    private final OrientationEventListener orientation;
    private int captureRotation=Surface.ROTATION_0;
    private boolean flash,grid,level,flashAvailable,resumed,registered,sensorStarted;
    private float gx,gy,gz;
    private boolean shutterDown,holdTriggered,previewTouch,previewClickFromTouch;
    private int shutterPointer=-1,previewPointer=-1,startExposureIndex;
    private float downY,downX;
    private int gestureDegrees;
    private boolean exposureDragging;
    private final int touchSlop;
    private final Runnable startHold=this::beginHold;
    private void beginHold() {
        if(!shutterDown||!shutter.isEnabled()) return;
        holdTriggered=true;
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        actions.holdShutter(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    CameraScreen(Context context,LookSurface surface,Actions actions) {
        this.context=context; this.actions=actions; this.surface=surface;
        lookSettings=LookSettings.load(context); surface.setLookSettings(lookSettings);
        preferences=context.getSharedPreferences("camera-controls",Context.MODE_PRIVATE);
        flash=preferences.getBoolean("flash",false);
        grid=preferences.getBoolean("grid",false);
        level=preferences.getBoolean("level",false);
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        sensors=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if(sensor==null) sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        levelSensor=sensor;
        orientation=new OrientationEventListener(context,SensorManager.SENSOR_DELAY_UI) {
            @Override public void onOrientationChanged(int degrees) {
                if(degrees==ORIENTATION_UNKNOWN) return;
                // Sensor angle is clockwise; display rotation constants are the inverse.
                int current=captureRotation==Surface.ROTATION_90?270:
                    captureRotation==Surface.ROTATION_180?180:captureRotation==Surface.ROTATION_270?90:0;
                int distance=Math.abs(degrees-current); distance=Math.min(distance,360-distance);
                if(distance<55) return; // 10 degree hysteresis at the quadrant boundaries.
                if(degrees>=45&&degrees<135) captureRotation=Surface.ROTATION_270;
                else if(degrees>=135&&degrees<225) captureRotation=Surface.ROTATION_180;
                else if(degrees>=225&&degrees<315) captureRotation=Surface.ROTATION_90;
                else captureRotation=Surface.ROTATION_0;
                applyCaptureOrientation();
            }
        };

        root=new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13,14,13));
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(dp(12),insets.getSystemWindowInsetTop()+dp(4),dp(12),
                insets.getSystemWindowInsetBottom()+dp(8));
            return insets;
        });
        LinearLayout tools=new LinearLayout(context); tools.setGravity(Gravity.CENTER);
        flashButton=tool("フラッシュ"); gridButton=tool("グリッド"); levelButton=tool("水平器");
        Button settingsButton=tool("設定"); settingsButton.setText("設定\n⋮"); settingsButton.setTextColor(0xffc9ccc3);
        settingsButton.setOnClickListener(view -> {
            PopupMenu menu=new PopupMenu(context,settingsButton);
            menu.getMenu().add("フィルター調整").setOnMenuItemClickListener(item -> { showFilterSettings(); return true; });
            menu.show();
        });
        for(Button button:new Button[]{flashButton,gridButton,levelButton,settingsButton})
            tools.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));
        flashButton.setOnClickListener(view -> {
            flash=!flash; preferences.edit().putBoolean("flash",flash).apply(); updateTools();
        });
        root.addView(tools);
        FrameLayout preview=new FrameLayout(context);
        preview.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        overlay=new CameraOverlay(context,surface::getPreviewInfo);
        overlay.setGrid(grid); overlay.setLevel(level&&levelSensor!=null);
        preview.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        gridButton.setOnClickListener(view -> {
            grid=!grid; preferences.edit().putBoolean("grid",grid).apply();
            overlay.setGrid(grid); updateTools();
        });
        levelButton.setOnClickListener(view -> {
            level=!level; preferences.edit().putBoolean("level",level).apply();
            overlay.setLevel(level); updateTools(); updateSensors();
        });
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        overlay.setOnClickListener(view -> {
            if(!previewClickFromTouch) actions.focus(overlay.getWidth()/2f,overlay.getHeight()/2f);
        });
        overlay.setOnTouchListener((view,event) -> previewTouch(event));

        status=new TextView(context); status.setTextSize(12); status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(182,184,177)); status.setMinHeight(dp(30));
        root.addView(status);
        permissionButton=new Button(context); permissionButton.setText("カメラを許可");
        permissionButton.setVisibility(View.GONE); permissionButton.setOnClickListener(view -> actions.permission());
        root.addView(permissionButton);
        FrameLayout bottom=new FrameLayout(context);
        shutter=new Button(context); shutter.setText(""); shutter.setPadding(0,0,0,0);
        shutter.setContentDescription("撮影。長押しで連写");
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},shutterBackground(0xff777974));
        states.addState(new int[]{android.R.attr.state_pressed},shutterBackground(0xffc5c7bd));
        states.addState(new int[]{},shutterBackground(0xfff2f1e9));
        shutter.setBackground(states); shutter.setEnabled(false);
        shutter.setOnClickListener(view -> actions.tapShutter());
        shutter.setOnTouchListener((view,event) -> shutterTouch(event));
        FrameLayout.LayoutParams circle=new FrameLayout.LayoutParams(dp(76),dp(76),Gravity.CENTER);
        bottom.addView(shutter,circle);
        gallery=new ImageButton(context); gallery.setImageResource(android.R.drawable.ic_menu_gallery);
        gallery.setScaleType(ImageView.ScaleType.CENTER_CROP); gallery.setPadding(dp(3),dp(3),dp(3),dp(3));
        gallery.setBackgroundColor(0xff30342d); gallery.setContentDescription("撮影した写真のフォルダを開く");
        gallery.setOnClickListener(view -> actions.gallery());
        FrameLayout.LayoutParams album=new FrameLayout.LayoutParams(dp(60),dp(60),Gravity.LEFT|Gravity.CENTER_VERTICAL);
        album.leftMargin=dp(12); bottom.addView(gallery,album);
        root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(88)));
        TextView hint=new TextView(context); hint.setText("長押しで連写"); hint.setTextSize(10);
        hint.setTextColor(0xff92968d); hint.setGravity(Gravity.CENTER); root.addView(hint);
        updateTools();
    }

    LookSettings lookSettings() { return lookSettings; }
    private void showFilterSettings() {
        if(filterDialog!=null&&filterDialog.isShowing()) return;
        filterDialog=new FilterSettingsDialog(context,lookSettings,settings -> {
            lookSettings=settings; surface.setLookSettings(settings);
        },settings -> {
            lookSettings=settings; settings.save(context); surface.setLookSettings(settings);
        });
        filterDialog.show();
    }

    boolean flashEnabled() { return flash&&flashAvailable; }
    void flashAvailable(boolean available) { flashAvailable=available; updateTools(); }
    void ready(boolean ready) { shutter.setEnabled(ready); overlay.setEnabled(ready); overlay.invalidate(); }
    void status(String message) { status.setText(message); }
    void permission(String text,boolean visible) {
        permissionButton.setText(text); permissionButton.setVisibility(visible?View.VISIBLE:View.GONE);
    }
    int captureRotation() { return captureRotation; }
    private int uiDegrees() {
        return captureRotation==Surface.ROTATION_90?90:captureRotation==Surface.ROTATION_180?180:
            captureRotation==Surface.ROTATION_270?270:0;
    }
    private void applyCaptureOrientation() {
        // End an active drag before switching its axis; the next touch starts
        // from the current EV, so rotating the phone cannot make EV jump.
        if(previewTouch) endPreviewTouch();
        overlay.setDisplayRotation(uiDegrees());
    }
    void thumbnail(Bitmap bitmap) {
        gallery.setImageBitmap(bitmap);
        if(thumbnail!=null&&thumbnail!=bitmap) thumbnail.recycle();
        thumbnail=bitmap;
    }
    void resume() { resumed=true; if(orientation.canDetectOrientation()) orientation.enable(); updateSensors(); }
    void pause() {
        if(filterDialog!=null&&filterDialog.isShowing()) filterDialog.dismiss();
        resumed=false; orientation.disable(); stopShutterTouch(); endPreviewTouch(); overlay.clearIndicator();
        updateSensors();
    }
    void destroy() {
        pause(); handler.removeCallbacksAndMessages(null);
        gallery.setImageDrawable(null);
        if(thumbnail!=null) { thumbnail.recycle(); thumbnail=null; }
    }

    private void updateTools() {
        flashButton.setEnabled(flashAvailable);
        flashButton.setText("フラッシュ\n"+(flashAvailable?(flash?"ON":"OFF"):"—"));
        flashButton.setTextColor(flashEnabled()?0xffebc964:0xffc9ccc3);
        flashButton.setContentDescription("フラッシュ "+(flashAvailable?(flash?"オン":"オフ"):"非対応"));
        gridButton.setText("グリッド\n"+(grid?"ON":"OFF"));
        gridButton.setSelected(grid); gridButton.setTextColor(grid?0xffebc964:0xffc9ccc3);
        gridButton.setContentDescription("グリッド "+(grid?"オン":"オフ"));
        levelButton.setEnabled(levelSensor!=null);
        levelButton.setText("水平器\n"+(levelSensor==null?"—":(level?"ON":"OFF")));
        levelButton.setSelected(level); levelButton.setTextColor(level&&levelSensor!=null?0xffebc964:0xffc9ccc3);
        levelButton.setContentDescription("水平器 "+(levelSensor==null?"非対応":(level?"オン":"オフ")));
    }

    private boolean shutterTouch(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                shutterPointer=event.getPointerId(0); shutterDown=true; holdTriggered=false;
                shutter.setPressed(true); handler.postDelayed(startHold,400); return true;
            case MotionEvent.ACTION_MOVE:
                int index=event.findPointerIndex(shutterPointer);
                if(index<0||event.getX(index)<-touchSlop||event.getY(index)<-touchSlop
                        ||event.getX(index)>shutter.getWidth()+touchSlop
                        ||event.getY(index)>shutter.getHeight()+touchSlop) stopShutterTouch();
                return true;
            case MotionEvent.ACTION_UP:
                boolean click=shutterDown&&!holdTriggered;
                stopShutterTouch(); if(click) shutter.performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                stopShutterTouch(); return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getPointerId(event.getActionIndex())==shutterPointer) stopShutterTouch();
                return true;
            default: return true;
        }
    }

    private void stopShutterTouch() {
        handler.removeCallbacks(startHold);
        if(holdTriggered) actions.holdShutter(false);
        shutterDown=false; holdTriggered=false; shutterPointer=-1; shutter.setPressed(false);
    }

    private boolean previewTouch(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=event.getX(); downY=event.getY(); startExposureIndex=actions.exposureIndex();
                gestureDegrees=uiDegrees(); exposureDragging=false;
                previewTouch=actions.focus(downX,downY);
                if(!previewTouch) return false;
                previewPointer=event.getPointerId(0); overlay.setTouching(true);
                overlay.getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                if(!previewTouch) return false;
                int index=event.findPointerIndex(previewPointer);
                if(index<0) { endPreviewTouch(); return true; }
                float delta=ExposureDrag.verticalDelta(gestureDegrees,event.getX(index)-downX,event.getY(index)-downY);
                if(Math.abs(delta)>touchSlop) exposureDragging=true;
                if(exposureDragging) actions.exposureDrag(startExposureIndex,delta);
                return true;
            case MotionEvent.ACTION_UP:
                if(!previewTouch) return false;
                boolean click=!exposureDragging&&Math.hypot(event.getY()-downY,event.getX()-downX)<=touchSlop;
                endPreviewTouch();
                if(click) {
                    previewClickFromTouch=true;
                    overlay.performClick(); previewClickFromTouch=false;
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                endPreviewTouch(); return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getPointerId(event.getActionIndex())==previewPointer) endPreviewTouch();
                return true;
            default: return previewTouch;
        }
    }

    private void endPreviewTouch() {
        previewTouch=false; previewPointer=-1; exposureDragging=false; overlay.setTouching(false);
        overlay.getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void updateSensors() {
        boolean want=resumed&&level&&levelSensor!=null;
        if(want&&!registered) {
            sensorStarted=false; overlay.setReading(null);
            registered=sensors.registerListener(this,levelSensor,SensorManager.SENSOR_DELAY_UI);
            overlay.setLevel(registered);
        } else if(!want&&registered) {
            sensors.unregisterListener(this); registered=false; sensorStarted=false; overlay.setReading(null);
        }
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if(!registered||!resumed||!level) return;
        if(!sensorStarted) {
            gx=event.values[0]; gy=event.values[1]; gz=event.values[2]; sensorStarted=true;
        } else {
            float weight=event.sensor.getType()==Sensor.TYPE_GRAVITY?.3f:.12f;
            gx+=(event.values[0]-gx)*weight; gy+=(event.values[1]-gy)*weight; gz+=(event.values[2]-gz)*weight;
        }
        overlay.setReading(LevelReading.fromGravity(gx,gy,gz));
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}

    private Button tool(String description) {
        Button view=new Button(context); view.setTextSize(11); view.setAllCaps(false);
        view.setPadding(dp(4),0,dp(4),0); view.setBackgroundColor(Color.TRANSPARENT);
        view.setContentDescription(description); return view;
    }
    private GradientDrawable shutterBackground(int color) {
        GradientDrawable shape=new GradientDrawable(); shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color); shape.setStroke(dp(4),0xff4f534b); return shape;
    }
    private int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
}
