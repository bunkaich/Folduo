package jp.bunkaich.sukashimotion;

import android.os.Build;
import java.util.Set;

/** Galaxy Z Fold7 models allowed to start display control. Keep in sync with tools/CoverWallpaperSetup.java. */
final class SupportedDevice {
    /** SM-F966Z: Japan (tested). SM-F966N: Korea, same hardware with a different region. */
    static final Set<String> MODELS=Set.of("SM-F966Z","SM-F966N");
    private SupportedDevice(){}
    static boolean isSupported(String model){return model!=null&&MODELS.contains(model);}
    static boolean current(){return isSupported(Build.MODEL);}
}
