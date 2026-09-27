import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.wallpaper.WallpaperDescription;
import android.content.ComponentName;
import android.content.Context;
import android.os.*;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Set;

/** Optional, explicit ADB setup for a supported Fold7; changes front HOME only. */
public final class CoverWallpaperSetup {
    private static final int COVER_HOME = 17;
    /** Same list as the app's SupportedDevice: SM-F966Z (Japan, tested) and SM-F966N (Korea). */
    private static final Set<String> MODELS = Set.of("SM-F966Z", "SM-F966N");
    private static final String RESOURCE_PACKAGE = "com.samsung.android.wallpaper.res";
    private static final String STOCK_URI = "android.resource://" + RESOURCE_PACKAGE + "/drawable/sub_wallpaper_002.png";
    private static final ComponentName LIVE = new ComponentName("com.samsung.android.wallpaper.live",
            "com.samsung.android.wallpaper.live.fold.FoldInteractive");

    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }

    private static boolean angleVideo(Bundle extras) {
        return extras != null && extras.getBundle("serviceSettings") != null
                && "video_002.mp4".equals(extras.getBundle("serviceSettings").getString("filename"));
    }

    private static void run(String[] args) throws Exception {
        String action = args.length == 0 ? "status" : args[0];
        if (!action.equals("status") && !action.equals("apply") && !action.equals("restore-stock"))
            throw new IllegalArgumentException("Use status, apply, or restore-stock");
        if (android.os.Process.myUid() != 2000 || Build.MODEL == null || !MODELS.contains(Build.MODEL))
            throw new IllegalStateException("This setup is limited to ADB shell on a supported Fold7 " + MODELS);
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        WallpaperManager manager = WallpaperManager.getInstance(context);
        Method getExtras = WallpaperManager.class.getMethod("getWallpaperExtras", int.class, int.class);
        Object uri = WallpaperManager.class.getMethod("semGetUri", int.class).invoke(manager, COVER_HOME);
        WallpaperInfo info = (WallpaperInfo) WallpaperManager.class.getMethod("getWallpaperInfo", int.class, int.class)
                .invoke(manager, COVER_HOME, 0);
        boolean stock = STOCK_URI.equals(String.valueOf(uri)) && (info == null ||
                "com.android.systemui.wallpapers.ImageWallpaper".equals(info.getComponent().getClassName()));
        boolean live = info != null && LIVE.equals(info.getComponent())
                && angleVideo((Bundle) getExtras.invoke(manager, COVER_HOME, 0));
        System.out.println("Cover home: " + (live ? "angle-aware stock video" : stock ? "original stock image" : "other wallpaper"));
        if (action.equals("status")) return;
        if ((!stock && !live)) throw new IllegalStateException("Wallpaper changed since setup; refusing to overwrite it");
        if (action.equals("apply") && live || action.equals("restore-stock") && stock) {
            System.out.println("Already configured; no change made"); return;
        }
        Context resources = context.createPackageContext(RESOURCE_PACKAGE, 0);
        int id = resources.getResources().getIdentifier("sub_wallpaper_002", "drawable", RESOURCE_PACKAGE);
        if (id == 0) throw new IllegalStateException("Original stock image unavailable; no change made");
        byte[] bytes;
        try (InputStream input = resources.getResources().openRawResource(id)) { bytes = input.readAllBytes(); }
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "wallpaper");
        Object remote = Class.forName("android.app.IWallpaperManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        Class<?> api = Class.forName("android.app.IWallpaperManager");
        // Samsung's public wrappers clear wallpaper snapshot history for shell callers.
        // Call the existing setter directly so unrelated wallpaper history is retained.
        if (action.equals("apply")) {
            Bundle inner = (Bundle) getExtras.invoke(manager, 5, 0);
            if (!angleVideo(inner)) throw new IllegalStateException("Expected inner angle-aware wallpaper unavailable");
            WallpaperDescription.Builder builder = new WallpaperDescription.Builder();
            builder.getClass().getMethod("setComponent", ComponentName.class).invoke(builder, LIVE);
            Method setter = null;
            for (Method method : api.getMethods())
                if (method.getName().equals("setWallpaperComponentChecked") && method.getParameterCount() == 5) setter = method;
            if (setter == null) throw new IllegalStateException("Expected wallpaper setter unavailable");
            setter.invoke(remote, builder.build(), context.getPackageName(), COVER_HOME, 0, inner);
            System.out.println("Applied angle-aware stock video to front HOME only");
        } else {
            Method setter = null;
            for (Method method : api.getMethods())
                if (method.getName().equals("setWallpaper") && method.getParameterCount() == 11) setter = method;
            if (setter == null) throw new IllegalStateException("Expected wallpaper restore setter unavailable");
            Bundle extras = new Bundle(); extras.putString("uri", STOCK_URI); extras.putBoolean("isPreloaded", true);
            ParcelFileDescriptor file = (ParcelFileDescriptor) setter.invoke(remote, null, context.getPackageName(),
                    new WallpaperDescription.Builder().build(), false, new Bundle(), COVER_HOME, null, 0, 0, true, extras);
            if (file == null) throw new IllegalStateException("Wallpaper restore did not return a writable file");
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(file)) { output.write(bytes); }
            System.out.println("Restored original stock image to front HOME only");
        }
        SystemClock.sleep(1000);
    }
}
