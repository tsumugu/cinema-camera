package com.example.cinemacamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/** Single processing-thread renderer, independent of the Activity's preview surface. */
final class PhotoRenderer {
    private final Context context;
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext=EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface=EGL14.EGL_NO_SURFACE;
    private int program,grain;
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
    PhotoRenderer(Context context) {
        this.context=context;
        vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
    }
    Bitmap render(Bitmap source,LookSettings settings) {
        try { return render(source,null,settings); }
        finally { source.recycle(); }
    }
    Bitmap render(LinearPhoto source,LookSettings settings) { return render(null,source,settings); }
    private Bitmap render(Bitmap bitmap,LinearPhoto linear,LookSettings settings) {
        int input=0,output=0,fbo=0;
        try {
            initialize();
            if(!EGL14.eglMakeCurrent(display,surface,surface,eglContext)) throw new IllegalStateException("Cannot use photo EGL context");
            int width=linear==null?bitmap.getWidth():linear.width,height=linear==null?bitmap.getHeight():linear.height;
            int[] max=new int[1]; GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,max,0);
            if(width>max[0]||height>max[0]) throw new IllegalStateException("Photo exceeds GPU texture size");
            input=texture(GLES20.GL_CLAMP_TO_EDGE);
            if(linear==null) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);
            else {
                String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
                if(extensions==null||!extensions.contains("GL_OES_texture_float")) throw new IllegalStateException("No float RAW textures");
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
                linear.rgba.position(0);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_FLOAT,linear.rgba);
            }
            output=texture(GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            int[] ids=new int[1]; GLES20.glGenFramebuffers(1,ids,0); fbo=ids[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,output,0);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Cannot create photo framebuffer");
            GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,input);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uImage"),0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,grain);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uGrain"),1);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uImageSize"),width,height);
            settings.apply(program);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uCompare"),0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uLinearInput"),linear==null?0:1);
            int attribute=GLES20.glGetAttribLocation(program,"aPosition"); vertices.position(0);
            GLES20.glEnableVertexAttribArray(attribute);
            GLES20.glVertexAttribPointer(attribute,2,GLES20.GL_FLOAT,false,0,vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); GLES20.glDisableVertexAttribArray(attribute);
            ByteBuffer rgba=ByteBuffer.allocateDirect(width*height*4);
            GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba); check();
            int[] pixels=new int[width*height];
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                int i=((height-1-y)*width+x)*4;
                pixels[y*width+x]=0xff000000|((rgba.get(i)&255)<<16)|((rgba.get(i+1)&255)<<8)|(rgba.get(i+2)&255);
            }
            return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        } finally {
            if(eglContext!=EGL14.EGL_NO_CONTEXT) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
                if(fbo!=0) GLES20.glDeleteFramebuffers(1,new int[]{fbo},0);
                if(input!=0) GLES20.glDeleteTextures(1,new int[]{input},0);
                if(output!=0) GLES20.glDeleteTextures(1,new int[]{output},0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            }
        }
    }
    private void initialize() {
        if(program!=0) return;
        try {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions=new int[2];
            if(!EGL14.eglInitialize(display,versions,0,versions,1)) throw new IllegalStateException("Photo EGL initialization failed");
            EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
            int[] attributes={EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
            if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)||count[0]==0)
                throw new IllegalStateException("No photo EGL config");
            eglContext=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if(eglContext==EGL14.EGL_NO_CONTEXT||surface==EGL14.EGL_NO_SURFACE
                    ||!EGL14.eglMakeCurrent(display,surface,surface,eglContext)) throw new IllegalStateException("Photo EGL context unavailable");
            int vertex=compile(GLES20.GL_VERTEX_SHADER,"look.vert"),fragment=compile(GLES20.GL_FRAGMENT_SHADER,"look.frag");
            program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment);
            GLES20.glLinkProgram(program); int[] linked=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
            if(linked[0]==0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            grain=texture(GLES20.GL_REPEAT);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,GrainTile.SIZE,GrainTile.SIZE,0,
                GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,GrainTile.create()); check();
        } catch(Exception error) { close(); throw new IllegalStateException("Photo renderer unavailable",error); }
    }
    private int compile(int kind,String asset) throws Exception {
        String source;
        try(java.io.InputStream stream=context.getAssets().open(asset);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n;
            while((n=stream.read(buffer))!=-1) bytes.write(buffer,0,n);
            source=bytes.toString(StandardCharsets.UTF_8.name());
        }
        int shader=GLES20.glCreateShader(kind); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] compiled=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);
        if(compiled[0]==0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
    private int texture(int wrap) {
        int[] names=new int[1]; GLES20.glGenTextures(1,names,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,names[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,wrap); return names[0];
    }
    private void check() {
        int error=GLES20.glGetError();
        if(error!=GLES20.GL_NO_ERROR) throw new IllegalStateException("Photo GL error: "+error);
    }
    void close() {
        if(display!=EGL14.EGL_NO_DISPLAY) {
            if(eglContext!=EGL14.EGL_NO_CONTEXT&&surface!=EGL14.EGL_NO_SURFACE
                    &&EGL14.eglMakeCurrent(display,surface,surface,eglContext)) {
                if(program!=0) GLES20.glDeleteProgram(program);
                if(grain!=0) GLES20.glDeleteTextures(1,new int[]{grain},0);
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface);
            if(eglContext!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,eglContext);
            EGL14.eglTerminate(display);
        }
        display=EGL14.EGL_NO_DISPLAY; eglContext=EGL14.EGL_NO_CONTEXT; surface=EGL14.EGL_NO_SURFACE; program=0; grain=0;
    }
}
