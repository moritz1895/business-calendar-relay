package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;

/**
 * Persists {@link ZonedDateTime} as its full ISO-8601 representation, including the
 * named {@code ZoneId} (e.g. {@code Europe/Berlin}), not just a numeric UTC offset.
 *
 * <p>Hibernate's default {@code ZonedDateTime} column mapping round-trips through a
 * plain SQL timestamp/offset type and can normalize a named zone down to a fixed
 * offset. That would silently break {@link RelayState}'s change-detection, which relies
 * on {@link ZonedDateTime#equals(Object)} comparing the same {@code ZoneId} instance,
 * not just the same instant. Storing the formatted string sidesteps that entirely.
 */
@Converter
final class ZonedDateTimeStringConverter implements AttributeConverter<ZonedDateTime, String> {

    @Override
    public String convertToDatabaseColumn(ZonedDateTime attribute) {
        return attribute.format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    @Override
    public ZonedDateTime convertToEntityAttribute(String dbData) {
        return ZonedDateTime.parse(dbData, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }
}
