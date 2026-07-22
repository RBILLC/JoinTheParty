# JNI: the native side calls SyncCore.onNativeEvent reflectively via method ID
# resolved at create time; keep the class shape stable.
-keepclassmembers class com.jointheparty.app.core.SyncCore {
    void onNativeEvent(int, double, double, double, int, int, long);
}
