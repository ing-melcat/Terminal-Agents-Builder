# Keep Room entities and Hilt generated classes intact during R8 shrinking
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
