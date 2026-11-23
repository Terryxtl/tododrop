# kotlinx.serialization：保留 @Serializable 类的序列化器（官方规则随库自带，这里兜底）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.terryxtl.tododrop.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp 可选依赖
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
