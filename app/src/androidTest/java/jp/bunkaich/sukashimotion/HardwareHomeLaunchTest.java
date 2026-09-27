package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.ListView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Explicit opt-in test: temporarily holds both physical Fold7 displays. */
public class HardwareHomeLaunchTest {
    interface Check { boolean ready() throws Exception; }
    private void waitFor(Check check) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10000;
        int stable=0;
        while(SystemClock.elapsedRealtime()<deadline){
            stable=check.ready()?stable+1:0;
            if(stable>=3)return;
            Thread.sleep(50);
        }
        fail("Device condition must settle");
    }
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private boolean topOnInner(String component) throws Exception {
        return innerWindow("mFocusedApp=",component);
    }
    private boolean innerWindow(String field,String component) throws Exception {
        String dump=shell("dumpsys window displays");
        int inner=dump.indexOf("Display: mDisplayId=1 "); if(inner<0)return false;
        String section=dump.substring(inner);int end=section.indexOf("Display: mDisplayId=",10);if(end>=0)section=section.substring(0,end);
        return section.lines().anyMatch(line->line.contains(field)&&line.contains(component));
    }
    private void tapTile(Activity home,int slot,boolean longPress) throws Exception {
        // Coordinates from the View tree are final layout coordinates. Wait for
        // Samsung's task animation, whose compositor transform is independent.
        Thread.sleep(1000);
        int[] location=new int[2];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            View tile=find(home.getWindow().getDecorView(),HomeScene.class).primary.tiles[slot];
            tile.getLocationOnScreen(location);location[0]+=tile.getWidth()/2;location[1]+=tile.getHeight()/2;
        });
        shell(longPress?"input -d 1 swipe "+location[0]+" "+location[1]+" "+location[0]+" "+location[1]+" 700":"input -d 1 tap "+location[0]+" "+location[1]);
    }
    private static <T extends View> T find(View root,Class<T> type) {
        if(type.isInstance(root))return type.cast(root);
        if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){T result=find(group.getChildAt(i),type);if(result!=null)return result;}
        return null;
    }
    @Test public void realInnerHomeLaunchesSelectedAppsAndKeepsLongPress() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")) && SupportedDevice.current());
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        boolean enabled=MotionSettings.enabled(context);
        IShellBridge bridge=null;
        try {
            MotionSettings.setEnabled(context,false);
            context.stopService(new Intent(context,MotionService.class));
            waitFor(()->!MotionService.running);
            BridgeConnection.disconnect();waitFor(()->BridgeConnection.bridge==null);
            shell("input keyevent KEYCODE_HOME");
            java.util.concurrent.atomic.AtomicReference<Activity> home=new java.util.concurrent.atomic.AtomicReference<>();
            waitFor(()->{
                instrumentation.runOnMainSync(()->{
                    for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof HomeActivity)home.set(a);
                });return home.get()!=null;
            });
            Activity target=home.get();
            BridgeConnection.connect(context);waitFor(()->BridgeConnection.bridge!=null);
            bridge=BridgeConnection.bridge;
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float a,long t,int source){}});
            Bundle held=bridge.hold(false,0);assertTrue(held.toString(),held.getBoolean("ok"));
            waitFor(()->shell("dumpsys display").contains("mDisplayId=1"));
            Bundle moved=bridge.moveApp(0,1,false);assertTrue(moved.toString(),moved.getBoolean("ok"));
            waitFor(()->topOnInner(".HomeActivity"));
            waitFor(()->{
                AtomicInteger display=new AtomicInteger(-1);
                instrumentation.runOnMainSync(()->display.set(target.getDisplay().getDisplayId()));
                return display.get()==1;
            });
            MotionSettings.setEnabled(context,true);
            for(int round=0;round<3;round++) {
                AtomicInteger slot=new AtomicInteger(-1);
                waitFor(()->{
                    instrumentation.runOnMainSync(()->{
                        HomeScene scene=find(target.getWindow().getDecorView(),HomeScene.class);
                        if(scene==null)return;
                        for(int i=0;i<scene.primary.apps.size();i++)if(scene.primary.apps.get(i)!=null && scene.primary.apps.get(i).component().getPackageName().equals("com.sec.android.app.popupcalculator"))slot.set(i);
                    });return slot.get()>=0;
                });
                tapTile(target,slot.get(),false);
                waitFor(()->topOnInner("com.sec.android.app.popupcalculator/.Calculator"));
                assertTrue(bridge.navigate(1,KeyEvent.KEYCODE_HOME,-1).getBoolean("ok"));
                waitFor(()->innerWindow("mObscuringWindow=",".HomeActivity"));
                assertTrue(bridge.moveApp(1,0,false).getBoolean("ok"));
                waitFor(()->{AtomicInteger id=new AtomicInteger(-1);instrumentation.runOnMainSync(()->id.set(target.getDisplay().getDisplayId()));return id.get()==0;});
                assertTrue(bridge.moveApp(0,1,false).getBoolean("ok"));
                waitFor(()->innerWindow("mObscuringWindow=",".HomeActivity"));
            }
            tapTile(target,0,true);
            instrumentation.waitForIdleSync();
            AtomicInteger lists=new AtomicInteger();
            instrumentation.runOnMainSync(()->{for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(find(root,ListView.class)!=null)lists.incrementAndGet();});
            assertTrue("Long-press opens the app chooser",lists.get()>0);
            instrumentation.runOnMainSync(()->((HomeActivity)target).settings());
            waitFor(()->topOnInner(".MainActivity"));
        } catch(Throwable failure) {
            Bundle report=new Bundle();report.putString("stream",shell("dumpsys activity activities").lines()
                .filter(line->line.contains("Display #")||line.contains("type=home")||line.contains("topResumedActivity")||line.contains("Hist")&&line.contains(".HomeActivity"))
                .collect(java.util.stream.Collectors.joining("\n"))+"\n"+shell("dumpsys window displays").lines()
                .filter(line->line.contains("Display:")||line.contains("mFocusedApp=")||line.contains("mCurrentFocus=")||line.contains("mObscuringWindow="))
                .collect(java.util.stream.Collectors.joining("\n"))+"\n");
            instrumentation.sendStatus(0,report);throw failure;
        } finally {
            MotionSettings.setEnabled(context,false);
            if(bridge!=null){bridge.release();bridge.stopAngles();}
            MotionSettings.setEnabled(context,enabled);
            if(enabled)context.startForegroundService(new Intent(context,MotionService.class).setAction("restore"));
        }
    }
}
