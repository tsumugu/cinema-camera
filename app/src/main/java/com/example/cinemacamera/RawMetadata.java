package com.example.cinemacamera;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Size;
import androidx.camera.core.ImageProxy;

final class RawMetadata {
    private RawMetadata() {}

    static RawDeveloper.Input input(ImageProxy image,CameraCharacteristics info,CaptureResult result) {
        if(result==null) throw new IllegalArgumentException("No capture metadata matching RAW timestamp");
        RawDeveloper.Input in=new RawDeveloper.Input();
        in.width=image.getWidth(); in.height=image.getHeight();
        ImageProxy.PlaneProxy plane=image.getPlanes()[0];
        in.bytes=plane.getBuffer(); in.rowStride=plane.getRowStride(); in.pixelStride=plane.getPixelStride();
        Rect active=info.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        Size pixels=info.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if(active==null||pixels==null) throw new IllegalArgumentException("Missing RAW sensor geometry");
        if(in.width==pixels.getWidth()&&in.height==pixels.getHeight()) {
            in.left=active.left; in.top=active.top;
        } else if(in.width==active.width()&&in.height==active.height()) {
            in.originX=active.left; in.originY=active.top;
        } else throw new IllegalArgumentException("RAW dimensions do not match sensor metadata");
        in.cropWidth=active.width(); in.cropHeight=active.height();
        Integer cfa=info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if(cfa==null) throw new IllegalArgumentException("Missing RAW CFA");
        in.cfa=cfa; in.rotation=image.getImageInfo().getRotationDegrees();
        Integer white=result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if(white==null) white=info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if(white==null) throw new IllegalArgumentException("Missing RAW white level");
        in.white=white;
        in.black=result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if(in.black==null) {
            BlackLevelPattern pattern=info.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            if(pattern==null) throw new IllegalArgumentException("Missing RAW black levels");
            in.black=new float[4];
            for(int y=0;y<2;y++) for(int x=0;x<2;x++) in.black[y*2+x]=pattern.getOffsetForIndex(x,y);
        }
        RggbChannelVector wb=result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        ColorSpaceTransform matrix=result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if(wb==null||matrix==null) throw new IllegalArgumentException("Missing RAW white balance or color transform");
        in.gains=new float[]{wb.getRed(),wb.getGreenEven(),wb.getGreenOdd(),wb.getBlue()};
        in.matrix=new float[9];
        for(int y=0;y<3;y++) for(int x=0;x<3;x++) in.matrix[y*3+x]=matrix.getElement(x,y).floatValue();
        // ColorSpaceTransform.getElement(column,row), stored row-major above.
        LensShadingMap shading=result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if(shading==null) throw new IllegalArgumentException("Missing RAW lens shading map");
        in.shadingWidth=shading.getColumnCount(); in.shadingHeight=shading.getRowCount();
        in.shading=new float[in.shadingWidth*in.shadingHeight*4];
        shading.copyGainFactors(in.shading,0);
        Integer boost=result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        if(boost!=null) in.exposureBoost=boost/100f;
        return in;
    }
}
