package io.github.ultramancode.springai.privacy.benchmarks;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextFactoryBenchmarkAccess;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Measures protect, scoped disclosure, delegate execution, and result retokenization together. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class ToolBoundaryBenchmark {

    @Param({"1", "10", "100"})
    public int entityPairs;

    private PrivacyService privacyService;
    private ToolCallback protectedTool;
    private String toolInput;

    @Setup(Level.Trial)
    public void setUp() {
        this.privacyService = CorePrivacyBenchmark.benchmarkPrivacyService();
        this.protectedTool = new PrivacyToolCallbackFactory(
                this.privacyService,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", List.of("CUSTOMER_ID")))
        ).wrap(delegate());
        this.toolInput = toolInput(this.entityPairs);

        try (PrivacySession session = this.privacyService.openSession()) {
            String protectedResult = this.protectedTool.call(
                    this.toolInput,
                    PrivacyToolContextFactoryBenchmarkAccess.create(session.handle())
            );
            if (protectedResult.contains("CUST-9000") || protectedResult.contains("result@example.test")) {
                throw new IllegalStateException("Benchmark setup crossed the protected result boundary");
            }
        }
    }

    @Benchmark
    public void scopedToolRoundTrip(Blackhole blackhole) {
        try (PrivacySession session = this.privacyService.openSession()) {
            blackhole.consume(this.protectedTool.call(
                    this.toolInput,
                    PrivacyToolContextFactoryBenchmarkAccess.create(session.handle())
            ));
        }
    }

    @TearDown(Level.Iteration)
    public void assertNoSessionLeak() {
        if (this.privacyService.activeSessionCount() != 0) {
            throw new IllegalStateException("Benchmark leaked a privacy session");
        }
    }

    private static ToolCallback delegate() {
        // Keep argument binding out of the measurement so it isolates the privacy boundary cost.
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Returns a synthetic customer record")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "processed CUST-9000 for result@example.test";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private static String toolInput(int entityPairs) {
        StringBuilder input = new StringBuilder("{\"records\":[");
        for (int index = 0; index < entityPairs; index++) {
            if (index > 0) {
                input.append(',');
            }
            input.append(String.format(
                    Locale.ROOT,
                    "{\"customerId\":\"CUST-%04d\",\"email\":\"user%04d@example.test\"}",
                    index,
                    index
            ));
        }
        return input.append("]}").toString();
    }
}
