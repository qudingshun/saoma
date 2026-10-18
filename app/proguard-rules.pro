# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# POI（仅规则库「规则源导入」解析 .xlsx/.xls 用；工单 Excel 导入仍保持下架）
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.commons.**
-dontwarn java.awt.**
-keep class org.openxmlformats.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.openxmlformats.**
-dontwarn org.apache.xmlbeans.**
