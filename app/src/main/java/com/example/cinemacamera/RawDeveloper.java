package com.example.cinemacamera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Initial RAW16 Bayer developer. Each 2x2 Bayer cell yields one RGB pixel,
 * averaging its two corrected greens. This intentionally limits resolution;
 * it is not an edge-aware full-resolution demosaicer or a mature noise pipeline.
 * No 8-bit image is created here.
 */
public final class RawDeveloper {
    private static final int[][] CHANNELS={{0,1,2,3},{1,0,3,2},{1,3,0,2},{3,1,2,0}};
    public static final class Input {
        public ByteBuffer bytes;
        public int width,height,rowStride,pixelStride;
        public int left,top,cropWidth,cropHeight,originX,originY,cfa,rotation;
        // cfa uses Android's RGGB=0, GRBG=1, GBRG=2, BGGR=3.
        public float white;
        public float[] black; // 2x2 sensor positions, row-major
        public float[] gains; // R, green-even-row, green-odd-row, B
        public float[] matrix; // WB-corrected camera RGB -> linear sRGB, row-major
        public float[] shading; // interleaved R, Ge, Go, B
        public int shadingWidth,shadingHeight;
        public float exposureBoost=1f;
    }
    private RawDeveloper() {}

    public static int channel(int cfa,int x,int y) {
        int phase=((y&1)<<1)|(x&1);
        if(cfa<0||cfa>3) throw new IllegalArgumentException("Unsupported CFA: "+cfa);
        return CHANNELS[cfa][phase];
    }

