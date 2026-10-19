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

# log4j（POI 5.x 的传递依赖）里的可选 OSGi / bnd 集成，App 用不到（R8 missing_rules 生成）
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.framework.**
