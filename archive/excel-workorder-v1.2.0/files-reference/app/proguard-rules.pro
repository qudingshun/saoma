# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# POI
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.commons.**
-dontwarn java.awt.**
-keep class org.openxmlformats.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.openxmlformats.**
-dontwarn org.apache.xmlbeans.**
