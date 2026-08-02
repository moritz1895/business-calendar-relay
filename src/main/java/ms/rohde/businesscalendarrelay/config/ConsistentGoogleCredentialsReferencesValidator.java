package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig.CalendarSourceType;
import org.jspecify.annotations.Nullable;

/**
 * Validates {@link ConsistentGoogleCredentialsReferences} -- see that annotation's Javadoc
 * for the full rationale. A {@code null} {@link RelayProperties} is considered valid here,
 * following the same convention as {@link ConsistentCalendarSourceFieldsValidator}: a
 * {@code null} record is already reported by a dedicated {@code @NotNull} constraint, not
 * duplicated in every other constraint.
 */
public class ConsistentGoogleCredentialsReferencesValidator
        implements ConstraintValidator<ConsistentGoogleCredentialsReferences, RelayProperties> {

    @Override
    public boolean isValid(@Nullable RelayProperties properties, ConstraintValidatorContext context) {
        if (properties == null) {
            return true;
        }
        var credentialIds = properties.googleCredentials().stream()
                .map(RelayProperties.GoogleCredentials::id)
                .toList();
        var duplicateIds = findDuplicates(credentialIds);
        var unresolvedCalendars = properties.calendars().stream()
                .filter(calendar -> calendar.type() == CalendarSourceType.GOOGLE)
                .filter(calendar -> calendar.googleCredentialsId() != null)
                .filter(calendar -> !credentialIds.contains(calendar.googleCredentialsId()))
                .toList();

        if (duplicateIds.isEmpty() && unresolvedCalendars.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        duplicateIds.forEach(duplicateId -> context.buildConstraintViolationWithTemplate(
                        "relay.google-credentials contains duplicate id '" + duplicateId + "'")
                .addConstraintViolation());
        unresolvedCalendars.forEach(calendar -> context.buildConstraintViolationWithTemplate(
                        "relay.calendars[" + calendar.id() + "].google-credentials-id '"
                                + calendar.googleCredentialsId() + "' matches no configured "
                                + "relay.google-credentials[].id")
                .addConstraintViolation());
        return false;
    }

    private static Set<String> findDuplicates(List<String> ids) {
        var seen = new HashSet<String>();
        var duplicates = new HashSet<String>();
        for (var id : ids) {
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        return duplicates;
    }
}
