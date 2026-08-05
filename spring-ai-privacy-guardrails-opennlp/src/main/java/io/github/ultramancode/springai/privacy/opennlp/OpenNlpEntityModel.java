package io.github.ultramancode.springai.privacy.opennlp;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import opennlp.tools.namefind.TokenNameFinderModel;

import java.util.Objects;

/**
 * Associates a canonical privacy entity type with one OpenNLP name-finder model.
 *
 * @param entityType exact canonical entity type assigned to every entity found by the model
 * @param model application-supplied OpenNLP name-finder model
 */
public record OpenNlpEntityModel(
        String entityType,
        TokenNameFinderModel model
) {

    /** Validates one entity-type and model association. */
    public OpenNlpEntityModel {
        entityType = EntityTypeRegistry.requireValidEntityType(entityType);
        model = Objects.requireNonNull(model, "model must not be null");
    }
}
