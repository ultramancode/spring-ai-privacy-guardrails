package io.github.ultramancode.springai.privacy.benchmarks;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Measures deterministic regex analysis and request-scoped core tokenization. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class CorePrivacyBenchmark {

    @Param({"1024", "16384"})
    public int payloadCharacters;

    private PrivacyService privacyService;
    private String payload;

    @Setup(Level.Trial)
    public void setUp() {
        this.privacyService = benchmarkPrivacyService();
        this.payload = payload(payloadCharacters);
    }

    @Benchmark
    public void regexAnalysis(Blackhole blackhole) {
        blackhole.consume(this.privacyService.analyze(this.payload));
    }

    @Benchmark
    public void tokenizeOneRequest(Blackhole blackhole) {
        try (PrivacySession session = this.privacyService.openSession()) {
            blackhole.consume(this.privacyService.tokenize(session.handle(), this.payload));
        }
    }

    @TearDown(Level.Iteration)
    public void assertNoSessionLeak() {
        if (this.privacyService.activeSessionCount() != 0) {
            throw new IllegalStateException("Benchmark leaked a privacy session");
        }
    }

    static PrivacyService benchmarkPrivacyService() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("CUSTOMER_ID", "\\bCUST-\\d{4}\\b", 0.99, 0),
                new RegexPiiRule(
                        "EMAIL_ADDRESS",
                        "\\b[A-Za-z0-9._%+-]+@example\\.test\\b",
                        0.99,
                        0
                )
        ));
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private static String payload(int targetCharacters) {
        StringBuilder payload = new StringBuilder(targetCharacters);
        int index = 0;
        while (true) {
            String record = String.format(
                    Locale.ROOT,
                    "record-%04d customer CUST-%04d owner user%04d@example.test status active. ",
                    index,
                    index % 10_000,
                    index
            );
            if (payload.length() + record.length() > targetCharacters) {
                break;
            }
            payload.append(record);
            index++;
        }
        while (payload.length() < targetCharacters) {
            payload.append('x');
        }
        return payload.toString();
    }
}
