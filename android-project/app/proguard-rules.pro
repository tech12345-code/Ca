# Keep Room entities, Firebase Firestore POJOs, and ML Kit models
-keepclassmembers class com.smartexpense.manager.data.local.** { *; }
-keepclassmembers class com.smartexpense.manager.domain.model.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
-dontwarn com.google.mlkit.**
