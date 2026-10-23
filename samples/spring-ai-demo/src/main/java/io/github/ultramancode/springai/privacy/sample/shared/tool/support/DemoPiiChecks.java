package io.github.ultramancode.springai.privacy.sample.shared.tool.support;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolArgumentEvidence;

import java.util.List;
import java.util.regex.Pattern;

// PII checks for the fixed synthetic customer data.
public final class DemoPiiChecks {

    private static final CustomerDemoData CUSTOMER_DATA = CustomerDemoData.DEFAULT;
    private static final List<String> DENIED_TOOL_VALUES = CUSTOMER_DATA.deniedToolValues();
    private static final List<String> ALLOWED_TOOL_VALUES = CUSTOMER_DATA.allowedToolValues();

    public static final Pattern EMPLOYEE_ID_TOKEN_PATTERN = OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID");
    public static final Pattern EMAIL_TOKEN_PATTERN = OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS");
    public static final Pattern PHONE_TOKEN_PATTERN = OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER");
    public static final Pattern CUSTOMER_ID_TOKEN_PATTERN = OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID");

    private DemoPiiChecks() {
    }

    public static ToolArgumentEvidence checkToolArguments(CustomerLookupArguments arguments) {
        String receivedValues = String.join("\n",
                arguments.employeeId(), arguments.email(), arguments.phone(), arguments.customerId());
        int deniedRawValueCount = countContainedValues(receivedValues, DENIED_TOOL_VALUES);
        int allowedRawValueCount = countContainedValues(receivedValues, ALLOWED_TOOL_VALUES);
        boolean deniedFieldsTokenized = matchesToken(arguments.employeeId(), EMPLOYEE_ID_TOKEN_PATTERN)
                && matchesToken(arguments.email(), EMAIL_TOKEN_PATTERN)
                && matchesToken(arguments.phone(), PHONE_TOKEN_PATTERN);
        boolean customerIdRestored = CUSTOMER_DATA.customerId().equals(arguments.customerId());
        boolean receivedOnlyAllowedOriginals = deniedRawValueCount == 0
                && deniedFieldsTokenized
                && customerIdRestored;
        return new ToolArgumentEvidence(deniedRawValueCount, allowedRawValueCount, receivedOnlyAllowedOriginals);
    }

    // Count matching demo data values, not every occurrence in the text.
    public static int countContainedValues(String text, List<String> values) {
        if (text == null) {
            return 0;
        }
        return Math.toIntExact(values.stream().filter(text::contains).count());
    }

    private static boolean matchesToken(String text, Pattern tokenPattern) {
        if (text == null) {
            return false;
        }
        return tokenPattern.matcher(text).matches();
    }
}
