package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on {@link RelayProperties} enforcing that
 * {@code relay.google-credentials[]} forms a unique, fully referenceable namespace: every
 * {@code type: GOOGLE} {@link RelayProperties.CalendarConfig#googleCredentialsId()} must
 * resolve to a configured {@link RelayProperties.GoogleCredentials#id()}, and no two
 * {@link RelayProperties.GoogleCredentials} entries may share an {@code id} -- see
 * {@code docs/features/relay-config-consolidation.md}.
 *
 * <p>Placed on {@link RelayProperties} itself, not on
 * {@link RelayProperties.CalendarConfig} (unlike {@link ConsistentCalendarSourceFields}):
 * resolving a reference needs both sides -- {@link RelayProperties#calendars()} and
 * {@link RelayProperties#googleCredentials()} -- simultaneously visible, which only
 * {@link RelayProperties} itself has. Deliberately a class-level Bean Validation
 * constraint with a dedicated {@link jakarta.validation.ConstraintValidator}, following
 * the same established pattern as {@link ConsistentCalendarSourceFields}: it participates
 * in the same validation pass as every other constraint on the tree, so every violation is
 * collected together into a single, complete
 * {@link jakarta.validation.ConstraintViolationException} report at application startup
 * instead of aborting on the first one found.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConsistentGoogleCredentialsReferencesValidator.class)
public @interface ConsistentGoogleCredentialsReferences {

    String message() default "google credentials reference inconsistent";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
