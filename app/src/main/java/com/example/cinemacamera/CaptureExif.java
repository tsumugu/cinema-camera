package com.example.cinemacamera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.os.SystemClock;
import androidx.exifinterface.media.ExifInterface;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class CaptureExif {
    static JSONObject base(long when,String zone) throws Exception {
        JSONObject tags=new JSONObject().put(ExifInterface.TAG_MAKE,Build.MANUFACTURER).put(ExifInterface.TAG_MODEL,Build.MODEL);
        dates(tags,when,zone);
        return new JSONObject().put("tags",tags).put("timezone",zone).put("metadataSource","unavailable");
    }
    static void dates(JSONObject tags,long when,String zone) throws Exception {
        TimeZone tz=TimeZone.getTimeZone(zone); SimpleDateFormat date=new SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US);date.setTimeZone(tz);
        String value=date.format(new Date(when));int minutes=tz.getOffset(when)/60000;
        String offset=String.format(Locale.US,"%s%02d:%02d",minutes<0?"-":"+",Math.abs(minutes)/60,Math.abs(minutes)%60);
        tags.put(ExifInterface.TAG_DATETIME,value).put(ExifInterface.TAG_DATETIME_ORIGINAL,value).put(ExifInterface.TAG_DATETIME_DIGITIZED,value)
            .put(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,offset).put(ExifInterface.TAG_OFFSET_TIME_DIGITIZED,offset)
            .put(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,String.format(Locale.US,"%03d",when%1000));
    }
    static JSONObject fromResult(JSONObject initial,CaptureResult result,CameraCharacteristics info) throws Exception {
        JSONObject data=new JSONObject(initial.toString()),tags=data.getJSONObject("tags");
        Long time=result.get(CaptureResult.SENSOR_EXPOSURE_TIME),timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
        Integer iso=result.get(CaptureResult.SENSOR_SENSITIVITY),ae=result.get(CaptureResult.CONTROL_AE_MODE);
        Float aperture=result.get(CaptureResult.LENS_APERTURE),focal=result.get(CaptureResult.LENS_FOCAL_LENGTH);
        if(time!=null&&time>0) {tags.put(ExifInterface.TAG_EXPOSURE_TIME,ratio(time,1_000_000_000L));tags.put(ExifInterface.TAG_SHUTTER_SPEED_VALUE,decimal(-Math.log(time/1e9)/Math.log(2)));}
        if(iso!=null&&iso>0) tags.put(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,Integer.toString(iso));
        if(aperture!=null&&aperture>0) {tags.put(ExifInterface.TAG_F_NUMBER,decimal(aperture));tags.put(ExifInterface.TAG_APERTURE_VALUE,decimal(2*Math.log(aperture)/Math.log(2)));}
        if(focal!=null&&focal>0) tags.put(ExifInterface.TAG_FOCAL_LENGTH,decimal(focal));
        Integer compensation=result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        android.util.Rational step=info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        boolean manual=ae!=null&&ae==CaptureResult.CONTROL_AE_MODE_OFF;
        if(manual||compensation!=null&&step!=null) tags.put(ExifInterface.TAG_EXPOSURE_BIAS_VALUE,decimal(manual?0:compensation*step.doubleValue()));
        if(ae!=null)tags.put(ExifInterface.TAG_EXPOSURE_MODE,manual?"1":"0").put(ExifInterface.TAG_EXPOSURE_PROGRAM,manual?"1":"2");
        Integer awb=result.get(CaptureResult.CONTROL_AWB_MODE);if(awb!=null) tags.put(ExifInterface.TAG_WHITE_BALANCE,awb==CaptureResult.CONTROL_AWB_MODE_AUTO?"0":"1");
        Integer flash=result.get(CaptureResult.FLASH_STATE);if(flash!=null) tags.put(ExifInterface.TAG_FLASH,flash==CaptureResult.FLASH_STATE_FIRED||flash==CaptureResult.FLASH_STATE_PARTIAL?"1":"0");
        Float focus=result.get(CaptureResult.LENS_FOCUS_DISTANCE);if(focus!=null) data.put("focusDiopters",focus.doubleValue());
        Integer clock=info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if(timestamp!=null&&clock!=null&&clock==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            long age=SystemClock.elapsedRealtimeNanos()-timestamp;
            if(age>=0&&age<120_000_000_000L) dates(tags,System.currentTimeMillis()-age/1_000_000L,data.getString("timezone"));
        }
        return data.put("metadataSource","camera2-capture-result");
    }
    static JSONObject fromJpeg(JSONObject initial,byte[] bytes) throws Exception {
        JSONObject data=new JSONObject(initial.toString()),tags=data.getJSONObject("tags");
        ExifInterface source=new ExifInterface(new ByteArrayInputStream(bytes));
        for(String tag:new String[]{ExifInterface.TAG_EXPOSURE_TIME,ExifInterface.TAG_SHUTTER_SPEED_VALUE,ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_F_NUMBER,ExifInterface.TAG_APERTURE_VALUE,ExifInterface.TAG_EXPOSURE_BIAS_VALUE,ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,ExifInterface.TAG_WHITE_BALANCE,ExifInterface.TAG_EXPOSURE_MODE,ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_DATETIME_ORIGINAL,ExifInterface.TAG_DATETIME_DIGITIZED,ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,ExifInterface.TAG_OFFSET_TIME_DIGITIZED}) {
            String v=source.getAttribute(tag);if(v!=null) tags.put(tag,v);
        }
        return data.put("metadataSource","camera-jpeg-exif");
    }
    static void apply(ExifInterface exif,PendingPhotoStore.Record record) throws Exception {
        JSONObject capture=record.json.optJSONObject("capture");
        if(capture==null) capture=base(record.capturedAt,TimeZone.getDefault().getID());
        JSONObject tags=capture.optJSONObject("tags");
        if(tags!=null) {java.util.Iterator<String> names=tags.keys();while(names.hasNext()){String name=names.next();exif.setAttribute(name,tags.getString(name));}}
        JSONObject gps=record.json.optJSONObject("location");
        if(gps!=null) {
            exif.setLatLong(gps.getDouble("latitude"),gps.getDouble("longitude"));
            exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD,gps.optString("provider","unknown").toUpperCase(Locale.US));
            if(gps.has("altitude")) exif.setAltitude(gps.getDouble("altitude"));
            if(gps.has("accuracy")) exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR,decimal(gps.getDouble("accuracy")));
            Date date=new Date(gps.getLong("time"));SimpleDateFormat day=new SimpleDateFormat("yyyy:MM:dd",Locale.US),clock=new SimpleDateFormat("HH:mm:ss",Locale.US);
            day.setTimeZone(TimeZone.getTimeZone("UTC"));clock.setTimeZone(TimeZone.getTimeZone("UTC"));
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP,day.format(date));exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP,clock.format(date));
        }
    }
    static String decimal(double value){return String.format(Locale.US,"%.8f",value);}
    static String ratio(long n,long d){long a=n,b=d;while(b!=0){long t=a%b;a=b;b=t;}n/=a;d/=a;while(n>0xffffffffL||d>0xffffffffL){n=(n+1)/2;d=Math.max(1,(d+1)/2);}return n+"/"+d;}
}
