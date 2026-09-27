package jp.bunkaich.sukashimotion;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;

/** Move app and home tasks without swapping physical/logical display IDs. */
final class TaskDisplayRouter {
    private final Object manager;private final Class<?> api;
    private int lastDestination;
    private final Set<Integer> movedTasks=new LinkedHashSet<>();
    TaskDisplayRouter()throws Exception{
        manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        api=Class.forName("android.app.IActivityTaskManager");
    }
    TaskDisplayRouter(Object manager,Class<?> api){this.manager=manager;this.api=api;}
    private int number(Object info,String field)throws Exception{return info.getClass().getField(field).getInt(info);}
    private int activityType(Object info)throws Exception{
        Object configuration=info.getClass().getField("configuration").get(info);
        Object window=configuration.getClass().getField("windowConfiguration").get(configuration);
        return (int)window.getClass().getMethod("getActivityType").invoke(window);
    }
    private boolean standard(Object info)throws Exception{return activityType(info)==1;}
    private List<?> roots(int display)throws Exception{return (List<?>)api.getMethod("getAllRootTaskInfosOnDisplay",int.class).invoke(manager,display);}
    private Object home(int display)throws Exception{
        for(Object root:roots(display))if(activityType(root)==2&&root.getClass().getField("topActivity").get(root)!=null)return root;
        return null;
    }
    private void moveHome(Object root,int destination,boolean focus)throws Exception{
        // Samsung permits one HOME root per display. Use an already-created HOME,
        // never reparent a second HOME root into it.
        Object existing=home(destination);
        if(existing!=null)root=existing;
        int id=number(root,"taskId");
        if(number(root,"displayId")!=destination)api.getMethod("moveRootTaskToDisplayOnTopOrBottom",int.class,int.class,boolean.class).invoke(manager,id,destination,focus);
        if(focus)api.getMethod("setFocusedRootTask",int.class).invoke(manager,id);
        lastDestination=destination;
    }
    synchronized void showHome(int display)throws Exception{
        showHome(display,null);
    }
    synchronized void showHome(int display,ComponentName preferred)throws Exception{
        if(preferred!=null){
            Object task=homeTask(display,preferred);
            if(task==null)task=homeTask(display==0?1:0,preferred);
            if(task!=null){moveHomeTask(task,display);return;}
        }
        Object root=home(display);if(root==null)root=home(display==0?1:0);
        if(root==null)throw new IllegalStateException("@folduo/err_home_missing");
        moveHome(root,display,true);
    }
    private Object homeTask(int display,ComponentName component)throws Exception{
        List<?> tasks=(List<?>)api.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,64,false,false,display);
        for(Object task:tasks)if(activityType(task)==2&&matchesLaunch(task,component))return task;
        return null;
    }
    private void moveHomeTask(Object task,int destination)throws Exception{
        int source=number(task,"displayId"),id=number(task,"taskId");
        if(source==destination){resumeHomeTask(id,destination);return;}
        Object sourceRoot=home(source),destinationRoot=home(destination);
        if(sourceRoot==null)throw new IllegalStateException("@folduo/err_source_home_missing");
        // Keep Samsung's one-HOME-root-per-display invariant, but transfer the
        // selected launcher's child task, not the other display's stale home.
        if(destinationRoot!=null&&id!=number(sourceRoot,"taskId")){
            try{api.getMethod("moveTaskToRootTask",int.class,int.class,boolean.class).invoke(manager,id,number(destinationRoot,"taskId"),true);}
            catch(InvocationTargetException e){
                // SM-F966N firmware keeps AOSP's rule that this call may not reparent into a HOME
                // root, and rejects before changing anything. Recents places the same selected
                // HOME in the destination's root; the destination's own HOME is the last resort.
                if(!(e.getCause() instanceof IllegalArgumentException))throw e;
                if(!resumeHomeOn(id,destination))moveHome(destinationRoot,destination,true);
                return;
            }
            resumeHomeTask(id,destination);
        }else moveHome(sourceRoot,destination,true);
    }
    private boolean resumeHomeOn(int id,int display)throws Exception{
        try{resumeHomeTask(id,display);}catch(Exception refused){return false;}
        for(Object task:(List<?>)api.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,64,false,false,display))
            if(number(task,"taskId")==id)return true;
        return false;
    }
    private void resumeHomeTask(int id,int display)throws Exception{
        int result=(int)api.getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(manager,id,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
        if(result<0)throw new IllegalStateException("@folduo/err_home_missing");
        api.getMethod("setFocusedTask",int.class).invoke(manager,id);lastDestination=display;
    }
    private List<?> tasks(int display)throws Exception{return (List<?>)api.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,1,false,false,display);}
    synchronized Bundle move(int source,int destination,boolean idle)throws Exception{
        Bundle result=new Bundle();List<?> tasks=tasks(source);
        if(!tasks.isEmpty()&&activityType(tasks.get(0))==2){
            moveHomeTask(tasks.get(0),destination);result.putBoolean("ok",true);result.putBoolean("moved",true);result.putBoolean("home",true);return result;
        }
        if(tasks.isEmpty()||!standard(tasks.get(0))){
            if(idle){result.putBoolean("ok",true);return result;}
            throw new UnsupportedOperationException("@folduo/err_system_screen");
        }
        Object task=tasks.get(0);int id=number(task,"taskId");
        if(number(task,"displayId")!=source)throw new IllegalStateException("@folduo/err_app_moved");
        // Recents restarts the existing task on its destination. A bare reparent left it undrawn
        // on this Fold7. The framework still checks launch/display and task restrictions.
        Bundle options=ActivityOptions.makeBasic().setLaunchDisplayId(destination).toBundle();
        api.getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(manager,id,options);
        lastDestination=destination;movedTasks.add(id);result.putBoolean("ok",true);result.putBoolean("moved",true);result.putInt("taskId",id);return result;
    }
    synchronized ArrayList<Bundle> recentApps(android.content.Context context)throws Exception{
        Object slice=api.getMethod("getRecentTasks",int.class,int.class,int.class).invoke(manager,24,2,android.os.Process.myUid()/100000);
        List<?> recent=(List<?>)slice.getClass().getMethod("getList").invoke(slice);ArrayList<Bundle> result=new ArrayList<>();
        for(Object task:recent){
            if(!standard(task))continue;
            android.content.ComponentName component=(android.content.ComponentName)task.getClass().getField("realActivity").get(task);
            if(component==null||component.getPackageName().equals(BuildConfig.APPLICATION_ID))continue;
            Bundle row=new Bundle();row.putInt("taskId",number(task,"taskId"));
            try{
                android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(component.getPackageName(),0);
                row.putString("label",String.valueOf(info.loadLabel(context.getPackageManager())));
                android.graphics.drawable.Drawable icon=info.loadIcon(context.getPackageManager());
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888);
                icon.setBounds(0,0,96,96);icon.draw(new android.graphics.Canvas(bitmap));row.putParcelable("icon",bitmap);
            }catch(android.content.pm.PackageManager.NameNotFoundException unavailable){continue;}
            result.add(row);if(result.size()>=12)break;
        }
        return result;
    }
    synchronized void selectRecent(int taskId,int display)throws Exception{
        Object slice=api.getMethod("getRecentTasks",int.class,int.class,int.class).invoke(manager,64,2,android.os.Process.myUid()/100000);
        for(Object task:(List<?>)slice.getClass().getMethod("getList").invoke(slice))if(number(task,"taskId")==taskId&&standard(task)){
            api.getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(manager,taskId,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());lastDestination=display;movedTasks.add(taskId);focusTop(display);return;
        }
        throw new IllegalStateException("@folduo/err_app_finished");
    }
    synchronized void focusTop(int display)throws Exception{
        List<?> top=tasks(display);if(top.isEmpty())return;
        int task=number(top.get(0),"taskId");
        api.getMethod("setFocusedTask",int.class).invoke(manager,task);
    }
    private int settingsTask()throws Exception{
        Object slice=api.getMethod("getRecentTasks",int.class,int.class,int.class).invoke(manager,64,2,android.os.Process.myUid()/100000);
        for(Object task:(List<?>)slice.getClass().getMethod("getList").invoke(slice)){
            android.content.ComponentName component=(android.content.ComponentName)task.getClass().getField("realActivity").get(task);
            if(standard(task)&&component!=null&&"com.android.settings".equals(component.getPackageName()))return number(task,"taskId");
        }
        return -1;
    }
    static Intent launchIntent(ComponentName component) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
    synchronized void launchApp(Context context, ComponentName component, int display) throws Exception {
        // Only accept an enabled launcher entry. The UI cannot supply arbitrary
        // intents, extras, users or non-exported components to this shell process.
        if (component == null || display != 1) throw new IllegalArgumentException("@folduo/err_launch_target");
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.getPackageName());
        boolean allowed = false;
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(query, 0)) {
            if (info.activityInfo != null && info.activityInfo.exported && info.activityInfo.enabled
                    && info.activityInfo.applicationInfo.enabled
                    && component.equals(new ComponentName(info.activityInfo.packageName, info.activityInfo.name))) { allowed = true; break; }
        }
        if (!allowed) throw new IllegalArgumentException("@folduo/err_launch_target");
        launchSelected(component, display, () -> startOnPrimary(component));
    }
    private static void startOnPrimary(ComponentName component) {
        // A package context created in app_process retains the system attribution
        // package on Samsung. Activity.startActivity then fails its UID/package
        // check. The shell command uses com.android.shell's actual attribution.
        runPrimaryLaunch("am", "start", "--display", "0", "-a", Intent.ACTION_MAIN,
            "-c", Intent.CATEGORY_LAUNCHER, "-n", component.flattenToString(), "-f", "0x10200000");
    }
    private static void runPrimaryLaunch(String... command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) throw new IllegalStateException("@folduo/err_launch_unconfirmed");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || output.contains("Error:") || output.contains("Exception")) throw new IllegalStateException("@folduo/err_launch_unconfirmed");
        } catch (java.io.IOException e) { throw new IllegalStateException("@folduo/err_launch_unconfirmed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("@folduo/err_launch_unconfirmed", e); }
        finally { if (process != null) process.destroy(); }
    }
    synchronized void launchSelected(ComponentName component, int display, Runnable launch) throws Exception {
        // Samsung redirects new activities away from the rear display, even when
        // their visible launcher lives there. Launch normally, then resume only
        // the task that belongs to the exact selected launcher component.
        launch.run();
        for (int attempt = 0; attempt < 25; attempt++) {
            List<?> running = (List<?>) api.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class).invoke(manager, 16, false, false, 0);
            for (Object task : running) {
                if (!standard(task) || !matchesLaunch(task, component)) continue;
                int id = number(task, "taskId");
                int result = (int) api.getMethod("startActivityFromRecents", int.class, Bundle.class).invoke(manager, id, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
                if (result < 0) throw new IllegalStateException("@folduo/err_launch_unconfirmed");
                movedTasks.add(id); lastDestination = display; focusTop(display);
                android.util.Log.i("FolduoLaunch", "selected task=" + id + " display=" + display);
                return;
            }
            android.os.SystemClock.sleep(40);
        }
        throw new IllegalStateException("@folduo/err_launch_unconfirmed");
    }
    private boolean matchesLaunch(Object task, ComponentName component) throws Exception {
        Intent base = (Intent) task.getClass().getField("baseIntent").get(task);
        if (base != null && component.equals(base.getComponent())) return true;
        return component.equals(task.getClass().getField("realActivity").get(task))
            || component.equals(task.getClass().getField("origActivity").get(task));
    }
    synchronized void openSettings(android.content.Context context,int display)throws Exception{
        int task=settingsTask();
        if(task<0){
            // Samsung rejects a new caller on this private auxiliary panel. Launch
            // the app normally first, then use the supported existing-task route.
            runPrimaryLaunch("am","start","--display","0","-a",android.provider.Settings.ACTION_SETTINGS);
            for(int i=0;i<20&&task<0;i++){android.os.SystemClock.sleep(50);task=settingsTask();}
        }
        if(task<0)throw new IllegalStateException("@folduo/err_settings_missing");
        selectRecent(task,display);lastDestination=display;
    }
    synchronized android.graphics.Bitmap preview(int taskId)throws Exception{
        // Only use the platform's recents snapshot, which excludes protected windows.
        // A missing/unsupported snapshot is represented by the normal app icon.
        Object slice=api.getMethod("getRecentTasks",int.class,int.class,int.class).invoke(manager,64,2,android.os.Process.myUid()/100000);
        boolean allowed=false;
        for(Object task:(List<?>)slice.getClass().getMethod("getList").invoke(slice))if(number(task,"taskId")==taskId&&standard(task)){allowed=true;break;}
        if(!allowed)return null;
        Object snapshot=api.getMethod("getTaskSnapshot",int.class,boolean.class).invoke(manager,taskId,true);
        if(snapshot==null)return null;
        android.hardware.HardwareBuffer buffer=(android.hardware.HardwareBuffer)snapshot.getClass().getMethod("getHardwareBuffer").invoke(snapshot);
        if(buffer==null)return null;
        try{
            if((buffer.getUsage()&android.hardware.HardwareBuffer.USAGE_PROTECTED_CONTENT)!=0)return null;
            android.graphics.ColorSpace space=(android.graphics.ColorSpace)snapshot.getClass().getMethod("getColorSpace").invoke(snapshot);
            android.graphics.Bitmap hardware=android.graphics.Bitmap.wrapHardwareBuffer(buffer,space);if(hardware==null)return null;
            android.graphics.Bitmap cpu=hardware.copy(android.graphics.Bitmap.Config.ARGB_8888,false);hardware.recycle();if(cpu==null)return null;
            float scale=Math.min(1,320f/Math.max(cpu.getWidth(),cpu.getHeight()));
            android.graphics.Bitmap small=android.graphics.Bitmap.createScaledBitmap(cpu,Math.max(1,Math.round(cpu.getWidth()*scale)),Math.max(1,Math.round(cpu.getHeight()*scale)),true);
            if(small!=cpu)cpu.recycle();return small;
        }finally{buffer.close();}
    }
    synchronized void restore()throws Exception{
        List<?> visible=tasks(1);Object top=visible.isEmpty()?null:visible.get(0);
        int active=top!=null&&standard(top)?number(top,"taskId"):-1;
        // Return the current app first. Do not sweep unrelated HOME roots or change
        // which unrelated application was selected after the fold.
        if(active>=0)api.getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(manager,active,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        else if(top!=null&&activityType(top)==2)moveHomeTask(top,0);
        for(Object root:roots(1))if(standard(root)&&movedTasks.contains(number(root,"taskId"))&&number(root,"taskId")!=active)
            api.getMethod("moveRootTaskToDisplayOnTopOrBottom",int.class,int.class,boolean.class).invoke(manager,number(root,"taskId"),0,false);
        movedTasks.clear();lastDestination=0;
    }
}
