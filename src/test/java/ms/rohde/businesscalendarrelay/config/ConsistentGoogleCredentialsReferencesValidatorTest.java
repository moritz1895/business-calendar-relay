package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig.CalendarSourceType;
import ms.rohde.businesscalendarrelay.config.RelayProperties.GoogleCredentials;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ConsistentGoogleCredentialsReferencesValidator} through a real
 * {@link Validator} (not the {@link jakarta.validation.ConstraintValidator} directly) so
 * the {@link ConsistentGoogleCredentialsReferences} annotation wiring is exercised too,
 * mirroring {@link ConsistentCalendarSourceFieldsValidatorTest}.
 */
class ConsistentGoogleCredentialsReferencesValidatorTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final RelayProperties.InitializationProperties DEFAULT_INITIALIZATION =
            new RelayProperties.InitializationProperties(5, Duration.ofHours(1));

    private static RelayProperties relayProperties(
            List<CalendarConfig> calendars, List<GoogleCredentials> googleCredentials) {
        return new RelayProperties(
                Duration.ofMinutes(5),
                calendars,
                Period.ofMonths(6),
                DEFAULT_INITIALIZATION,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                googleCredentials);
    }

    private static CalendarConfig googleCalendar(String id, String googleCredentialsId) {
        return new CalendarConfig(
                id, CalendarSourceType.GOOGLE, null, null, null, "someone@gmail.com", googleCredentialsId, true);
    }

    private static CalendarConfig caldavCalendar(String id) {
        return new CalendarConfig(
                id, CalendarSourceType.CALDAV, "https://caldav.example.com/", "user", "password", null, null, true);
    }

    @Test
    void validate_givenEmptyGoogleCredentialsAndNoGoogleCalendars_thenNoViolation() {
        var violations = VALIDATOR.validate(relayProperties(List.of(caldavCalendar("caldav-one")), List.of()));

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_givenGoogleCredentialsIdResolvesToConfiguredEntry_thenNoViolation() {
        var relayProperties = relayProperties(
                List.of(googleCalendar("google-one", "personal-google-account")),
                List.of(new GoogleCredentials("personal-google-account", "client-id", "client-secret", "refresh-token")));

        var violations = VALIDATOR.validate(relayProperties);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_givenGoogleCredentialsIdMatchesNoConfiguredEntry_thenReportsViolation() {
        var relayProperties = relayProperties(
                List.of(googleCalendar("google-one", "typo-account")),
                List.of(new GoogleCredentials("personal-google-account", "client-id", "client-secret", "refresh-token")));

        var violations = VALIDATOR.validate(relayProperties);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validate_givenDuplicateGoogleCredentialsIds_thenReportsViolation() {
        var relayProperties = relayProperties(
                List.of(),
                List.of(
                        new GoogleCredentials("duplicate-id", "client-id-1", "client-secret-1", "refresh-token-1"),
                        new GoogleCredentials("duplicate-id", "client-id-2", "client-secret-2", "refresh-token-2")));

        var violations = VALIDATOR.validate(relayProperties);

        assertThat(violations).isNotEmpty();
    }

    /**
     * A defined-but-unreferenced credentials entry is a legitimate intermediate state
     * while a deployer is configuring several calendars step by step -- only an
     * unresolvable reference in the other direction is an error, see
     * {@code docs/features/relay-config-consolidation.md} "Weitere Entscheidungen".
     */
    @Test
    void validate_givenUnreferencedGoogleCredentialsEntry_thenNoViolation() {
        var relayProperties = relayProperties(
                List.of(),
                List.of(new GoogleCredentials("unused-account", "client-id", "client-secret", "refresh-token")));

        var violations = VALIDATOR.validate(relayProperties);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_givenSharedGoogleCredentialsIdAcrossTwoCalendars_thenNoViolation() {
        var relayProperties = relayProperties(
                List.of(
                        googleCalendar("google-primary", "personal-google-account"),
                        googleCalendar("google-secondary", "personal-google-account")),
                List.of(new GoogleCredentials("personal-google-account", "client-id", "client-secret", "refresh-token")));

        var violations = VALIDATOR.validate(relayProperties);

        assertThat(violations).isEmpty();
    }
}
