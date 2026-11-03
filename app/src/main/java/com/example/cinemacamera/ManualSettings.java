package com.example.cinemacamera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import org.json.JSONObject;

final class ManualSettings {
    final boolean exposure,focus;
    final long shutter;
    final int iso;
    final float distance;
    ManualSettings(boolean exposure,long shutter,int iso,boolean focus,float distance){this.exposure=exposure;this.shutter=shutter;this.iso=iso;this.focus=focus;this.distance=distance;}
    static ManualSettings load(Context c){android.content.SharedPreferences p=c.getSharedPreferences("manual-camera",0);return new ManualSettings(p.getBoolean("exposure",false),p.getLong("shutter",8_000_000),p.getInt("iso",100),p.getBoolean("focus",false),p.getFloat("distance",0));}
    void save(Context c){c.getSharedPreferences("manual-camera",0).edit().putBoolean("exposure",exposure).putLong("shutter",shutter).putInt("iso",iso).putBoolean("focus",focus).putFloat("distance",distance).apply();}
    JSONObject json() throws Exception{return new JSONObject().put("manualExposure",exposure).put("requestedExposureNs",shutter).put("requestedIso",iso).put("manualFocus",focus).put("requestedFocusDiopters",(double)distance);}
    static final class Limits {
        final boolean exposure,focus;
        final long low,high,frameMax;
        final int isoLow,isoHigh;
        final float near;
        Limits(CameraCharacteristics c){
            int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),af=c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),ae=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            Range<Long> times=c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);Range<Integer> sensitivities=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Long frame=c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);Float minimum=c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            frameMax=frame==null?1_000_000_000L:frame;low=times==null?100_000L:Math.max(1,times.getLower());high=times==null?1_000_000_000L:Math.min(times.getUpper(),frameMax);
            isoLow=sensitivities==null?100:sensitivities.getLower();isoHigh=sensitivities==null?100:sensitivities.getUpper();near=minimum==null||!Float.isFinite(minimum)?0:Math.max(0,minimum);
            exposure=contains(caps,CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)&&contains(ae,CaptureRequest.CONTROL_AE_MODE_OFF)&&times!=null&&sensitivities!=null&&frame!=null&&high>=low&&isoLow>0&&isoHigh>=isoLow&&frameMax>0;
            focus=near>0&&contains(af,CaptureRequest.CONTROL_AF_MODE_OFF);
        }
        ManualSettings fit(ManualSettings s){return new ManualSettings(s.exposure&&exposure,Math.max(low,Math.min(high,s.shutter)),Math.max(isoLow,Math.min(isoHigh,s.iso)),s.focus&&focus,Math.max(0,Math.min(near,s.distance)));}
        private static boolean contains(int[] list,int value){if(list!=null)for(int x:list)if(x==value)return true;return false;}
    }
}
