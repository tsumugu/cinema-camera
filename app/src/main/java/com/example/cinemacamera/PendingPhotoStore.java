package com.example.cinemacamera;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable source data and journal. Never use cacheDir for accepted photographs. */
final class PendingPhotoStore {
    static final String OWNER=UUID.randomUUID().toString();
    static final Object JOURNAL_LOCK=new Object();
    static final java.util.Set<String> ACTIVE_CAPTURES=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final class Record {
        final String id,state,error;
        final long capturedAt;
        final LookSettings look;
        final JSONObject json;
        Record(JSONObject value) throws Exception {
            json=value; id=value.getString("id"); state=value.getString("state");
            capturedAt=value.getLong("capturedAt"); error=value.optString("error","");
            JSONObject v=value.getJSONObject("look");
            look=new LookSettings(v.getInt("strength"),v.getInt("cool"),v.getInt("saturation"),v.getInt("contrast"),v.getInt("grain"));
        }
    }
    static File root(Context context) { return new File(context.getNoBackupFilesDir(),"pending-photos"); }
    static File dir(Context context,String id) {
        if(!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid photo ID");
        return new File(root(context),id);
    }
    static void create(Context context,String id,long time,LookSettings look,JSONObject capture,JSONObject location,JSONObject manual) throws Exception {
        ACTIVE_CAPTURES.add(id);
        synchronized(JOURNAL_LOCK) {
            File folder=dir(context,id);
            if(new File(folder,"job.json").exists()) {
                JSONObject value=readJson(new File(folder,"job.json"));
                if(value.getString("state").equals("capturing")) {
                    value.put("capture",capture).put("location",location==null?JSONObject.NULL:location).put("manualRequest",manual);
                    writeJson(new File(folder,"job.json"),value);
                }
                return;
            }
            JSONObject settings=new JSONObject().put("strength",look.strength).put("cool",look.cool)
                .put("saturation",look.saturation).put("contrast",look.contrast).put("grain",look.grain);
            writeJson(new File(folder,"job.json"),new JSONObject().put("version",1).put("id",id)
                .put("capturedAt",time).put("look",settings).put("owner",OWNER).put("state","capturing")
                .put("capture",capture).put("location",location==null?JSONObject.NULL:location).put("manualRequest",manual));
        }
    }
    static void captureMetadata(Context context,String id,JSONObject capture) throws Exception {
        synchronized(JOURNAL_LOCK){File file=new File(dir(context,id),"job.json");JSONObject value=readJson(file);value.put("capture",capture);writeJson(file,value);}
    }
    static Record read(Context context,String id) throws Exception {
        synchronized(JOURNAL_LOCK) { return new Record(readJson(new File(dir(context,id),"job.json"))); }
    }
    static void ready(Context context,String id) throws Exception { change(context,id,"ready",null,null); }
    static void saved(Context context,String id,Uri uri) throws Exception { change(context,id,"saved",uri,null); }
    static void error(Context context,String id,Throwable error) {
        try { change(context,id,null,null,error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage())); }
        catch(Exception ignored) {}
    }
    private static void change(Context context,String id,String state,Uri uri,String error) throws Exception {
        synchronized(JOURNAL_LOCK) {
            File file=new File(dir(context,id),"job.json"); JSONObject value=readJson(file);
            if(state!=null) value.put("state",state);
            if(uri!=null) value.put("uri",uri.toString());
            value.put("error",error==null?"":error); writeJson(file,value);
        }
    }
    static boolean hasRaw(Context context,String id) {
        File folder=dir(context,id);
        return new File(folder,"raw.bin").isFile()&&new File(folder,"raw.json").isFile();
    }
    static boolean hasJpeg(Context context,String id) {
        File folder=dir(context,id);
        return new File(folder,"jpeg.bin").isFile()&&new File(folder,"jpeg.json").isFile();
    }
    static boolean hasInput(Context context,String id) { return hasRaw(context,id)||hasJpeg(context,id); }
    static void writeRaw(Context context,String id,RawDeveloper.Input in) throws Exception {
        File folder=dir(context,id);
        JSONObject meta=new JSONObject().put("width",in.width).put("height",in.height).put("rowStride",in.rowStride)
            .put("pixelStride",in.pixelStride).put("left",in.left).put("top",in.top).put("cropWidth",in.cropWidth)
            .put("cropHeight",in.cropHeight).put("originX",in.originX).put("originY",in.originY).put("cfa",in.cfa)
            .put("rotation",in.rotation).put("white",in.white).put("exposureBoost",in.exposureBoost)
            .put("shadingWidth",in.shadingWidth).put("shadingHeight",in.shadingHeight)
            .put("black",array(in.black)).put("gains",array(in.gains)).put("matrix",array(in.matrix)).put("shading",array(in.shading));
        writeJson(new File(folder,"raw.json"),meta);
        writeBytes(new File(folder,"raw.bin"),in.bytes.duplicate());
    }
    static RawDeveloper.Input raw(Context context,String id) throws Exception {
        File folder=dir(context,id); JSONObject j=readJson(new File(folder,"raw.json"));
        RawDeveloper.Input in=new RawDeveloper.Input();
        in.width=j.getInt("width"); in.height=j.getInt("height"); in.rowStride=j.getInt("rowStride"); in.pixelStride=j.getInt("pixelStride");
        in.left=j.getInt("left"); in.top=j.getInt("top"); in.cropWidth=j.getInt("cropWidth"); in.cropHeight=j.getInt("cropHeight");
        in.originX=j.getInt("originX"); in.originY=j.getInt("originY"); in.cfa=j.getInt("cfa"); in.rotation=j.getInt("rotation");
        in.white=(float)j.getDouble("white"); in.exposureBoost=(float)j.getDouble("exposureBoost");
        in.shadingWidth=j.getInt("shadingWidth"); in.shadingHeight=j.getInt("shadingHeight");
        in.black=floats(j.getJSONArray("black")); in.gains=floats(j.getJSONArray("gains"));
        in.matrix=floats(j.getJSONArray("matrix")); in.shading=floats(j.getJSONArray("shading"));
        try(RandomAccessFile file=new RandomAccessFile(new File(folder,"raw.bin"),"r")) {
            in.bytes=file.getChannel().map(FileChannel.MapMode.READ_ONLY,0,file.length());
        }
        return in;
    }
    static void writeJpeg(Context context,String id,PhotoBitmaps.Encoded image) throws Exception {
        File folder=dir(context,id); Rect c=image.crop;
        writeJson(new File(folder,"jpeg.json"),new JSONObject().put("width",image.width).put("height",image.height)
            .put("rotation",image.rotation).put("left",c.left).put("top",c.top).put("right",c.right).put("bottom",c.bottom));
        writeBytes(new File(folder,"jpeg.bin"),ByteBuffer.wrap(image.bytes));
    }
    static PhotoBitmaps.Encoded jpeg(Context context,String id) throws Exception {
        File folder=dir(context,id); JSONObject j=readJson(new File(folder,"jpeg.json"));
        byte[] data=new AtomicFile(new File(folder,"jpeg.bin")).readFully();
        return new PhotoBitmaps.Encoded(data,new Rect(j.getInt("left"),j.getInt("top"),j.getInt("right"),j.getInt("bottom")),
            j.getInt("width"),j.getInt("height"),j.getInt("rotation"));
    }
    static List<Record> records(Context context) {
        List<Record> records=new ArrayList<>(); File[] folders=root(context).listFiles();
        if(folders!=null) for(File folder:folders) {
            if(!folder.isDirectory()) continue;
            try { records.add(read(context,folder.getName())); }
            catch(Exception error) { Log.e("CinemaCamera","Retaining unreadable pending record "+folder.getName(),error); }
        }
        return records;
    }
    static int count(Context context) {
        File[] folders=root(context).listFiles(); int count=0;
        if(folders!=null) for(File folder:folders) if(folder.isDirectory()&&
            (new File(folder,"job.json").isFile()||new File(folder,"job.json.bak").isFile())) count++;
        return count;
    }
    static boolean waitingToRetry(Context context) {
        for(Record record:records(context)) if(!record.error.isEmpty()) return true;
        return false;
    }
    static void recover(Context context) {
        for(Record record:records(context)) {
            try {
                if(record.state.equals("saved")) { remove(context,record.id); continue; }
                if(record.state.equals("capturing")) {
                    if(record.json.optString("owner").equals(OWNER)&&ACTIVE_CAPTURES.contains(record.id)) continue;
                    if(!hasInput(context,record.id)) { remove(context,record.id); continue; }
                    ready(context,record.id);
                }
                SaveScheduler.schedule(context,record.id);
            } catch(Exception error) { Log.e("CinemaCamera","Pending photo recovery failed",error); }
        }
    }
    static void remove(Context context,String id) throws IOException {
        synchronized(JOURNAL_LOCK) {
            File folder=dir(context,id); File[] files=folder.listFiles();
            // Remove the journal last, so a crash during cleanup is recoverable.
            if(files!=null) for(File file:files) if(!file.getName().startsWith("job.json")&&!file.delete()) throw new IOException("Cannot remove "+file.getName());
            if(files!=null) for(File file:files) if(file.getName().startsWith("job.json")&&!file.delete()) throw new IOException("Cannot remove journal");
            if(folder.exists()&&!folder.delete()) throw new IOException("Cannot remove completed photo directory");
        }
    }
    private static JSONArray array(float[] values) throws Exception {
        JSONArray result=new JSONArray(); if(values!=null) for(float v:values) result.put((double)v); return result;
    }
    private static float[] floats(JSONArray values) throws Exception {
        float[] result=new float[values.length()]; for(int i=0;i<result.length;i++) result[i]=(float)values.getDouble(i); return result;
    }
    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));
    }
    private static void writeJson(File file,JSONObject value) throws Exception {
        writeBytes(file,ByteBuffer.wrap(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static void writeBytes(File file,ByteBuffer bytes) throws IOException {
        if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs()) throw new IOException("Cannot create pending photo directory");
        AtomicFile atomic=new AtomicFile(file); FileOutputStream stream=atomic.startWrite();
        try {
            FileChannel channel=stream.getChannel(); while(bytes.hasRemaining()) channel.write(bytes);
            stream.getFD().sync(); atomic.finishWrite(stream);
            // Persist the rename as well as file contents before accepting input.
            java.io.FileDescriptor directory=null;
            try {
                directory=android.system.Os.open(file.getParent(),android.system.OsConstants.O_RDONLY,0);
                android.system.Os.fsync(directory);
            } catch(android.system.ErrnoException error) { throw new IOException("Cannot sync pending photo directory",error); }
            finally { if(directory!=null) try { android.system.Os.close(directory); } catch(android.system.ErrnoException ignored) {} }
        } catch(Throwable error) { atomic.failWrite(stream); throw error; }
    }
}
