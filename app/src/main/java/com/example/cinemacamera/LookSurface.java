package com.example.cinemacamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** One GPU shader for both live display and saved photos. All GL work is serialized. */
public final class LookSurface extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final AtomicReference<PreviewFrame> pendingFrame = new AtomicReference<>();
    private volatile PreviewFrameInfo previewInfo;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final Consumer<Throwable> onError;
    private final Runnable onFirstFrame;
    private int program, imageTexture, grainTexture, imageWidth, imageHeight, viewWidth, viewHeight;
    private boolean hasFrame, announcedFrame;
    private volatile boolean compare;
    private volatile LookSettings lookSettings=LookSettings.defaults();
    private volatile boolean acceptingFrames = true;

    public LookSurface(Context context, Runnable onFirstFrame, Consumer<Throwable> onError) {
        super(context);
        this.onFirstFrame = onFirstFrame;
        this.onError = onError;
        vertices.put(new float[]{-1,-1, 1,-1, -1,1, 1,1}).position(0);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8,8,8,8,0,0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Takes ownership; replaces stale frames instead of building up camera latency. */
    public void offerFrame(Bitmap bitmap, PreviewFrameInfo info) {
        if (!acceptingFrames) { bitmap.recycle(); return; }
        PreviewFrame stale = pendingFrame.getAndSet(new PreviewFrame(bitmap,info));
        if (stale != null) stale.bitmap.recycle();
        requestRender();
    }

    public void setLookSettings(LookSettings settings) { lookSettings=settings; requestRender(); }

    public PreviewFrameInfo getPreviewInfo() { return previewInfo; }

    private static final class PreviewFrame {
        final Bitmap bitmap;
        final PreviewFrameInfo info;
        PreviewFrame(Bitmap bitmap,PreviewFrameInfo info) { this.bitmap=bitmap; this.info=info; }
    }

    public void setCompare(boolean enabled) { compare = enabled; requestRender(); }

    public void stopFrames() {
        acceptingFrames = false;
        PreviewFrame stale = pendingFrame.getAndSet(null);
        if (stale != null) stale.bitmap.recycle();
    }

    public void startFrames() {
        acceptingFrames = true;
        queueEvent(() -> { hasFrame = false; announcedFrame = false; previewInfo=null; });
    }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        hasFrame = false;
        previewInfo = null;
        announcedFrame = false;
        program = 0;
        try {
            program = GLES20.glCreateProgram();
            int vertex = compile(GLES20.GL_VERTEX_SHADER, read("look.vert"));
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, read("look.frag"));
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            imageTexture = texture(GLES20.GL_CLAMP_TO_EDGE);
            grainTexture = texture(GLES20.GL_REPEAT);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                GrainTile.SIZE, GrainTile.SIZE, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, GrainTile.create());
            check("initialize renderer");
        } catch (Throwable e) { program = 0; fail(e); }
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewWidth = width; viewHeight = height;
    }

    @Override public void onDrawFrame(GL10 unused) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glClearColor(0.035f,0.04f,0.035f,1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        PreviewFrame next = pendingFrame.getAndSet(null);
        if (program == 0) { if(next != null) next.bitmap.recycle(); return; }
        try {
            if (next != null) {
                imageWidth = next.bitmap.getWidth(); imageHeight = next.bitmap.getHeight();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, next.bitmap, 0);
                check("upload preview");
                previewInfo=next.info;
                hasFrame = true;
            }
            if (hasFrame && viewWidth > 0 && viewHeight > 0) {
                int[] viewport = FrameGeometry.fit(imageWidth, imageHeight, viewWidth, viewHeight);
                GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
                draw(imageTexture, imageWidth, imageHeight, compare);
                if (!announcedFrame) {
                    announcedFrame = true;
                    main.post(onFirstFrame);
                }
            }
        } catch (Throwable e) { fail(e); }
        finally { if(next != null) next.bitmap.recycle(); }
    }

    /** Takes ownership of source. Saves always use the full look, even during comparison. */
    public void renderPhoto(Bitmap source, Consumer<Bitmap> success, Consumer<Throwable> failure) {
        renderPhoto(source,null,success,failure);
    }

    public void renderLinearPhoto(LinearPhoto source, Consumer<Bitmap> success, Consumer<Throwable> failure) {
        renderPhoto(null,source,success,failure);
    }

    private void renderPhoto(Bitmap source, LinearPhoto linear, Consumer<Bitmap> success, Consumer<Throwable> failure) {
        queueEvent(() -> {
            int input = 0, output = 0, framebuffer = 0;
            try {
                if(program == 0) throw new IllegalStateException("Renderer is not ready");
                int width = linear == null ? source.getWidth() : linear.width;
                int height = linear == null ? source.getHeight() : linear.height;
                int[] max = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0);
                if (width > max[0] || height > max[0]) throw new IllegalStateException("Photo exceeds GPU texture limit");
                input = texture(GLES20.GL_CLAMP_TO_EDGE);
                if(linear == null) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,source,0);
                else {
                    String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
                    if(extensions == null || !extensions.contains("GL_OES_texture_float"))
                        throw new IllegalStateException("Floating-point RAW textures are unavailable");
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
                    linear.rgba.position(0);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,
                        GLES20.GL_RGBA,GLES20.GL_FLOAT,linear.rgba);
                }
                output = texture(GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,
                    GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
                int[] ids = new int[1];
                GLES20.glGenFramebuffers(1,ids,0); framebuffer = ids[0];
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,output,0);
                if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Cannot create photo framebuffer");
                GLES20.glViewport(0,0,width,height);
                draw(input,width,height,false,linear != null);
                ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
                GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);
                check("render photo");
                int[] pixels = new int[width * height];
                for(int y = 0; y < height; y++) {
                    int from = (height - 1 - y) * width * 4;
                    for(int x = 0; x < width; x++) {
                        int i = from + x * 4;
                        pixels[y * width + x] = 0xff000000 | ((rgba.get(i)&255)<<16)
                            | ((rgba.get(i+1)&255)<<8) | (rgba.get(i+2)&255);
                    }
                }
                Bitmap result = Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
                // Consumer only schedules IO; never hold the GL thread for JPEG compression.
                try { success.accept(result); }
                catch(Throwable error) { result.recycle(); throw error; }
            } catch(Throwable e) { main.post(() -> failure.accept(e)); }
            finally {
                if(source != null) source.recycle();
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
                if(framebuffer != 0) GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0);
                if(input != 0) GLES20.glDeleteTextures(1,new int[]{input},0);
                if(output != 0) GLES20.glDeleteTextures(1,new int[]{output},0);
                requestRender();
            }
        });
    }

    private void draw(int texture, int width, int height, boolean original) {
        draw(texture,width,height,original,false);
    }

    private void draw(int texture, int width, int height, boolean original, boolean linear) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uImage"),0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,grainTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uGrain"),1);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uImageSize"),width,height);
        lookSettings.apply(program);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uCompare"),original ? 1f : 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uLinearInput"),linear ? 1f : 0f);
        int attribute = GLES20.glGetAttribLocation(program,"aPosition");
        vertices.position(0);
        GLES20.glEnableVertexAttribArray(attribute);
        GLES20.glVertexAttribPointer(attribute,2,GLES20.GL_FLOAT,false,0,vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(attribute);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        check("draw");
    }

    private int texture(int wrap) {
        int[] id = new int[1]; GLES20.glGenTextures(1,id,0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,wrap);
        return id[0];
    }

    private int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);
        if(compiled[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private String read(String name) throws Exception {
        try(InputStream stream = getContext().getAssets().open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while((length = stream.read(buffer)) != -1) bytes.write(buffer,0,length);
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private void check(String stage) {
        int error = GLES20.glGetError();
        if(error != GLES20.GL_NO_ERROR) throw new IllegalStateException(stage + ": GL " + error);
    }
    private void fail(Throwable error) { main.post(() -> onError.accept(error)); }
}
