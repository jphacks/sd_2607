# Jackson finds its std (de)serializers via @JacksonStdImpl; R8 drops that annotation from unkept classes,
# so convertValue(..., Map) on nested objects NPEs and Author/DID init fails (stuck at 準備中).
-keep class com.fasterxml.jackson.** { *; }
# Optional JDK-only types; Jackson loads them reflectively and tolerates their absence.
-dontwarn java.beans.**
