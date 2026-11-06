package ch.bbw.trips.dtos;

import ch.bbw.trips.repo.BusinessTrip;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class TripDtoMapper     {

        // from DTO to Entity
    public BusinessTrip toEntity(TripDto tripDto) {
        return BusinessTrip.builder()
                .title(tripDto.title())
                .description(tripDto.description())
                .startTrip(tripDto.startTrip())
                .endTrip(tripDto.endTrip())
                .build();
    }
        // from Entity to DTO
    public TripDto toDto(BusinessTrip businessTrip) {
        return TripDto.builder()
                .title(businessTrip.getTitle())
                .description(businessTrip.getDescription())
                .startTrip(businessTrip.getStartTrip())
                .endTrip(businessTrip.getEndTrip())
                .build();
    }
    // list of entities to list of DTOs
    public List<TripDto> toDtoList(List<BusinessTrip> businessTrips) {
        return businessTrips.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }
    // list of DTOs to list of entities
    public List<BusinessTrip> toEntityList(List<TripDto> tripDtos) {
        return tripDtos.stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

}
