# ProGuard / R8 rules for Terminal Agents Builder (RUR Edition)
# Preserves upstream Termux JNI entrypoints + com.termux.custom.* Compose/enum/reflection contracts.

-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Preserve Termux native JNI bridges and terminal classes
-keep class com.termux.terminal.JNI { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve custom workstation enums & data models used with rememberSaveable / reflection
-keep class com.termux.custom.provisioning.CustomBootstrapProvisioner { *; }
-keep class com.termux.custom.guardian.ProcessGuardianService { *; }
-keep class com.termux.custom.ui.WorkstationActivity { *; }
-keepclassmembers enum com.termux.custom.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Suppress harmless warnings from optional JVM/Guava references
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
