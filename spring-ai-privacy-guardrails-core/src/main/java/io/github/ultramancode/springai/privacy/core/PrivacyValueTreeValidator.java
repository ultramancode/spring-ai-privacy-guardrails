package io.github.ultramancode.springai.privacy.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and copies values accepted by the direct value-tree API. */
final class PrivacyValueTreeValidator {

    private static final int MAX_NUMBER_BIT_LENGTH = BigInteger.TEN
            .pow(PrivacyService.MAX_VALUE_TREE_NUMBER_CHARACTERS)
            .bitLength();

    private final PrivacyPhase phase;
    private final Set<Object> activeContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    private int nodeCount;
    private long inputCharacters;

    private PrivacyValueTreeValidator(PrivacyPhase phase) {
        this.phase = phase;
    }

    static Object validateAndCopy(Object valueTree, PrivacyPhase phase) {
        return new PrivacyValueTreeValidator(phase).validateValue(valueTree, 0);
    }

    static boolean isSupportedNumber(Number number) {
        Class<?> numberType = number.getClass();
        if (numberType == Double.class) {
            return Double.isFinite(number.doubleValue());
        }
        if (numberType == Float.class) {
            return Float.isFinite(number.floatValue());
        }
        return numberType == Byte.class
                || numberType == Short.class
                || numberType == Integer.class
                || numberType == Long.class
                || numberType == BigInteger.class
                || numberType == BigDecimal.class;
    }

    private Object validateValue(Object value, int containerDepth) {
        acceptNode();
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            acceptString(text);
            return text;
        }
        if (value instanceof Number number && isSupportedNumber(number)) {
            acceptNumber(number);
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            return validateMap(map, containerDepth + 1);
        }
        if (value instanceof List<?> list) {
            return validateList(list, containerDepth + 1);
        }
        throw invalidTree("Value tree contains an unsupported Java value");
    }

    private Map<String, Object> validateMap(Map<?, ?> map, int depth) {
        requireDepth(depth);
        enterContainer(map);
        try {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                acceptNode();
                if (!(entry.getKey() instanceof String key)) {
                    throw invalidTree("Value tree map keys must be strings");
                }
                acceptString(key);
                copy.put(key, validateValue(entry.getValue(), depth));
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            this.activeContainers.remove(map);
        }
    }

    private List<Object> validateList(List<?> list, int depth) {
        requireDepth(depth);
        enterContainer(list);
        try {
            List<Object> copy = new ArrayList<>();
            for (Object value : list) {
                copy.add(validateValue(value, depth));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            this.activeContainers.remove(list);
        }
    }

    private void acceptNode() {
        if (++this.nodeCount > PrivacyService.MAX_VALUE_TREE_NODES) {
            throw limitExceeded("Value tree exceeded the bounded node-count limit");
        }
    }

    private void acceptString(String text) {
        if (text.length() > PrivacyService.MAX_VALUE_TREE_STRING_CHARACTERS) {
            throw limitExceeded("Value tree exceeded the bounded string-value limit");
        }
        acceptInputCharacters(text.length());
    }

    private void acceptNumber(Number number) {
        if ((number instanceof BigInteger integer && integer.bitLength() > MAX_NUMBER_BIT_LENGTH)
                || (number instanceof BigDecimal decimal
                    && decimal.unscaledValue().bitLength() > MAX_NUMBER_BIT_LENGTH)) {
            throw limitExceeded("Value tree exceeded the bounded number-representation limit");
        }
        String representation = number.toString();
        if (representation.length() > PrivacyService.MAX_VALUE_TREE_NUMBER_CHARACTERS) {
            throw limitExceeded("Value tree exceeded the bounded number-representation limit");
        }
        acceptInputCharacters(representation.length());
    }

    private void acceptInputCharacters(int additionalCharacters) {
        this.inputCharacters += additionalCharacters;
        if (this.inputCharacters > PrivacyService.MAX_VALUE_TREE_INPUT_CHARACTERS) {
            throw limitExceeded("Value tree exceeded the bounded input-content limit");
        }
    }

    private void requireDepth(int depth) {
        if (depth > PrivacyService.MAX_VALUE_TREE_DEPTH) {
            throw limitExceeded("Value tree exceeded the bounded nesting-depth limit");
        }
    }

    private void enterContainer(Object container) {
        if (!this.activeContainers.add(container)) {
            throw invalidTree("Value tree contains a reference cycle");
        }
    }

    private PrivacyGuardrailException invalidTree(String message) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                this.phase,
                message
        );
    }

    private PrivacyGuardrailException limitExceeded(String message) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                this.phase,
                message
        );
    }
}
