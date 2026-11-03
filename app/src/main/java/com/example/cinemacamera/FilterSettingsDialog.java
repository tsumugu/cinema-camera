package com.example.cinemacamera;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.Consumer;

/** Bottom sheet with live preview; only Save commits the draft to preferences. */
final class FilterSettingsDialog extends Dialog {
    private final Context context;
    private final LookSettings original;
    private final Consumer<LookSettings> preview,save;
    private final SeekBar[] bars=new SeekBar[5];
    private final TextView[] labels=new TextView[5];
    private final String[] names={"全体の強さ","青み","彩度","コントラスト","粒子"};
    private boolean binding,saved;
    private LookSettings draft;

    FilterSettingsDialog(Context context,LookSettings original,Consumer<LookSettings> preview,Consumer<LookSettings> save) {
        super(context); this.context=context; this.original=original; this.preview=preview; this.save=save; draft=original;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(16),dp(20),dp(16));
        GradientDrawable background=new GradientDrawable(); background.setColor(0xff242721);
        background.setCornerRadii(new float[]{dp(18),dp(18),dp(18),dp(18),0,0,0,0}); root.setBackground(background);
        TextView title=text("フィルター調整",18); root.addView(title);
        TextView hint=text("プレビューを見ながら調整し、保存してください",12);
        hint.setTextColor(0xffb4baad); hint.setPadding(0,dp(8),0,dp(12)); root.addView(hint);
        ScrollView scroll=new ScrollView(context); scroll.setFillViewport(false);
        LinearLayout controls=new LinearLayout(context); controls.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(controls); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<bars.length;i++) {
            labels[i]=text("",13); controls.addView(labels[i]);
            SeekBar bar=new SeekBar(context); bars[i]=bar; bar.setMax(i==2?150:100);
            bar.setContentDescription(names[i]);
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,dp(44)); size.bottomMargin=dp(6);
            controls.addView(bar,size);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seek,int value,boolean fromUser) {
                    if(binding||!fromUser) return;
                    draft=new LookSettings(bars[0].getProgress(),bars[1].getProgress(),bars[2].getProgress(),
                        bars[3].getProgress(),bars[4].getProgress());
                    updateLabels(); preview.accept(draft);
                }
                @Override public void onStartTrackingTouch(SeekBar seek) {}
                @Override public void onStopTrackingTouch(SeekBar seek) {}
            });
        }
        LinearLayout buttons=new LinearLayout(context); buttons.setGravity(Gravity.CENTER);
        Button reset=button("初期値"),cancel=button("キャンセル"),commit=button("保存");
        for(Button button:new Button[]{reset,cancel,commit}) buttons.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));
        commit.setTextColor(0xffebc964);
        reset.setOnClickListener(view -> { setValues(LookSettings.defaults()); preview.accept(draft); });
        cancel.setOnClickListener(view -> dismiss());
        commit.setOnClickListener(view -> { save.accept(draft); saved=true; dismiss(); });
        root.addView(buttons); setContentView(root); setValues(original);
        setOnDismissListener(dialog -> { if(!saved) preview.accept(this.original); });
    }
    @Override public void show() {
        super.show();
        Window window=getWindow();
        if(window!=null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            int height=Math.min(dp(510),Math.round(context.getResources().getDisplayMetrics().heightPixels*.65f));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,height);
            WindowManager.LayoutParams attributes=window.getAttributes(); attributes.dimAmount=0f;
            window.setAttributes(attributes);
        }
    }
    private void setValues(LookSettings values) {
        draft=values; binding=true;
        int[] positions={values.strength,values.cool,values.saturation,values.contrast,values.grain};
        for(int i=0;i<bars.length;i++) bars[i].setProgress(positions[i]);
        binding=false; updateLabels();
    }
    private void updateLabels() {
        for(int i=0;i<bars.length;i++) labels[i].setText(names[i]+"  "+bars[i].getProgress()+"%");
    }
    private TextView text(String value,int size) {
        TextView text=new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(0xfff0f1e9); return text;
    }
    private Button button(String name) {
        Button button=new Button(context); button.setText(name); button.setTextSize(12); button.setAllCaps(false);
        button.setTextColor(Color.WHITE); button.setBackgroundColor(Color.TRANSPARENT); return button;
    }
    private int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
}
