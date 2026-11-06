package ch.bbw.trips.dtos;

import ch.bbw.trips.repo.Employee;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record FlightDto(
        Long id,
        Long number,
        String cityFrom,
        String cityTo,
        LocalDateTime flightDate,
        Employee employee
) {
    public FlightDto(Long id, Long number, String from, String to) {
        // must delegate to the canonical constructor
        this(id, number, from, to, null, null);
    }
    // Lombok will generate the getters, equals, hashCode, toString methods
    // No need for @Data annotation as we are using record which is immutable) {

}
