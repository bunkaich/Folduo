package jp.bunkaich.sukashimotion;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class LanguageTest {
    private Context context;
    private ActivityScenario<MainActivity> screen;
    private LocaleList original;
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();

    @Before public void start(){
        context=instrumentation.getTargetContext();
        original=context.getSystemService(LocaleManager.class).getApplicationLocales();
        MotionSettings.setEnabled(context,false);
        screen=ActivityScenario.launch(MainActivity.class);
    }
    @After public void stop()throws Exception{
        MotionSettings.setEnabled(context,false);
        context.stopService(new Intent(context,MotionService.class));
        waitFor(()->!MotionService.running);
        screen.onActivity(a->a.getSystemService(LocaleManager.class).setApplicationLocales(original));
        instrumentation.waitForIdleSync();screen.close();
    }
    private interface Check {boolean ok();}
    private void waitFor(Check condition)throws Exception{
        long end=SystemClock.elapsedRealtime()+6000;
        while(!condition.ok()&&SystemClock.elapsedRealtime()<end)Thread.sleep(30);
        assertTrue("Condition reached before timeout",condition.ok());
    }
    private void select(int index,String language)throws Exception{
        screen.onActivity(a->{
            a.findViewById(R.id.language_button).performClick();
            for(View root:WindowInspector.getGlobalWindowViews()){
                ListView list=find(root,ListView.class);
                if(list!=null){list.performItemClick(list.getChildAt(index),index,list.getItemIdAtPosition(index));return;}
            }
            fail("Language picker must be available without Shizuku");
        });
        waitFor(()->{
            AtomicBoolean matches=new AtomicBoolean();
            screen.onActivity(a->matches.set(a.getResources().getConfiguration().getLocales().get(0).getLanguage().equals(language)));
            return matches.get();
        });
    }
    private static <T extends View> T find(View view,Class<T> type){
        if(type.isInstance(view))return type.cast(view);
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){T found=find(group.getChildAt(i),type);if(found!=null)return found;}
        return null;
    }
    private Context localized(String language){
        Configuration config=new Configuration(context.getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags(language));return context.createConfigurationContext(config);
    }
    @Test public void pickerSwitchesBothWaysAndFollowsSystemAgain()throws Exception{
        select(1,"en");
        screen.onActivity(a->{assertEquals("Preview the animation",a.getString(R.string.preview));assertEquals("en",a.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());});
        screen.recreate();
        screen.onActivity(a->assertEquals("en",a.getResources().getConfiguration().getLocales().get(0).getLanguage()));
        select(2,"ja");
        screen.onActivity(a->assertEquals("見え方を試す",a.getString(R.string.preview)));
        select(3,"ko");
        screen.onActivity(a->assertEquals("애니메이션 미리 보기",a.getString(R.string.preview)));
        String system=context.getSystemService(LocaleManager.class).getSystemLocales().get(0).getLanguage();
        select(0,system.equals("ja")||system.equals("ko")?system:"en");
        assertTrue(context.getSystemService(LocaleManager.class).getApplicationLocales().isEmpty());
    }
    @Test public void languageChangeUpdatesNotificationWithoutRestartingAngles()throws Exception{
        select(1,"en");
        ServiceLifecycleTest.FakeBridge bridge=new ServiceLifecycleTest.FakeBridge();BridgeConnection.bridge=bridge;
        context.startForegroundService(new Intent(context,MotionService.class));
        waitFor(()->bridge.sink!=null);bridge.sink.angle(120,SystemClock.elapsedRealtime(),3);
        waitFor(()->MotionService.status.is(R.string.close_to_prepare));
        int starts=bridge.starts;IAngleSink sink=bridge.sink;
        assertNotification("Resume","Fold animation");
        select(2,"ja");assertNotification("再開","開閉の演出");
        assertTrue(MotionService.running);assertSame("Language must not replace the sensor listener",sink,bridge.sink);assertEquals(starts,bridge.starts);
        select(1,"en");assertNotification("Resume","Fold animation");
        assertEquals(0,bridge.captures);assertEquals(0,bridge.moves);assertEquals(0,bridge.holds);
    }
    private void assertNotification(String action,String channel)throws Exception{
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        waitFor(()->{
            for(var notification:manager.getActiveNotifications())if(notification.getId()==7){
                Notification n=notification.getNotification();
                return n.actions!=null&&action.contentEquals(n.actions[0].title)&&channel.contentEquals(manager.getNotificationChannel("motion").getName())
                    &&n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().equals(MotionService.status.resolve(localized(action.equals("Resume")?"en":"ja")));
            }
            return false;
        });
    }
    @Test public void savedRecoveryAndShellErrorsUseCurrentLanguage(){
        Context ja=localized("ja"),en=localized("en");
        UiText reason=UiText.of(R.string.failure_retry,UiText.of(R.string.prepare_failed,UiText.raw("IllegalStateException: @folduo/err_display_conflict")));
        MotionSettings.recovery(ja,reason);
        String japanese=MotionSettings.recovery(ja),english=MotionSettings.recovery(en);
        assertTrue(japanese.contains("他の処理が画面を制御しています"));
        assertTrue(english.contains("Another process is controlling the displays"));
        assertFalse(english.contains("@folduo/"));assertFalse(english.matches("(?s).*[ぁ-んァ-ン一-龯].*"));
        assertTrue(reason.encode(ja).contains("prepare_failed"));
        assertEquals(en.getString(R.string.previous_recovery),UiText.decode(en,"invalid legacy record").resolve(en));
        assertEquals(en.getString(R.string.unknown_error),UiText.raw("@folduo/nonexistent_message").resolve(en));
        MotionSettings.recovery(context,"");
    }
    @Test public void englishControlsAndSensorReportAreLocalized()throws Exception{
        select(1,"en");
        screen.onActivity(a->{
            InnerNavigation nav=new InnerNavigation(a.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),0,1968,2184,(action,task)->{});
            try{
                boolean found=false;
                for(View root:WindowInspector.getGlobalWindowViews())found|=hasDescription(root,"Recent apps");
                assertTrue("Overlay controls use the app language",found);
            }finally{nav.close();}
            Bundle b=new Bundle();b.putInt("uid",2000);b.putString("display","inner=0 / cover=1");
            String report=MainActivity.formatReport(a,b);assertTrue(report.contains("Helper UID: 2000"));assertTrue(report.contains("Dual gyroscopes:"));
        });
    }
    private boolean hasDescription(View view,String label){
        if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return true;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)if(hasDescription(group.getChildAt(i),label))return true;
        return false;
    }
}
