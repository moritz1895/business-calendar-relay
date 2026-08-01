package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig.CalendarSourceType;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ConsistentCalendarSourceFieldsValidator} through a real {@link Validator}
 * (not the {@link jakarta.validation.ConstraintValidator} directly) so the
 * {@link ConsistentCalendarSourceFields} annotation wiring is exercised too, mirroring how
 * Spring actually invokes it during {@code @ConfigurationProperties} binding.
 */
class ConsistentCalendarSourceFieldsValidatorTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static CalendarConfig caldavConfig(String caldavUrl, String caldavUsername, String caldavPassword) {
        return new CalendarConfig(
                "calendar-id",
                CalendarSourceType.CALDAV,
                caldavUrl,
                caldavUsername,
                caldavPassword,
                null,
                null,
                null,
                null,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                true);
    }

    private static CalendarConfig googleConfig(
            String googleCalendarId, String googleClientId, String googleClientSecret, String googleRefreshToken) {
        return new CalendarConfig(
                "calendar-id",
                CalendarSourceType.GOOGLE,
                null,
                null,
                null,
                googleCalendarId,
                googleClientId,
                googleClientSecret,
                googleRefreshToken,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                true);
    }

    @Test
    void validate_givenCaldavTypeWithAllCaldavFieldsPresent_thenNoViolation() {
        var violations = VALIDATOR.validate(caldavConfig("https://caldav.example.com/", "user", "password"));

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_givenCaldavTypeMissingCaldavUrl_thenReportsViolation() {
        var violations = VALIDATOR.validate(caldavConfig(null, "user", "password"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validate_givenCaldavTypeWithBlankCaldavUsername_thenReportsViolation() {
        var violations = VALIDATOR.validate(caldavConfig("https://caldav.example.com/", "   ", "password"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validate_givenCaldavTypeMissingAllCaldavFields_thenReportsSingleViolationNotAborting() {
        var violations = VALIDATOR.validate(caldavConfig(null, null, null));

        assertThat(violations).hasSize(1);
    }

    @Test
    void validate_givenGoogleTypeWithAllGoogleFieldsPresent_thenNoViolation() {
        var violations = VALIDATOR.validate(googleConfig("someone@gmail.com", "client-id", "client-secret", "refresh-token"));

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_givenGoogleTypeMissingGoogleRefreshToken_thenReportsViolation() {
        var violations = VALIDATOR.validate(googleConfig("someone@gmail.com", "client-id", "client-secret", null));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validate_givenGoogleTypeWithCaldavFieldsInsteadOfGoogleFields_thenReportsViolation() {
        var violations = VALIDATOR.validate(new CalendarConfig(
                "calendar-id",
                CalendarSourceType.GOOGLE,
                "https://caldav.example.com/",
                "user",
                "password",
                null,
                null,
                null,
                null,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                true));

        assertThat(violations).isNotEmpty();
    }
}
