package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on {@link RelayProperties.CalendarConfig} enforcing that the
 * right set of protocol-specific fields is non-blank for its configured
 * {@link RelayProperties.CalendarConfig.CalendarSourceType}: the CalDAV-specific fields
 * ({@code caldavUrl}/{@code caldavUsername}/{@code caldavPassword}) when {@code type ==
 * CALDAV}, or the Google-specific fields ({@code googleCalendarId}/
 * {@code googleCredentialsId}) when {@code type == GOOGLE} -- see
 * {@code docs/features/google-calendar-integration.md}'s Design-Entscheidung 1 and
 * {@code docs/features/relay-config-consolidation.md}. This constraint only checks that
 * {@code googleCredentialsId} is non-blank -- whether it actually resolves to a
 * configured {@code relay.google-credentials[].id} is checked separately by
 * {@link ConsistentGoogleCredentialsReferences}, which needs sibling-list visibility this
 * per-{@link RelayProperties.CalendarConfig} constraint does not have.
 *
 * <p>Deliberately a class-level Bean Validation constraint rather than a manual check in
 * {@link RelayProperties.CalendarConfig}'s compact constructor: a compact-constructor
 * check throws and aborts object construction on the very first violation it finds, before
 * Spring's own {@code @Valid List<CalendarConfig>} tree validation (the plain
 * {@code @NotBlank} fields, recursively) even runs -- a deployer with several simultaneous
 * configuration mistakes would only ever see the first one reported. A
 * {@link jakarta.validation.ConstraintValidator} instead participates in the same
 * validation pass as every other constraint on the record, so every violation -- this one
 * included -- is collected together into a single, complete
 * {@link jakarta.validation.ConstraintViolationException} report at application startup,
 * consistent with this project's fail-fast-with-full-report philosophy for configuration
 * errors.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConsistentCalendarSourceFieldsValidator.class)
public @interface ConsistentCalendarSourceFields {

    String message() default "calendar config fields inconsistent with its type";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
