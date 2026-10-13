# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# Filament (JNI-bound classes and gltfio)
-keep class com.google.android.filament.** { *; }

# Files tab terminal (Termux emulator): JNI.createSubprocess and friends are found by name from libtermux.so
-keep class com.termux.terminal.JNI { *; }
