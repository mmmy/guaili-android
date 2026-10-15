# WorkManager persists these names across application updates.
-keepnames class * extends androidx.work.ListenableWorker
-keepattributes Signature,InnerClasses,EnclosingMethod

# Room 2.6 keeps database types, but full mode also needs the reflective constructor.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# Glance reconstructs widget callbacks from their recorded class names.
-keepclassmembers class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
