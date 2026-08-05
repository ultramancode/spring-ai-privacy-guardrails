package io.github.ultramancode.springai.privacy.benchmarks;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Measures opaque-token restoration as the number of distinct request mappings grows. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class HighCardinalityPrivacyBenchmark {

    @Param({"100", "1000", "5000"})
    public int mappings;

    private PrivacyService privacyService;
    private PrivacySession session;
    private String tokenizedText;

    @Setup(Level.Trial)
    public void setUp() {
        this.privacyService = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        this.session = this.privacyService.openSession();

        StringBuilder source = new StringBuilder();
        List<PiiSpan> spans = new ArrayList<>();
        for (int index = 0; index < this.mappings; index++) {
            if (index > 0) {
                source.append(' ');
            }
            int start = source.length();
            source.append(String.format(Locale.ROOT, "id%05d", index));
            spans.add(new PiiSpan("CUSTOMER_ID", start, source.length(), 1.0));
        }
        this.tokenizedText = this.privacyService.tokenize(
                this.session.handle(),
                source.toString(),
                spans
        );
    }

    @Benchmark
    public void detokenizeDistinctMappings(Blackhole blackhole) {
        blackhole.consume(this.privacyService.detokenize(
                this.session.handle(),
                this.tokenizedText
        ));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        this.session.close();
        if (this.privacyService.activeSessionCount() != 0) {
            throw new IllegalStateException("Benchmark leaked a privacy session");
        }
    }
}
