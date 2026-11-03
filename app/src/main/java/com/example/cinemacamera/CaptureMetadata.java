package com.example.cinemacamera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import java.util.LinkedHashMap;

/** Match metadata to the RAW image by SENSOR_TIMESTAMP, never by arrival order. */
final class CaptureMetadata extends CameraCaptureSession.CaptureCallback {
    private final LinkedHashMap<Long,TotalCaptureResult> frames=new LinkedHashMap<>();
    @Override public synchronized void onCaptureCompleted(CameraCaptureSession session,
            CaptureRequest request,TotalCaptureResult result) {
        Integer intent=request.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
        Long time=result.get(CaptureResult.SENSOR_TIMESTAMP);
        if(time==null||intent==null||intent!=CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) return;
        frames.put(time,result);
        while(frames.size()>8) frames.remove(frames.keySet().iterator().next());
        notifyAll();
    }
    synchronized TotalCaptureResult await(long timestamp) throws InterruptedException {
        long deadline=System.nanoTime()+1_000_000_000L;
        while(!frames.containsKey(timestamp)) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) return null;
            wait(Math.max(1,remaining/1_000_000L));
        }
        return frames.remove(timestamp);
    }
}
