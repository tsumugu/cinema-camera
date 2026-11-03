import com.example.cinemacamera.LinearPhoto;
import com.example.cinemacamera.RawDeveloper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RawChecks {
    private static final int[][] PHASE_CHANNELS={{0,1,2,3},{1,0,3,2},{1,3,0,2},{3,1,2,0}};
    private static void near(float value,float expected,String message) {
        if(Math.abs(value-expected)>.001f) throw new AssertionError(message+": "+value+" != "+expected);
    }
    private static RawDeveloper.Input input(int cfa) {
        RawDeveloper.Input in=new RawDeveloper.Input();
        in.width=4; in.height=4; in.pixelStride=4; in.rowStride=24;
        in.cropWidth=4; in.cropHeight=4; in.cfa=cfa; in.white=4064;
        in.black=new float[]{64,72,80,88};
        in.gains=new float[]{2,1,1,3};
        in.matrix=new float[]{1.1f,-.1f,0, .05f,.95f,0, 0,.2f,.8f};
        ByteBuffer buffer=ByteBuffer.allocate(16+in.rowStride*in.height).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(16);
        float[] sensorValues={.2f,.3f,.5f,.1f};
        for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
            int phase=(y%2)*2+x%2;
            float value=sensorValues[PHASE_CHANNELS[cfa][phase]];
            int code=Math.round(in.black[phase]+value*(in.white-in.black[phase]));
            buffer.putShort(16+y*in.rowStride+x*in.pixelStride,(short)code);
        }
        in.bytes=buffer;
        return in;
    }
    public static void main(String[] args) {
        for(int cfa=0;cfa<4;cfa++) {
            LinearPhoto photo=RawDeveloper.develop(input(cfa),2560);
            if(photo.width!=2||photo.height!=2) throw new AssertionError("Bayer superpixel dimensions");
            for(int i=0;i<4;i++) {
                near(photo.rgba.get(i*4),.4f,"black/WB/matrix red, CFA "+cfa);
                near(photo.rgba.get(i*4+1),.4f,"two-green average, CFA "+cfa);
                near(photo.rgba.get(i*4+2),.32f,"matrix blue, CFA "+cfa);
            }
        }
        RawDeveloper.Input shading=input(0);
        shading.shadingWidth=2; shading.shadingHeight=2;
        shading.shading=new float[16]; java.util.Arrays.fill(shading.shading,2f);
        near(RawDeveloper.develop(shading,2560).rgba.get(0),.8f,"lens shading applied before color matrix");
        RawDeveloper.Input rotation=new RawDeveloper.Input();
        rotation.width=6; rotation.height=4; rotation.rowStride=12; rotation.pixelStride=2;
        rotation.cropWidth=6; rotation.cropHeight=4; rotation.cfa=0; rotation.rotation=90;
        rotation.black=new float[]{64,64,64,64}; rotation.white=4064;
        rotation.gains=new float[]{1,1,1,1}; rotation.matrix=new float[]{1,0,0,0,1,0,0,0,1};
        rotation.bytes=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=0;y<4;y++) for(int x=0;x<6;x++) {
            int cell=y/2*3+x/2;
            rotation.bytes.putShort(y*12+x*2,(short)(64+400*(cell+1)));
        }
        LinearPhoto turned=RawDeveloper.develop(rotation,2560);
        if(turned.width!=2||turned.height!=3) throw new AssertionError("RAW rotation dimensions");
        float[] expected={.4f,.1f,.5f,.2f,.6f,.3f};
        for(int i=0;i<6;i++) near(turned.rgba.get(i*4),expected[i],"clockwise orientation");
        rotation.rotation=0;
        rotation.bytes.putShort(0,(short)65); rotation.bytes.putShort(4,(short)66);
        LinearPhoto precision=RawDeveloper.develop(rotation,2560);
        float delta=precision.rgba.get(4)-precision.rgba.get(0);
        if(Math.abs(delta-.00025f)>.00001f) throw new AssertionError("RAW precision lost before GPU tone mapping");
        RawDeveloper.Input truncated=input(0); truncated.bytes.limit(30);
        rejects(truncated,"truncated RAW");
        RawDeveloper.Input missing=input(0); missing.matrix=null; rejects(missing,"missing color metadata");
        RawDeveloper.Input unsupported=input(0); unsupported.cfa=4; rejects(unsupported,"non-Bayer CFA");
        RawDeveloper.Input invalid=input(0); invalid.gains[0]=Float.NaN; rejects(invalid,"nonfinite gains");
        System.out.println("PASS RAW16 padded rows/pixel stride/buffer offset, four Bayer layouts, black/WB/color matrix, shading, rotation, float precision and invalid-input rejection");
    }
    private static void rejects(RawDeveloper.Input input,String message) {
        try { RawDeveloper.develop(input,2560); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Must reject "+message);
    }
}
