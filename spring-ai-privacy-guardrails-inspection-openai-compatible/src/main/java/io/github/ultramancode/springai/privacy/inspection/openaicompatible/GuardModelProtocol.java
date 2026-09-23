package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.Map;

/** Internal model semantics. HTTP compatibility alone never selects a protocol. */
interface GuardModelProtocol {

    Map<String, Object> request(String model, String text);

    InspectionFinding parse(String segmentId, String output);

    boolean acceptsLengthFinish();
}
