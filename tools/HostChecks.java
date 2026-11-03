import com.example.cinemacamera.FrameGeometry;
import com.example.cinemacamera.GrainTile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class HostChecks {
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(FrameGeometry.fit(3000,4000,1080,1920),new int[]{0,240,1080,1440}),"portrait fit");
        check(Arrays.equals(FrameGeometry.fit(4000,3000,1080,1920),new int[]{0,555,1080,810}),"landscape fit");
        for(int w : new int[]{1,480,640,1920,2560}) {
            for(int h : new int[]{1,480,640,1920,2560}) {
                int[] rect = FrameGeometry.fit(w,h,731,1103);
                check(rect[0]>=0 && rect[1]>=0 && rect[2]>0 && rect[3]>0,"positive viewport");
                check(rect[0]+rect[2]<=731 && rect[1]+rect[3]<=1103,"viewport containment");
            }
        }
        boolean rejected = false;
        try { FrameGeometry.fit(0,100,100,100); } catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"invalid dimensions rejected");
        ByteBuffer grain = GrainTile.create();
        byte[] bytes = new byte[grain.remaining()]; grain.get(bytes);
        ByteBuffer repeated = GrainTile.create();
        byte[] bytes2 = new byte[repeated.remaining()]; repeated.get(bytes2);
        check(Arrays.equals(bytes,bytes2),"repeatable grain");
        double mean=0;
        for(int i=0;i<bytes.length;i+=4) {
            check(bytes[i]==bytes[i+1] && bytes[i]==bytes[i+2],"luminance-only grain");
            check((bytes[i+3]&255)==255,"opaque grain texture");
            mean+=bytes[i]&255;
        }
        mean/=(GrainTile.SIZE*GrainTile.SIZE);
        check(Math.abs(mean-127.5)<1.0,"grain must not introduce a brightness bias");
        Files.write(Path.of(args[0]),bytes);
        System.out.printf("PASS geometry, image bounds, grain determinism / RGB neutrality; grain mean %.3f%n",mean);
    }
}
