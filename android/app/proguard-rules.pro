# kotlinx.serialization：保留 @Serializable 类的序列化器（官方规则随库自带，这里兜底）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.terryxtl.tododrop.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit：ComponentDiscovery 通过反射 getDeclaredConstructor().newInstance() 创建各个 Registrar。
# AGP 9 默认的 R8 full mode 下，库自带的 -keep class 规则不再隐式保留无参构造函数，
# 构造函数被移除后 BarcodeScanning.getClient() 会抛异常，导致打开扫码页就闪退。
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# OkHttp 可选依赖
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
