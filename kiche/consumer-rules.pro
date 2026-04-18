# Ensure native method calls remain intact after obfuscation
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep our core JNI implementation classes from being obfuscated
-keep class eu.buney.kiche.**
