package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig;
import org.jspecify.annotations.Nullable;

/**
 * Validates {@link ConsistentCalendarSourceFields} -- see that annotation's Javadoc for the
 * full rationale. A {@code null} {@link CalendarConfig} or a {@code null} {@code type} is
 * considered valid here: those are already reported by {@code @NotNull} on the record
 * component itself, and Bean Validation convention leaves null-handling to a dedicated
 * {@code @NotNull} constraint rather than duplicating it in every other constraint.
 */
public class ConsistentCalendarSourceFieldsValidator
        implements ConstraintValidator<ConsistentCalendarSourceFields, CalendarConfig> {

    @Override
    public boolean isValid(@Nullable CalendarConfig config, ConstraintValidatorContext context) {
        if (config == null || config.type() == null) {
            return true;
        }
        return switch (config.type()) {
            case CALDAV -> isNotBlank(config.caldavUrl())
                    && isNotBlank(config.caldavUsername())
                    && isNotBlank(config.caldavPassword());
            case GOOGLE -> isNotBlank(config.googleCalendarId())
                    && isNotBlank(config.googleCredentialsId());
        };
    }

    private boolean isNotBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
