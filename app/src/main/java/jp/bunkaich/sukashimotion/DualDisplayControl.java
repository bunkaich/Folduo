package jp.bunkaich.sukashimotion;

import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Validates Samsung's advertised states rather than relying on numeric IDs. */
final class DualDisplayControl implements AutoCloseable {
    final Object manager,service;final Class<?> requestType,callbackType;final Method request,cancel,read;
    final int innerState,outerState;private Object owned;
    DualDisplayControl()throws Exception{
        if(!SupportedDevice.current())throw new UnsupportedOperationException("@folduo/err_wrong_model");
        Class<?> type=Class.forName("android.hardware.devicestate.DeviceStateManager");
        manager=type.getConstructor().newInstance();int inner=-1,outer=-1;
        for(Object state:(List<?>)type.getMethod("getSupportedDeviceStates").invoke(manager)){
            Class<?> s=state.getClass();String name=(String)s.getMethod("getName").invoke(state);int id=(int)s.getMethod("getIdentifier").invoke(state);
            if(!(boolean)s.getMethod("hasProperty",int.class).invoke(state,10))continue;
            if("CONCURRENT_INNER_DEFAULT".equals(name)&&(boolean)s.getMethod("hasProperty",int.class).invoke(state,12))inner=id;
            if("CONCURRENT_OUTER_DEFAULT".equals(name)&&(boolean)s.getMethod("hasProperty",int.class).invoke(state,11))outer=id;
        }
        if(inner<0||outer<0)throw new UnsupportedOperationException("@folduo/err_states_unavailable");
        innerState=inner;outerState=outer;
        requestType=Class.forName("android.hardware.devicestate.DeviceStateRequest");callbackType=Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback");
        request=type.getMethod("requestState",requestType,Executor.class,callbackType);cancel=type.getMethod("cancelStateRequest");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"device_state");
        service=Class.forName("android.hardware.devicestate.IDeviceStateManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        read=Class.forName("android.hardware.devicestate.IDeviceStateManager").getMethod("getDeviceStateInfo");
    }
    int id(Object info,String field)throws Exception {Object state=info.getClass().getField(field).get(info);return (int)state.getClass().getMethod("getIdentifier").invoke(state);}
    String describe()throws Exception{Object info=read.invoke(service);return "inner="+innerState+" / cover="+outerState+" / current="+id(info,"currentState")+" / base="+id(info,"baseState");}
    synchronized boolean isOwned(){return owned!=null;}
    synchronized void hold(boolean inner,int previousOwner)throws Exception{
        Object info=read.invoke(service);
        int desired=inner?innerState:outerState;
        if(owned==null&&id(info,"currentState")!=id(info,"baseState")&&!recoverable(previousOwner,desired))throw new IllegalStateException("@folduo/err_display_conflict");
        Object builder=requestType.getMethod("newBuilder",int.class).invoke(null,inner?innerState:outerState);
        Object next=builder.getClass().getMethod("build").invoke(builder);
        Object callback=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,m,args)->{
            switch(m.getName()){
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "toString":return "FolduoDisplayCallback";
                case "onRequestCanceled":synchronized(this){if(owned==next)owned=null;}
            }return null;
        });
        Object previous=owned;owned=next;
        try{request.invoke(manager,next,(Executor)Runnable::run,callback);}catch(Exception e){owned=previous;throw e;}
    }
    private boolean recoverable(int previousOwner,int desired)throws Exception{
        if(previousOwner<=0)return false;
        boolean alive=true;
        try{android.system.Os.kill(previousOwner,0);}catch(android.system.ErrnoException e){alive=e.errno!=android.system.OsConstants.ESRCH;}
        if(alive)return false;
        java.lang.Process probe=new ProcessBuilder("dumpsys","device_state").start();
        try{
            if(!probe.waitFor(1500,java.util.concurrent.TimeUnit.MILLISECONDS))return false;
            String dump=new String(probe.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            return DisplayRequestOwner.canRecover(dump,previousOwner,desired,false);
        }finally{probe.destroy();}
    }
    @Override public synchronized void close(){if(owned==null)return;try{cancel.invoke(manager);owned=null;}catch(Exception ignored){/* Watchdog retries, without cancelling any other process's request. */}}
}
