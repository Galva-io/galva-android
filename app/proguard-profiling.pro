# Profiling builds: shrunk, non-debuggable release code with readable names in traces and profilers.
-dontobfuscate
# No class merging / inlining either, so CPU is charged to the method that spent it.
-dontoptimize
