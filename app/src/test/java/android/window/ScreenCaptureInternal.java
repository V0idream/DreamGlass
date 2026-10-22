package android.window;
/** API-family fixture only; never packaged into the APK. */
public final class ScreenCaptureInternal {
    public static Object createSyncCaptureListener() { return new Object(); }
    public static class CaptureArgs {
        public static class Builder { public CaptureArgs build() { return new CaptureArgs(); } }
    }
    public static class DisplayCaptureArgs { public static class Builder {} }
}
