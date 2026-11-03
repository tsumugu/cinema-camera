package com.example.cinemacamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class PhotoStore {
    private PhotoStore() {}

    static final String RELATIVE_PATH="Pictures/CinemaCamera";
    static Uri latest(ContentResolver resolver) {
        try(android.database.Cursor rows=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},MediaStore.Images.Media.RELATIVE_PATH+"=? AND "+MediaStore.Images.Media.IS_PENDING+"=0",
                new String[]{RELATIVE_PATH+"/"},MediaStore.Images.Media.DATE_ADDED+" DESC, "+MediaStore.Images.Media._ID+" DESC")) {
            if(rows!=null&&rows.moveToFirst()) return android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,rows.getLong(0));
        }
        return null;
    }
    static Uri save(ContentResolver resolver, Bitmap photo,long capturedAt) throws IOException {
        ContentValues values = new ContentValues();
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date(capturedAt));
        String suffix = UUID.randomUUID().toString().substring(0,8);
        values.put(MediaStore.Images.Media.DISPLAY_NAME,"CINEMA_" + time + "_" + suffix + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,RELATIVE_PATH);
        values.put(MediaStore.Images.Media.DATE_TAKEN,capturedAt);
        values.put(MediaStore.Images.Media.WIDTH,photo.getWidth());
        values.put(MediaStore.Images.Media.HEIGHT,photo.getHeight());
        values.put(MediaStore.Images.Media.ORIENTATION,0);
        values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri == null) throw new IOException("Could not create photo");
        try {
            try(OutputStream stream = resolver.openOutputStream(uri)) {
                if(stream == null || !photo.compress(Bitmap.CompressFormat.JPEG,95,stream))
                    throw new IOException("Could not encode photo");
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.Images.Media.IS_PENDING,0);
            if(resolver.update(uri,published,null,null) != 1)
                throw new IOException("Could not finish saving photo");
            return uri;
        } catch(Exception error) {
            resolver.delete(uri,null,null);
            if(error instanceof IOException) throw (IOException)error;
            throw new IOException(error);
        }
    }
}
