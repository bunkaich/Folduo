package jp.bunkaich.sukashimotion;
import android.os.Bundle;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TaskDisplayRouterTest {
 public static class WindowConfig {int type;WindowConfig(int t){type=t;}public int getActivityType(){return type;}}
 public static class Config {public WindowConfig windowConfiguration;Config(int t){windowConfiguration=new WindowConfig(t);}}
 public static class Info {
  public int taskId,displayId,parentTaskId=-1;public Config configuration;public ComponentName topActivity=new ComponentName("test","test.Main");
  public Intent baseIntent=new Intent();public ComponentName realActivity,origActivity;
  Info(int id,int display,int type){taskId=id;displayId=display;configuration=new Config(type);}
 }
 public static class Manager {
  List<Info> all=new ArrayList<>();int resumed=-1,focused=-1;List<String> moves=new ArrayList<>();boolean aospHomeRule,recentsStaysPut;
  public List<Info> getTasks(int count,boolean visible,boolean intent,int display){return all.stream().filter(i->i.displayId==display&&i.topActivity!=null).toList();}
  public List<Info> getAllRootTaskInfosOnDisplay(int display){return all.stream().filter(i->i.displayId==display&&i.parentTaskId<0).toList();}
  public void moveTaskToRootTask(int id,int root,boolean top){
   Info task=all.stream().filter(i->i.taskId==id).findFirst().orElseThrow();
   Info destination=all.stream().filter(i->i.taskId==root).findFirst().orElseThrow();
   if(aospHomeRule&&destination.configuration.windowConfiguration.type==2)throw new IllegalArgumentException("moveTaskToRootTask: Attempt to move task "+id+" to rootTask "+root);
   task.parentTaskId=root;task.displayId=destination.displayId;moves.add("child:"+id+":"+root);
  }
  public void moveRootTaskToDisplayOnTopOrBottom(int id,int display,boolean top){
   Info task=all.stream().filter(i->i.taskId==id).findFirst().orElseThrow();
   if(task.configuration.windowConfiguration.type==2&&all.stream().anyMatch(i->i.displayId==display&&i.configuration.windowConfiguration.type==2))throw new IllegalStateException("Duplicate HOME root");
   moves.add(id+":"+display+":"+top);task.displayId=display;
  }
  public void setFocusedRootTask(int id){focused=id;}
  public void setFocusedTask(int id){focused=id;}
  public int startActivityFromRecents(int id,Bundle options){resumed=id;if(recentsStaysPut)return 0;all.stream().filter(i->i.taskId==id).forEach(i->i.displayId=options.getInt("android.activity.launchDisplayId", -1));return 0;}
 }
 @Test public void homeUsesExistingDestinationWithoutMovingAnotherRoot()throws Exception{
  Manager m=new Manager();m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));TaskDisplayRouter r=new TaskDisplayRouter(m,Manager.class);
  assertTrue(r.move(0,1,false).getBoolean("home"));assertEquals(8,m.focused);assertTrue(m.moves.isEmpty());
  r.move(1,0,false);assertEquals(7,m.focused);assertTrue(m.moves.isEmpty());
 }
 @Test public void repeatedHomeFoldsKeepBothRootsOnTheirDisplay()throws Exception{
  Manager m=new Manager();m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));TaskDisplayRouter r=new TaskDisplayRouter(m,Manager.class);
  for(int i=0;i<8;i++){r.move(0,1,false);r.move(1,0,false);}
  assertEquals(0,m.all.get(0).displayId);assertEquals(1,m.all.get(1).displayId);assertTrue(m.moves.isEmpty());
 }
 @Test public void restoreNeverMovesDuplicateHomeOrUnrelatedApps()throws Exception{
  Manager m=new Manager();m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));m.all.add(new Info(15,1,1));TaskDisplayRouter r=new TaskDisplayRouter(m,Manager.class);r.restore();
  assertEquals(7,m.focused);assertTrue(m.moves.isEmpty());assertEquals(1,m.all.get(2).displayId);
 }
 @Test public void returnCurrentAppWithoutResurrectingPreviousApp()throws Exception{
  Manager m=new Manager();m.all.add(new Info(22,1,1));m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));TaskDisplayRouter r=new TaskDisplayRouter(m,Manager.class);r.restore();assertEquals(22,m.resumed);assertEquals(0,m.all.get(0).displayId);assertTrue(m.moves.isEmpty());
 }
 @Test public void appsStillUseRecentsResume()throws Exception{
  Manager m=new Manager();m.all.add(new Info(12,0,1));new TaskDisplayRouter(m,Manager.class).move(0,1,false);assertEquals(12,m.resumed);assertEquals(1,m.all.get(0).displayId);assertTrue(m.moves.isEmpty());
 }
 @Test public void backFocusUsesTheAppOnTheRequestedDisplay()throws Exception{
  Manager m=new Manager();m.all.add(new Info(7,0,2));m.all.add(new Info(12,1,1));new TaskDisplayRouter(m,Manager.class).focusTop(1);assertEquals(12,m.focused);
 }
 @Test public void systemTasksRemainExcluded()throws Exception{
  Manager m=new Manager();m.all.add(new Info(12,0,3));assertThrows(UnsupportedOperationException.class,()->new TaskDisplayRouter(m,Manager.class).move(0,1,false));assertEquals(-1,m.resumed);
 }
 @Test public void selectedAppIsLaunchedThenMovedToInner()throws Exception{
  Manager m=new Manager();m.all.add(new Info(8,1,2));ComponentName chosen=new ComponentName("calculator","calculator.Main");
  TaskDisplayRouter r=new TaskDisplayRouter(m,Manager.class);
  r.launchSelected(chosen,1,()->{Info app=new Info(31,0,1);app.baseIntent=TaskDisplayRouter.launchIntent(chosen);m.all.add(0,app);});
  assertEquals(31,m.resumed);assertEquals(1,m.all.get(0).displayId);assertEquals(31,m.focused);
 }
 @Test public void unrelatedForegroundAppIsNeverMovedInsteadOfSelection()throws Exception{
  Manager m=new Manager();Info unrelated=new Info(22,0,1);unrelated.realActivity=new ComponentName("other","other.Main");m.all.add(unrelated);
  ComponentName chosen=new ComponentName("calculator","calculator.Main");TaskDisplayRouter r=new TaskDisplayRouter(m,Manager.class);
  assertThrows(IllegalStateException.class,()->r.launchSelected(chosen,1,()->{}));
  assertEquals(-1,m.resumed);assertEquals(0,unrelated.displayId);
 }
 @Test public void launcherAliasMatchesItsOriginalComponent()throws Exception{
  Manager m=new Manager();ComponentName alias=new ComponentName("calculator","calculator.Alias");
  Info app=new Info(31,0,1);app.origActivity=alias;app.realActivity=new ComponentName("calculator","calculator.Main");m.all.add(app);
  new TaskDisplayRouter(m,Manager.class).launchSelected(alias,1,()->{});assertEquals(31,m.resumed);
 }
 @Test public void homeButtonPrefersSelectedLauncherOverSecondarySystemHome()throws Exception{
  Manager m=new Manager();Info system=new Info(9,1,2);system.realActivity=new ComponentName("system","system.Home");m.all.add(system);
  ComponentName selected=new ComponentName("folduo","folduo.Home");Info custom=new Info(10,1,2);custom.baseIntent=TaskDisplayRouter.launchIntent(selected);m.all.add(custom);
  new TaskDisplayRouter(m,Manager.class).showHome(1,selected);assertEquals(10,m.focused);assertTrue(m.moves.isEmpty());
 }
 @Test public void foldingTransfersChosenHomeChildWithoutDuplicatingRoots()throws Exception{
  Manager m=new Manager();Info launcher=new Info(10,0,2);launcher.parentTaskId=7;
  m.all.add(launcher);m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));TaskDisplayRouter router=new TaskDisplayRouter(m,Manager.class);
  router.move(0,1,false);assertEquals(1,launcher.displayId);assertEquals(8,launcher.parentTaskId);assertEquals(10,m.focused);
  router.move(1,0,false);assertEquals(0,launcher.displayId);assertEquals(7,launcher.parentTaskId);
  assertEquals(List.of("child:10:8","child:10:7"),m.moves);
 }
 @Test public void foldingResumesChosenHomeWhenFirmwareRefusesHomeChildMove()throws Exception{
  Manager m=new Manager();m.aospHomeRule=true;Info launcher=new Info(10,0,2);launcher.parentTaskId=7;
  m.all.add(launcher);m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));TaskDisplayRouter router=new TaskDisplayRouter(m,Manager.class);
  assertTrue(router.move(0,1,false).getBoolean("home"));assertEquals(10,m.resumed);assertEquals(10,m.focused);assertEquals(1,launcher.displayId);
  router.move(1,0,false);assertEquals(0,launcher.displayId);assertEquals(10,m.focused);assertTrue(m.moves.isEmpty());
 }
 @Test public void foldingShowsDestinationHomeWhenChosenHomeCannotMove()throws Exception{
  Manager m=new Manager();m.aospHomeRule=true;m.recentsStaysPut=true;Info launcher=new Info(10,0,2);launcher.parentTaskId=7;
  m.all.add(launcher);m.all.add(new Info(7,0,2));m.all.add(new Info(8,1,2));TaskDisplayRouter router=new TaskDisplayRouter(m,Manager.class);
  assertTrue(router.move(0,1,false).getBoolean("home"));assertEquals(8,m.focused);
  assertEquals(0,launcher.displayId);assertEquals(7,launcher.parentTaskId);assertTrue(m.moves.isEmpty());
 }
}
