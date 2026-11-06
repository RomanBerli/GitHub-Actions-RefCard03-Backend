package ch.bbw.trips.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Builder
public record TripDto(
        Long id,
        String title,
        String description,
        LocalDateTime startTrip,
        LocalDateTime endTrip
) {// what is in the body of the record is the constructor
    // Lombok will generate the getters, equals, hashCode, toString methods
    // No need for @Data annotation as we are using record which is immutable

}