    public static LinearPhoto develop(Input in,int maxEdge) {
        validate(in,maxEdge);
        ByteBuffer data=in.bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base=data.position();
        int nativeWidth=in.cropWidth/2,nativeHeight=in.cropHeight/2;
        float scale=Math.min(1f,(float)maxEdge/Math.max(nativeWidth,nativeHeight));
        int width=Math.max(1,(int)(nativeWidth*scale)),height=Math.max(1,(int)(nativeHeight*scale));
        boolean turn=in.rotation==90||in.rotation==270;
        int outputWidth=turn?height:width,outputHeight=turn?width:height;
        FloatBuffer output=ByteBuffer.allocateDirect(outputWidth*outputHeight*16)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        long deadline=System.nanoTime()+12_000_000_000L;
        for(int oy=0;oy<height;oy++) {
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)
                throw new IllegalStateException("RAW development timed out");
            int sy=in.top+2*(int)((long)oy*nativeHeight/height);
            for(int ox=0;ox<width;ox++) {
                int sx=in.left+2*(int)((long)ox*nativeWidth/width);
                float r=0,g=0,b=0;
                for(int dy=0;dy<2;dy++) for(int dx=0;dx<2;dx++) {
                    int x=sx+dx,y=sy+dy;
                    int sensorX=x+in.originX,sensorY=y+in.originY;
                    int channel=channel(in.cfa,sensorX,sensorY);
                    int phase=((sensorY&1)<<1)|(sensorX&1);
                    float black=in.black[phase];
                    int code=data.getShort(base+y*in.rowStride+x*in.pixelStride)&0xffff;
                    float value=Math.max(0f,Math.min(1f,(code-black)/(in.white-black)));
                    value*=in.gains[channel]*shade(in,channel,x,y)*in.exposureBoost;
                    if(channel==0) r=value;
                    else if(channel==3) b=value;
                    else g+=value*.5f;
                }
                float red=in.matrix[0]*r+in.matrix[1]*g+in.matrix[2]*b;
                float green=in.matrix[3]*r+in.matrix[4]*g+in.matrix[5]*b;
                float blue=in.matrix[6]*r+in.matrix[7]*g+in.matrix[8]*b;
                int x=ox,y=oy;
                switch(in.rotation) {
                    case 90: x=height-1-oy; y=ox; break;
                    case 180: x=width-1-ox; y=height-1-oy; break;
                    case 270: x=oy; y=width-1-ox; break;
                }
                int index=(y*outputWidth+x)*4;
                output.put(index,Math.max(0,red)); output.put(index+1,Math.max(0,green));
                output.put(index+2,Math.max(0,blue)); output.put(index+3,1f);
            }
        }
        output.position(0);
        return new LinearPhoto(outputWidth,outputHeight,output);
    }

    private static float shade(Input in,int channel,int x,int y) {
        if(in.shading==null) return 1f;
        float px=(float)(x-in.left)/Math.max(1,in.cropWidth-1)*(in.shadingWidth-1);
        float py=(float)(y-in.top)/Math.max(1,in.cropHeight-1)*(in.shadingHeight-1);
        int x0=(int)px,y0=(int)py,x1=Math.min(x0+1,in.shadingWidth-1),y1=Math.min(y0+1,in.shadingHeight-1);
        float tx=px-x0,ty=py-y0;
        float a=in.shading[(y0*in.shadingWidth+x0)*4+channel];
        float b=in.shading[(y0*in.shadingWidth+x1)*4+channel];
        float c=in.shading[(y1*in.shadingWidth+x0)*4+channel];
        float d=in.shading[(y1*in.shadingWidth+x1)*4+channel];
        return (a+(b-a)*tx)*(1-ty)+(c+(d-c)*tx)*ty;
    }

    private static void validate(Input in,int maxEdge) {
        if(in.bytes==null||in.width<2||in.height<2||in.pixelStride<2
                ||in.rowStride<(long)in.width*in.pixelStride||maxEdge<1||maxEdge>4096)
            throw new IllegalArgumentException("Invalid RAW layout");
        if(in.left<0||in.top<0||in.cropWidth<2||in.cropHeight<2
                ||in.left+in.cropWidth>in.width||in.top+in.cropHeight>in.height)
            throw new IllegalArgumentException("Invalid RAW active area");
        long required=(long)(in.height-1)*in.rowStride+(long)(in.width-1)*in.pixelStride+2;
        if(required>in.bytes.remaining()) throw new IllegalArgumentException("Truncated RAW buffer");
        if(in.cfa<0||in.cfa>3||in.rotation<0||in.rotation>=360||in.rotation%90!=0)
            throw new IllegalArgumentException("Unsupported RAW mosaic or orientation");
        if(in.black==null||in.black.length!=4||in.gains==null||in.gains.length!=4
                ||in.matrix==null||in.matrix.length!=9||!Float.isFinite(in.white)||in.white<=0)
            throw new IllegalArgumentException("Incomplete RAW color metadata");
        for(float v:in.black) if(!Float.isFinite(v)||v<0||v>=in.white)
            throw new IllegalArgumentException("Invalid RAW black levels");
        for(float v:in.gains) if(!Float.isFinite(v)||v<=0||v>32)
            throw new IllegalArgumentException("Invalid RAW WB gains");
        for(float v:in.matrix) if(!Float.isFinite(v)||Math.abs(v)>16)
            throw new IllegalArgumentException("Invalid RAW color matrix");
        float[] m=in.matrix;
        float determinant=m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
        if(Math.abs(determinant)<0.00001f) throw new IllegalArgumentException("Singular RAW color matrix");
        if(!Float.isFinite(in.exposureBoost)||in.exposureBoost<=0||in.exposureBoost>16)
            throw new IllegalArgumentException("Invalid RAW exposure boost");
        if(in.shading!=null) {
            if(in.shadingWidth<1||in.shadingHeight<1
                    ||in.shading.length!=(long)in.shadingWidth*in.shadingHeight*4)
                throw new IllegalArgumentException("Invalid RAW shading map");
            for(float v:in.shading) if(!Float.isFinite(v)||v<0.1||v>16)
                throw new IllegalArgumentException("Invalid RAW shading factor");
        }
    }
}
