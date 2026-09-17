package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Thread-safe provider contract. Implementations must bound work, honor the shared
 * deadline/interruption, and never retain or log content. Arbitrary synchronous
 * application code cannot be forcibly cancelled by this interface.
 */
@FunctionalInterface
public interface ContentInspector {

    /** Inspects the supplied segments within the request's limits and shared deadline. */
    InspectionResult inspect(InspectionRequest request);

    /** Stable diagnostic identifier, unique within an inspection service. */
    default String providerId() {
        return "custom";
    }

    /** Conservative default for custom implementations whose execution boundary is unknown. */
    default boolean requiresProtectedContent() {
        return true;
    }
}
