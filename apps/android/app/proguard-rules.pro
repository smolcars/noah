# BackupArchive admits only stored/deflate ZIP entries; these optional codecs are not shipped.
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream

# WorkManager 2.9 and its Room 2.5 keep these classes but not the no-arg constructors they call reflectively.
# R8 full mode strips them: WorkDatabase_Impl crashes app start, and the input mergers fail all one-time work.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.InputMerger { <init>(); }
