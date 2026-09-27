package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** One cover page, retained on the right when unfolded. The left reveals a second page. */
final class HomeScene extends FrameLayout {
    interface Actions { void launch(AppCatalog.App app); void choose(int slot); void drawer(); void settings(); void note(); }
    private final Actions actions;
    final HomePage primary;
    final TodayPage today;
    final Wallpaper wallpaper;
    boolean inner, reverse;
    float amount;
    long contentRevision;
    String lastTickKey="";
    private float startX,startY;
    HomeScene(Context context, Actions actions) {
        super(context); this.actions=actions;
        if(android.os.Build.VERSION.SDK_INT>=36)setRequestedFrameRate(HomeActivity.FRAME_RATE); setClipChildren(true);
        wallpaper=new Wallpaper(context);addView(wallpaper);
        today=new TodayPage(context);addView(today);
        primary=new HomePage(context,true);addView(primary);
        setContentDescription(context.getString(R.string.home_description));
    }
    int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    void updateApps(List<AppCatalog.App> apps){primary.updateApps(apps);contentRevision++;}
    void tick(int battery,String note){
        String key=LocalDate.now()+" "+LocalTime.now().getHour()+":"+LocalTime.now().getMinute()+"/"+battery+"/"+note;
        if(key.equals(lastTickKey))return;lastTickKey=key;
        primary.tick();today.tick(battery,note);contentRevision++;
    }
    Bitmap captureForBlur(float scale){
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(getWidth()*scale)),Math.max(1,Math.round(getHeight()*scale)),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.scale(bitmap.getWidth()/(float)getWidth(),bitmap.getHeight()/(float)getHeight());
        wallpaper.draw(canvas);
        if(inner){canvas.save();canvas.translate(today.getLeft(),today.getTop());today.draw(canvas);canvas.restore();}
        canvas.save();canvas.translate(primary.getLeft(),primary.getTop());primary.draw(canvas);canvas.restore();return bitmap;
    }
    void setPhoto(Bitmap photo){wallpaper.photo=photo;wallpaper.invalidate();contentRevision++;}
    void setFold(boolean isInner,float effect) {setFold(isInner,effect,reverse);}
    void setFold(boolean isInner,float effect,boolean reversed) {
        if(inner!=isInner||reverse!=reversed){inner=isInner;reverse=reversed;requestLayout();wallpaper.inner=inner;wallpaper.reverse=reverse;wallpaper.invalidate();}
        amount=effect;
        today.setVisibility(inner?VISIBLE:GONE);
        // Both pages remain in place. The effect only defocuses this exact content.
        today.setAlpha(1);
    }
    @Override protected void onMeasure(int w,int h){
        int width=MeasureSpec.getSize(w),height=MeasureSpec.getSize(h);setMeasuredDimension(width,height);
        wallpaper.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        int pageWidth=inner?width/2:width;
        primary.measure(exact(pageWidth),exact(height));today.measure(exact(pageWidth),exact(height));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int w=r-l,h=b-t,pw=inner?w/2:w;wallpaper.layout(0,0,w,h);
        int homeX=inner&&!reverse?w-pw:0,otherX=inner&&reverse?pw:0;
        primary.layout(homeX,0,homeX+pw,h);today.layout(otherX,0,otherX+pw,h);
    }
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){startX=e.getX();startY=e.getY();}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&startY-e.getY()>dp(56)&&Math.abs(e.getX()-startX)<dp(80))return true;
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_UP&&startY-e.getY()>dp(56)) {actions.drawer();performClick();return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    static GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    TextView text(Context c,String value,int size,int color){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER);v.setFontFeatureSettings("tnum");return v;}
    final class HomePage extends ViewGroup {
        final TextView clock,date,clockCaption,dateCaption;
        final LinearLayout[] tiles=new LinearLayout[16];
        final ImageView[] icons=new ImageView[16];
        final TextView[] labels=new TextView[16];
        final TextView drawer;
        final boolean interactive;
        List<AppCatalog.App> apps=List.of();
        HomePage(Context c,boolean interactive){
            super(c);this.interactive=interactive;
            clock=text(c,"",34,0xfff9f5ed);clock.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
            date=text(c,"",38,0xff33465a);date.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
            clockCaption=text(c,c.getString(R.string.home_time),11,0xffdee9ef);dateCaption=text(c,"",11,0xff546070);
            clock.setBackground(round(0x3a14324c,dp(24)));date.setBackground(round(0xd9f8f0e2,dp(24)));
            addView(clock);addView(date);addView(clockCaption);addView(dateCaption);
            for(int i=0;i<16;i++){
                final int slot=i;LinearLayout tile=new LinearLayout(c);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);
                ImageView icon=new ImageView(c);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                TextView label=text(c,"",11,Color.WHITE);label.setMaxLines(1);label.setEllipsize(TextUtils.TruncateAt.END);label.setShadowLayer(dp(2),0,dp(1),0x66503d31);
                tile.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(23));lp.topMargin=dp(5);tile.addView(label,lp);
                icons[i]=icon;labels[i]=label;tiles[i]=tile;addView(tile);
                if(interactive){
                    tile.setFocusable(true);tile.setClickable(true);tile.setBackground(round(0x00000000,dp(18)));
                    tile.setOnClickListener(v->{if(apps.size()>slot&&apps.get(slot)!=null)actions.launch(apps.get(slot));else actions.choose(slot);});
                    tile.setOnLongClickListener(v->{actions.choose(slot);return true;});
                }
            }
            drawer=text(c,c.getString(R.string.home_all_apps),12,0xfff8f5ef);drawer.setBackground(round(0x481d334b,dp(24)));addView(drawer);
            if(interactive){drawer.setOnClickListener(v->actions.drawer());drawer.setFocusable(true);}
            else {setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);setEnabled(false);}
            tick();
        }
        void updateApps(List<AppCatalog.App> list){apps=list;for(int i=0;i<16;i++){
            AppCatalog.App app=i<list.size()?list.get(i):null;
            icons[i].setImageDrawable(app==null?null:app.icon().getConstantState()!=null?app.icon().getConstantState().newDrawable():app.icon());
            icons[i].setBackground(app==null?round(0x25ffffff,dp(16)):null);
            labels[i].setText(app==null?getContext().getString(R.string.home_add):app.label());tiles[i].setContentDescription(getContext().getString(R.string.home_icon_description,app==null?getContext().getString(R.string.home_add_app):app.label()));
        }}
        void tick(){clock.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm")));LocalDate d=LocalDate.now();date.setText(String.valueOf(d.getDayOfMonth()));dateCaption.setText(d.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(getResources().getConfiguration().getLocales().get(0),"MMMEEE"),getResources().getConfiguration().getLocales().get(0))));}
        @Override protected void onMeasure(int ws,int hs){
            int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);
            int pad=dp(18),gap=dp(12),cardW=(w-2*pad-gap)/2,cardH=Math.min(dp(160),(int)(h*.205f));
            clock.measure(exact(cardW),exact(cardH));date.measure(exact(cardW),exact(cardH));
            clockCaption.measure(exact(cardW),exact(dp(26)));dateCaption.measure(exact(cardW),exact(dp(26)));
            int cellW=(w-2*dp(12))/4,cellH=(int)(h*.108f);int icon=Math.max(dp(24),Math.min(dp(60),Math.min((int)(cellW*.66f),cellH-dp(33))));
            for(int i=0;i<16;i++){icons[i].getLayoutParams().width=icon;icons[i].getLayoutParams().height=icon;tiles[i].measure(exact(cellW),exact(cellH));}
            drawer.measure(exact(Math.min(w-dp(36),dp(210))),exact(dp(46)));
        }
        @Override protected void onLayout(boolean c,int l,int t,int r,int b){
            int w=r-l,h=b-t,pad=dp(18),cw=clock.getMeasuredWidth(),ch=clock.getMeasuredHeight();
            int top=(int)(h*.095f);clock.layout(pad,top,pad+cw,top+ch);date.layout(w-pad-cw,top,w-pad,top+ch);
            int captionTop=top+ch-dp(37);clockCaption.layout(pad,captionTop,pad+cw,captionTop+dp(26));dateCaption.layout(w-pad-cw,captionTop,w-pad,captionTop+dp(26));
            int gridTop=(int)(h*.36f),cellW=tiles[0].getMeasuredWidth(),cellH=tiles[0].getMeasuredHeight();
            for(int i=0;i<16;i++){int x=(w-cellW*4)/2+(i%4)*cellW,y=gridTop+(i/4)*cellH;tiles[i].layout(x,y,x+cellW,y+cellH);}
            int dw=drawer.getMeasuredWidth(),dh=drawer.getMeasuredHeight(),dy=Math.min(h-dp(80),(int)(h*.855f));drawer.layout((w-dw)/2,dy,(w+dw)/2,dy+dh);
        }
    }
    final class TodayPage extends ViewGroup {
        final TextView title,largeClock,date,battery,note,edit;
        TodayPage(Context c){super(c);
            title=text(c,c.getString(R.string.home_today),18,0xfff8f4ec);title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            largeClock=text(c,"",62,Color.WHITE);largeClock.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);largeClock.setTypeface(Typeface.create("sans-serif-thin",Typeface.NORMAL));
            date=text(c,"",14,0xffe0e9ed);date.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            battery=text(c,"",20,0xff344658);battery.setBackground(round(0xddf8f0e2,dp(24)));
            note=text(c,"",17,0xff344658);note.setGravity(Gravity.TOP|Gravity.START);note.setPadding(dp(22),dp(22),dp(22),dp(22));note.setMaxLines(6);note.setEllipsize(TextUtils.TruncateAt.END);note.setBackground(round(0xcff8f0e2,dp(24)));note.setOnClickListener(v->actions.note());
            edit=text(c,c.getString(R.string.home_edit_note),12,0xffedf2f4);edit.setOnClickListener(v->actions.note());
            for(View v:new View[]{title,largeClock,date,battery,note,edit})addView(v);
        }
        void tick(int level,String saved){largeClock.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm")));date.setText(LocalDate.now().format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(getResources().getConfiguration().getLocales().get(0),"MMMddEEE"),getResources().getConfiguration().getLocales().get(0))));battery.setText(getContext().getString(R.string.home_battery,level<0?"—":level+"%"));note.setText(saved.isBlank()?getContext().getString(R.string.home_note_empty):saved);}
        @Override protected void onMeasure(int ws,int hs){
            int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);int cw=exact(w-dp(44));
            title.measure(cw,exact(dp(35)));largeClock.measure(cw,exact((int)(h*.28)-(int)(h*.15)));
            date.measure(cw,exact((int)(h*.33)-(int)(h*.28)));battery.measure(cw,exact((int)(h*.51)-(int)(h*.38)));
            note.measure(cw,exact((int)(h*.80)-(int)(h*.55)));edit.measure(cw,exact(dp(48)));
        }
        @Override protected void onLayout(boolean c,int l,int t,int r,int b){int w=r-l,h=b-t,p=dp(22);
            title.layout(p,(int)(h*.095),w-p,(int)(h*.095)+dp(35));largeClock.layout(p,(int)(h*.15),w-p,(int)(h*.28));date.layout(p,(int)(h*.28),w-p,(int)(h*.33));
            battery.layout(p,(int)(h*.38),w-p,(int)(h*.51));note.layout(p,(int)(h*.55),w-p,(int)(h*.80));edit.layout(p,(int)(h*.81),w-p,(int)(h*.81)+dp(48));
        }
    }
    static int exact(int n){return MeasureSpec.makeMeasureSpec(Math.max(0,n),MeasureSpec.EXACTLY);}
    static final class Wallpaper extends View {
        final Paint paint=new Paint(3);final Path path=new Path();boolean inner,reverse;
        LinearGradient sky,dune,foreground,veil;float cachedFull=-1,cachedHeight=-1;
        Bitmap photo;final Matrix matrix=new Matrix();final Paint photoPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
        Wallpaper(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            float h=getHeight(),full=inner?getWidth():getWidth()*2f;
            if(cachedFull!=full||cachedHeight!=h){rebuildGradients(full,h);}
            if(photo!=null){
                // Fill each screen from the photo's centre, so the subject is not split across the fold.
                float scale=Math.max(getWidth()/(float)photo.getWidth(),h/photo.getHeight());
                matrix.setScale(scale,scale);matrix.postTranslate((getWidth()-photo.getWidth()*scale)/2f,(h-photo.getHeight()*scale)/2f);
                c.drawBitmap(photo,matrix,photoPaint);paint.setShader(veil);c.drawRect(0,0,getWidth(),h,paint);paint.setShader(null);return;
            }
            c.save();if(!inner&&!reverse)c.translate(-getWidth(),0);
            paint.setShader(sky);c.drawRect(0,0,full,h,paint);
            path.reset();path.moveTo(-full*.3f,h*.88f);path.cubicTo(full*.18f,h*.18f,full*.38f,h*1.05f,full*1.15f,h*.38f);path.lineTo(full*1.2f,h*1.1f);path.lineTo(-full*.3f,h*1.1f);path.close();
            paint.setShader(dune);c.drawPath(path,paint);
            path.reset();path.moveTo(-full*.2f,h*.97f);path.cubicTo(full*.4f,h*.62f,full*.66f,h*1.05f,full*1.1f,h*.65f);path.lineTo(full*1.1f,h*1.2f);path.lineTo(-full*.2f,h*1.2f);path.close();paint.setShader(foreground);c.drawPath(path,paint);
            paint.setShader(veil);c.drawRect(0,0,full,h,paint);paint.setShader(null);c.restore();
        }
        void rebuildGradients(float full,float h){cachedFull=full;cachedHeight=h;
            sky=new LinearGradient(0,0,full,h,new int[]{0xff18344c,0xff6e94a7,0xffd2b99f,0xff9a725a},new float[]{0,.42f,.75f,1},Shader.TileMode.CLAMP);
            dune=new LinearGradient(0,h*.45f,full,h,new int[]{0xffecd9b9,0xffa58870,0xff413d40},null,Shader.TileMode.CLAMP);
            foreground=new LinearGradient(0,h*.6f,0,h,0xffa88164,0xff624c43,Shader.TileMode.CLAMP);
            veil=new LinearGradient(0,0,0,h,new int[]{0x18000000,0x00000000,0x32000000},null,Shader.TileMode.CLAMP);
        }
    }
}
