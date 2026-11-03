package com.example.cinemacamera;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.Consumer;

final class ManualSettingsDialog extends Dialog {
    private final ManualSettings original;
    private final ManualSettings.Limits limits;
    private final Consumer<ManualSettings> preview,save;
    private final CheckBox exposure,focus;
    private final SeekBar shutter,iso,distance;
    private final TextView shutterLabel,isoLabel,focusLabel;
    private boolean binding,committed;
    ManualSettingsDialog(Context context,ManualSettings initial,ManualSettings.Limits limits,Consumer<ManualSettings> preview,Consumer<ManualSettings> save){
        super(context);original=initial;this.limits=limits;this.preview=preview;this.save=save;requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,16);root.setBackgroundColor(0xff242721);
        TextView title=label(context,"撮影設定",18);root.addView(title);
        ScrollView scroll=new ScrollView(context);LinearLayout controls=new LinearLayout(context);controls.setOrientation(LinearLayout.VERTICAL);scroll.addView(controls);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        exposure=new CheckBox(context);exposure.setText(limits.exposure?"M：シャッター速度・ISOを指定":"マニュアル露出：このカメラは非対応");exposure.setTextColor(0xffefefe6);exposure.setEnabled(limits.exposure);controls.addView(exposure);
        shutterLabel=label(context,"",14);controls.addView(shutterLabel);shutter=bar(context);controls.addView(shutter);
        isoLabel=label(context,"",14);controls.addView(isoLabel);iso=bar(context);controls.addView(iso);
        focus=new CheckBox(context);focus.setText(limits.focus?"MF：ピントを手動で合わせる":"マニュアルフォーカス：非対応");focus.setTextColor(0xffefefe6);focus.setEnabled(limits.focus);controls.addView(focus);
        focusLabel=label(context,"",14);controls.addView(focusLabel);distance=bar(context);controls.addView(distance);
        TextView hint=label(context,"AUTOではタップ測光と上下ドラッグの露出補正を使えます。長秒露光ではプレビューの更新も遅くなります。",12);controls.addView(hint);
        LinearLayout buttons=new LinearLayout(context);Button auto=new Button(context),cancel=new Button(context),apply=new Button(context);auto.setText("AUTO");cancel.setText("キャンセル");apply.setText("適用");
        for(Button b:new Button[]{auto,cancel,apply})buttons.addView(b,new LinearLayout.LayoutParams(0,56*context.getResources().getDisplayMetrics().densityDpi/160,1));root.addView(buttons);
        setContentView(root);setValues(initial);
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user&&!binding)changed();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};
        for(SeekBar b:new SeekBar[]{shutter,iso,distance})b.setOnSeekBarChangeListener(listener);
        exposure.setOnCheckedChangeListener((v,checked)->{if(!binding)changed();});focus.setOnCheckedChangeListener((v,checked)->{if(!binding)changed();});
        auto.setOnClickListener(v->{ManualSettings x=value();setValues(new ManualSettings(false,x.shutter,x.iso,false,x.distance));changed();});
        cancel.setOnClickListener(v->dismiss());apply.setOnClickListener(v->{committed=true;save.accept(value());dismiss();});
        setOnDismissListener(d->{if(!committed)preview.accept(original);});
    }
    private SeekBar bar(Context c){SeekBar b=new SeekBar(c);b.setMax(1000);b.setMinimumHeight((int)(48*c.getResources().getDisplayMetrics().density));return b;}
    private TextView label(Context c,String text,int size){TextView v=new TextView(c);v.setText(text);v.setTextColor(0xffefefe6);v.setTextSize(size);v.setPadding(0,10,0,4);return v;}
    private void setValues(ManualSettings x){binding=true;exposure.setChecked(x.exposure);focus.setChecked(x.focus);shutter.setProgress(position(x.shutter,limits.low,limits.high));iso.setProgress(position(x.iso,limits.isoLow,limits.isoHigh));distance.setProgress(limits.near>0?Math.round(x.distance/limits.near*1000):0);binding=false;labels();}
    private static int position(double v,double low,double high){return high>low?(int)Math.max(0,Math.min(1000,Math.round(Math.log(v/low)/Math.log(high/low)*1000))):0;}
    private static long number(SeekBar b,long low,long high){return Math.max(low,Math.min(high,Math.round(low*Math.exp(b.getProgress()/1000.0*Math.log((double)high/low)))));}
    private ManualSettings value(){return new ManualSettings(exposure.isChecked(),number(shutter,limits.low,limits.high),(int)number(iso,limits.isoLow,limits.isoHigh),focus.isChecked(),limits.near*distance.getProgress()/1000f);}
    private void labels(){ManualSettings x=value();shutter.setEnabled(x.exposure);iso.setEnabled(x.exposure);distance.setEnabled(x.focus);shutterLabel.setText("シャッター  "+(x.shutter<250_000_000L?"約 1/"+Math.round(1e9/x.shutter)+" 秒":String.format(Locale.JAPAN,"%.3f 秒",x.shutter/1e9)));isoLabel.setText("ISO  "+x.iso);focusLabel.setText("ピント  "+(distance.getProgress()==0?"∞（遠景）":Math.round(distance.getProgress()/10f)+"% 近景側"));}
    private void changed(){labels();preview.accept(value());}
    @Override public void show(){super.show();Window w=getWindow();if(w!=null){w.setGravity(Gravity.BOTTOM);w.setDimAmount(0);w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,(int)(getContext().getResources().getDisplayMetrics().heightPixels*.68f));}}
}
