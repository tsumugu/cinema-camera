package com.example.cinemacamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** Foreground-only geotagging. Never substitute an old fix or a worker-time location. */
final class PhotoLocation implements LocationListener {
    private final Context context;
    private final LocationManager manager;
    private Location latest;
    PhotoLocation(Context context){this.context=context;manager=context.getSystemService(LocationManager.class);}
    boolean enabled(){return context.getSharedPreferences("geotag",0).getBoolean("enabled",true);}
    void enabled(boolean value){context.getSharedPreferences("geotag",0).edit().putBoolean("enabled",value).apply();if(!value) stop();}
    boolean permitted(){return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    void start(){
        stop();if(!enabled()||!permitted()||manager==null)return;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER}) {
            try {if(manager.isProviderEnabled(provider)){onLocationChanged(manager.getLastKnownLocation(provider));manager.requestLocationUpdates(provider,1000,0,this,Looper.getMainLooper());}}
            catch(IllegalArgumentException|SecurityException ignored){}
        }
    }
    void stop(){if(manager!=null)try{manager.removeUpdates(this);}catch(SecurityException ignored){}latest=null;}
    private boolean fresh(Location value){if(value==null)return false;long age=SystemClock.elapsedRealtimeNanos()-value.getElapsedRealtimeNanos();return age>=0&&age<=60_000_000_000L;}
    @Override public synchronized void onLocationChanged(Location value){
        if(!fresh(value))return;
        if(!fresh(latest)||value.getElapsedRealtimeNanos()-latest.getElapsedRealtimeNanos()>10_000_000_000L
                ||!latest.hasAccuracy()||value.hasAccuracy()&&value.getAccuracy()<=latest.getAccuracy())latest=new Location(value);
    }
    synchronized JSONObject snapshot(){
        if(!enabled()||!permitted()||!fresh(latest))return null;
        try {
            double lat=latest.getLatitude(),lon=latest.getLongitude();if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return null;
            JSONObject j=new JSONObject().put("latitude",lat).put("longitude",lon).put("time",latest.getTime()).put("provider",latest.getProvider()==null?"unknown":latest.getProvider());
            if(latest.hasAltitude())j.put("altitude",latest.getAltitude());if(latest.hasAccuracy())j.put("accuracy",(double)latest.getAccuracy());return j;
        } catch(Exception error){return null;}
    }
    String label(){
        if(!enabled())return "位置情報を記録：OFF";
        if(!permitted())return "位置情報を記録：許可が必要";
        if(manager==null||!manager.isLocationEnabled())return "位置情報を記録：端末の位置情報がOFF";
        boolean precise=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        return snapshot()==null?"位置情報を記録：測位待ち":precise?"位置情報を記録：ON":"位置情報を記録：概算";
    }
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onProviderDisabled(String provider){}
}
