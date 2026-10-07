package io.github.ultramancode.springai.privacy.inspection.rules;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionIdentifiers;
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

    public RuleBasedContentInspector(String id, List<InspectionRule> rules) {
        this.id = InspectionIdentifiers.requireValid(id);
        this.rules = List.copyOf(rules);
        if (this.rules.isEmpty()
                || this.rules.stream().map(InspectionRule::id).distinct().count()
                        != this.rules.size()) {
            throw new IllegalArgumentException("Configure at least one uniquely identified rule");
        }
    }

    @Override
    public String inspectorId() {
        return id;
    }

    @Override
    public boolean requiresPrivacyProcessedContent() {
        return false;
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) {
        Set<String> completedSegmentIds = new HashSet<>();
        List<InspectionFinding> findings = new ArrayList<>();
        try {
            for (ContentSegment segment : request.segments()) {
                for (InspectionRule rule : rules) {
                    request.checkActive();
                    if (rule.matches(segment.text())) {
                        findings.add(
                                new InspectionFinding(
                                        segment.id(), rule.category(), rule.id(), null));
                    }
                }
                completedSegmentIds.add(segment.id());
            }
            return InspectionResult.completed(completedSegmentIds, findings);
        } catch (InspectionException ex) {
            return InspectionResult.failed(ex.failureCode(), completedSegmentIds, findings);
        }
    }
}
