package io.github.viniciusssantos.flagforge.evaluation;

import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

public final class EvaluationApi {

    private EvaluationApi() {
    }

    public enum ValueType {
        BOOLEAN,
        STRING
    }

    public enum AttributeType {
        BOOLEAN,
        STRING,
        NUMBER
    }

    public enum Reason {
        TARGETING_MATCH,
        SPLIT,
        DEFAULT,
        DISABLED,
        PREREQUISITE_FAILED,
        STALE,
        ERROR
    }

    public enum ErrorCode {
        NONE,
        FLAG_NOT_FOUND,
        TYPE_MISMATCH,
        INVALID_REQUEST,
        INVALID_CONTEXT,
        INVALID_CONFIGURATION,
        SNAPSHOT_UNAVAILABLE
    }

    public record TypedAttribute(
            AttributeType type,
            JsonNode value) {

        public TypedAttribute {
            Objects.requireNonNull(type, "attribute type is required");
            Objects.requireNonNull(value, "attribute value is required");
        }
    }

    public record Request(
            ValueType type,
            JsonNode defaultValue,
            String targetingKey,
            Map<String, TypedAttribute> attributes) {

        public Request {
            Objects.requireNonNull(type, "requested type is required");
            Objects.requireNonNull(defaultValue, "default value is required");
            Objects.requireNonNull(targetingKey, "targeting key is required");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record ErrorMetadata(
            ErrorCode code,
            String message) {

        public ErrorMetadata {
            Objects.requireNonNull(code, "error code is required");
        }

        public static ErrorMetadata none() {
            return new ErrorMetadata(ErrorCode.NONE, null);
        }
    }

    public record Response(
            String flagKey,
            ValueType valueType,
            Object value,
            String variant,
            Reason reason,
            String sourceReason,
            String configurationVersion,
            ErrorMetadata error,
            String matchedRuleKey,
            String failedPrerequisiteKey,
            Integer bucket,
            boolean stale) {

        public Response {
            Objects.requireNonNull(flagKey, "flag key is required");
            Objects.requireNonNull(valueType, "value type is required");
            Objects.requireNonNull(value, "value is required");
            Objects.requireNonNull(reason, "reason is required");
            Objects.requireNonNull(error, "error metadata is required");
        }
    }

    public static final class EvaluationRequestException
            extends IllegalArgumentException {

        private final ErrorCode errorCode;

        public EvaluationRequestException(
                ErrorCode errorCode,
                String message) {
            super(message);
            this.errorCode = Objects.requireNonNull(
                    errorCode,
                    "error code is required");
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }
}
