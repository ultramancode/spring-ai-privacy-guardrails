package io.github.ultramancode.springai.privacy.inspection.rules;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inspects each text segment using the configured rules in order. */
public final class RuleBasedContentInspector implements ContentInspector {

    private final String id;
    private final List<InspectionRule> rules;

    public RuleBasedContentInspector(List<InspectionRule> rules) {
        this("rules", rules);
    }

    public RuleBasedContentInspector(String id, List<InspectionRule> rules) {
        this.id = ContentSegment.requireIdentifier(id);
        this.rules = List.copyOf(rules);
        if (this.rules.isEmpty()
                || this.rules.size() > 256
                || this.rules.stream().map(InspectionRule::id).distinct().count()
                        != this.rules.size()) {
            throw new IllegalArgumentException("Configure 1-256 uniquely identified rules");
        }
    }

    @Override
    public String providerId() {
        return id;
    }

    @Override
    public boolean requiresProtectedContent() {
        return false;
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) {
        Set<String> inspectedSegmentIds = new HashSet<>();
        List<InspectionFinding> findings = new ArrayList<>();
        try {
            for (ContentSegment segment : request.segments()) {
                for (InspectionRule rule : rules) {
                    request.checkActive();
                    if (rule.matches(segment.text())) {
                        if (findings.size() >= 10_000) {
                            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
                        }
                        findings.add(
                                new InspectionFinding(
                                        segment.id(), rule.category(), rule.id(), null));
                    }
                }
                inspectedSegmentIds.add(segment.id());
            }
            request.checkActive();
            return InspectionResult.completed(inspectedSegmentIds, findings);
        } catch (InspectionException ex) {
            return InspectionResult.failed(ex.failure(), inspectedSegmentIds, findings);
        }
    }
}
