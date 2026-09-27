package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class SupportedDeviceTest {
    @Test public void acceptsJapaneseFold7(){assertTrue(SupportedDevice.isSupported("SM-F966Z"));}
    @Test public void acceptsKoreanFold7(){assertTrue(SupportedDevice.isSupported("SM-F966N"));}
    @Test public void rejectsOtherModels(){assertFalse(SupportedDevice.isSupported("SM-F966B"));assertFalse(SupportedDevice.isSupported("SM-F956N"));assertFalse(SupportedDevice.isSupported("sm-f966n"));}
    @Test public void rejectsMissingModel(){assertFalse(SupportedDevice.isSupported(null));assertFalse(SupportedDevice.isSupported(""));}
}
