# ============================================================================
# Galva Android SDK — Consumer ProGuard Rules
# ============================================================================
# Bundled into the AAR via consumerProguardFiles. Auto-applied to consumer apps.
# Module namespaces: io.galva.common, io.galva.identity, io.galva.operation,
#                    io.galva.network, io.galva.iam, io.galva.billing, io.galva.sdk
# ============================================================================

# ============================================================================

# KOTLINX SERIALIZATION — REQUIRED FOR ENCODING/DECODING JSON
# Without these, json.encodeToString() and json.decodeFromString() will fail
# at runtime with "Serializer for class ... is not found".
# ============================================================================

# Keep generated $$serializer classes for SDK packages
-keep class io.galva.**$$serializer { *; }

# Keep generated serializers ($$serializer classes) for all SDK packages
-keepclasseswithmembers class io.galva.**$$serializer {
    *;
}

# Keep Companion objects of @Serializable classes (for .serializer() lookup)
-if @kotlinx.serialization.Serializable class io.galva.**
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}

-keepclassmembers class io.galva.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable classes themselves with their fields and constructors
# Note: R8 preserves annotations on classes that are kept via these rules,
# so we don't need a global -keepattributes *Annotation*
-keepclasseswithmembers @kotlinx.serialization.Serializable class io.galva.** {
    <fields>;
    <init>(...);
}

# Keep sealed class subclasses for polymorphic serialization
-keep class io.galva.**$* extends io.galva.** {
    <init>(...);
}

# Keep enums for serialization (values() / valueOf())
-keepclassmembers enum io.galva.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep descriptor classes referenced at runtime
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ============================================================================
# PUBLIC SDK API — KEEP ALL TYPES UNDER io.galva.*
# ============================================================================
-keep public class io.galva.common.** { *; }
-keep public interface io.galva.common.** { *; }
-keep public enum io.galva.common.** { *; }

-keep public class io.galva.identity.** { *; }
-keep public interface io.galva.identity.** { *; }
-keep public enum io.galva.identity.** { *; }

-keep public class io.galva.operation.** { *; }
-keep public interface io.galva.operation.** { *; }
-keep public enum io.galva.operation.** { *; }

-keep public class io.galva.network.request.** { *; }
-keep public class io.galva.network.response.** { *; }
-keep public interface io.galva.network.** { *; }
-keep public enum io.galva.network.** { *; }

-keep public class io.galva.iam.** { *; }
-keep public interface io.galva.iam.** { *; }
-keep public enum io.galva.iam.** { *; }

-keep public class io.galva.billing.** { *; }
-keep public interface io.galva.billing.** { *; }
-keep public enum io.galva.billing.** { *; }

-keep public class io.galva.sdk.Galva { *; }
-keep public class io.galva.sdk.impl.** { *; }
-keep public class io.galva.sdk.Galva$Companion { *; }
-keep public class io.galva.core.protocol.Configuration { *; }


# Identity module
-keep public interface io.galva.identity.IdentityManager { *; }
-keep public class io.galva.core.protocol.identity.ProfileProperty { *; }
