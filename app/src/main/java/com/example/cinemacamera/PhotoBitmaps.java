package com.example.cinemacamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;

final class PhotoBitmaps {
    private PhotoBitmaps() {}

    static final class Encoded {
        final byte[] bytes;
        final Rect crop;
        final int width,height,rotation;
        Encoded(byte[] bytes,Rect crop,int width,int height,int rotation) {
            this.bytes=bytes; this.crop=crop; this.width=width; this.height=height; this.rotation=rotation;
        }
    }
    static Encoded snapshot(ImageProxy image) {
        java.nio.ByteBuffer bytes=image.getPlanes()[0].getBuffer().duplicate();
        byte[] copy=new byte[bytes.remaining()]; bytes.get(copy);
        return new Encoded(copy,new Rect(image.getCropRect()),image.getWidth(),image.getHeight(),
            image.getImageInfo().getRotationDegrees());
    }
    static Bitmap from(Encoded image,int maxEdge) {
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,options);
        options.inSampleSize=1;
        int edge=Math.max(image.crop.width(),image.crop.height());
        while(edge/options.inSampleSize>maxEdge*2) options.inSampleSize*=2;
        options.inJustDecodeBounds=false; options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap source=BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,options);
        if(source==null) throw new IllegalArgumentException("Cannot decode captured JPEG");
        Bitmap result=null;
        try {
            float sx=(float)source.getWidth()/image.width,sy=(float)source.getHeight()/image.height;
            int left=Math.max(0,Math.round(image.crop.left*sx)),top=Math.max(0,Math.round(image.crop.top*sy));
            int width=Math.min(source.getWidth()-left,Math.max(1,Math.round(image.crop.width()*sx)));
            int height=Math.min(source.getHeight()-top,Math.max(1,Math.round(image.crop.height()*sy)));
            float scale=Math.min(1f,(float)maxEdge/Math.max(width,height));
            Matrix matrix=new Matrix(); matrix.postRotate(image.rotation); matrix.postScale(scale,scale);
            result=Bitmap.createBitmap(source,left,top,width,height,matrix,true);
            return result;
        } finally { if(result!=source) source.recycle(); }
    }

    /** Returns an upright, cropped, SDR sRGB bitmap. Does not close the proxy. */
    static Bitmap from(ImageProxy image, int maxEdge) {
        Bitmap source = image.toBitmap();
        Bitmap transformed = null;
        try {
            Rect crop = image.getCropRect();
            float scale = Math.min(1f, (float)maxEdge / Math.max(crop.width(),crop.height()));
            Matrix matrix = new Matrix();
            matrix.postRotate(image.getImageInfo().getRotationDegrees());
            matrix.postScale(scale,scale);
            transformed = Bitmap.createBitmap(source,crop.left,crop.top,
                crop.width(),crop.height(),matrix,true);
            if(transformed.getColorSpace() != null && transformed.getColorSpace().isSrgb()
                    && transformed.getConfig() == Bitmap.Config.ARGB_8888) {
                if(transformed != source) source.recycle();
                return transformed;
            }
            Bitmap srgb = Bitmap.createBitmap(transformed.getWidth(),transformed.getHeight(),
                Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.SRGB));
            new Canvas(srgb).drawBitmap(transformed,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));
            if(transformed != source) transformed.recycle();
            source.recycle();
            return srgb;
        } catch(Throwable error) {
            if(transformed != null && transformed != source) transformed.recycle();
            source.recycle();
            throw error;
        }
    }
}
