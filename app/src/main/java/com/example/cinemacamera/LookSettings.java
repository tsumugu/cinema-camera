package com.example.cinemacamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES20;

/** Immutable settings shared by preview and each individual shutter request. */
final class LookSettings {
    final int strength,cool,saturation,contrast,grain;
    LookSettings(int strength,int cool,int saturation,int contrast,int grain) {
        this.strength=clamp(strength,100); this.cool=clamp(cool,100);
        this.saturation=clamp(saturation,150); this.contrast=clamp(contrast,100); this.grain=clamp(grain,100);
    }
    static LookSettings defaults() { return new LookSettings(100,45,86,100,100); }
    static LookSettings load(Context context) {
        SharedPreferences p=context.getSharedPreferences("filter-settings",Context.MODE_PRIVATE);
        LookSettings d=defaults();
        return new LookSettings(p.getInt("strength",d.strength),p.getInt("cool",d.cool),
            p.getInt("saturation",d.saturation),p.getInt("contrast",d.contrast),p.getInt("grain",d.grain));
    }
    void save(Context context) {
        context.getSharedPreferences("filter-settings",Context.MODE_PRIVATE).edit()
            .putInt("strength",strength).putInt("cool",cool).putInt("saturation",saturation)
            .putInt("contrast",contrast).putInt("grain",grain).apply();
    }
    void apply(int program) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uStrength"),strength/100f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uCool"),cool/100f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uSaturation"),saturation/100f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uContrast"),.74f*contrast/100f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uGrainAmount"),.012f*grain/100f);
    }
    private static int clamp(int value,int max) { return Math.max(0,Math.min(max,value)); }
}
