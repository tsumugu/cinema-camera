package com.example.cinemacamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.exifinterface.media.ExifInterface;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.BooleanSupplier;

final class PhotoStore {
    private PhotoStore() {}
    static final String RELATIVE_PATH="Pictures/CinemaCamera";
    static Uri latest(ContentResolver resolver) {
        try(Cursor rows=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},MediaStore.Images.Media.RELATIVE_PATH+"=? AND "+MediaStore.Images.Media.IS_PENDING+"=0",
                new String[]{RELATIVE_PATH+"/"},MediaStore.Images.Media.DATE_ADDED+" DESC, "+MediaStore.Images.Media._ID+" DESC")) {
            if(rows!=null&&rows.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,rows.getLong(0));
        }
        return null;
    }
    static String name(PendingPhotoStore.Record record) {
        String time=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date(record.capturedAt));
        return "CINEMA_"+time+"_"+record.id+".jpg";
    }
    static final class Existing {
        final Uri uri;
        final boolean published;
        Existing(Uri uri,boolean published) { this.uri=uri; this.published=published; }
    }
    static Existing existing(ContentResolver resolver,PendingPhotoStore.Record record) {
        try(Cursor rows=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID,MediaStore.Images.Media.IS_PENDING,MediaStore.Images.Media.SIZE},
                MediaStore.Images.Media.RELATIVE_PATH+"=? AND "+MediaStore.Images.Media.DISPLAY_NAME+"=?",
                new String[]{RELATIVE_PATH+"/",name(record)},null)) {
            if(rows!=null&&rows.moveToFirst()) return new Existing(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,rows.getLong(0)),
                rows.getInt(1)==0&&rows.getLong(2)>0);
        }
        return null;
    }
    /** Stable filename and pending-row reuse make retries safe across crashes. */
    static Uri publish(ContentResolver resolver,PendingPhotoStore.Record record,Bitmap photo,BooleanSupplier stopped,
            String software,String diagnostics) throws IOException {
        Existing previous=existing(resolver,record);
        if(previous!=null&&previous.published) return previous.uri;
        if(stopped.getAsBoolean()) throw new IOException("Save stopped before publication");
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,name(record));
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,RELATIVE_PATH);
        values.put(MediaStore.Images.Media.DATE_TAKEN,record.capturedAt);
        values.put(MediaStore.Images.Media.WIDTH,photo.getWidth()); values.put(MediaStore.Images.Media.HEIGHT,photo.getHeight());
        values.put(MediaStore.Images.Media.ORIENTATION,0); values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=previous==null?resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values):previous.uri;
        if(uri==null) throw new IOException("Could not create photo");
        if(previous!=null) resolver.update(uri,values,null,null);
        // Retain pending rows on failure; the next attempt truncates and rewrites
        // this same row instead of publishing a partial image or a duplicate.
        ParcelFileDescriptor descriptor=resolver.openFileDescriptor(uri,"rwt");
        if(descriptor==null) throw new IOException("Could not open photo");
        try(ParcelFileDescriptor.AutoCloseOutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            if(!photo.compress(Bitmap.CompressFormat.JPEG,95,stream)) throw new IOException("Could not encode photo");
            stream.flush(); stream.getChannel().position(0);
            ExifInterface exif=new ExifInterface(stream.getFD());
            exif.setAttribute(ExifInterface.TAG_SOFTWARE,software);
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT,diagnostics);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_NORMAL));
            try { CaptureExif.apply(exif,record); }
            catch(Exception metadataError) { throw new IOException("Could not write capture metadata",metadataError); }
            exif.saveAttributes();
            stream.getFD().sync();
        }
        if(stopped.getAsBoolean()) throw new IOException("Save stopped before publication");
        ContentValues published=new ContentValues(); published.put(MediaStore.Images.Media.IS_PENDING,0);
        if(resolver.update(uri,published,null,null)!=1) throw new IOException("Could not publish photo");
        Existing confirmed=existing(resolver,record);
        if(confirmed==null||!confirmed.published) throw new IOException("Photo publication was not confirmed");
        return confirmed.uri;
    }
}
