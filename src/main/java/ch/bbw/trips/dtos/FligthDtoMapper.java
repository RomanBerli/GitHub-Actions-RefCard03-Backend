package ch.bbw.trips.dtos;

import ch.bbw.trips.repo.Flight;

import java.util.List;
import java.util.stream.Collectors;

public class FligthDtoMapper {
    // from DTO to Entity
     public static Flight toEntity(FlightDto flightDto) {
         return Flight.builder()
                 .id(flightDto.id())
                 .number(flightDto.number())

                 .cityFrom(flightDto.cityFrom())
                 .cityTo(flightDto.cityTo())
                 .flightDate(flightDto.flightDate())
                 .employee(flightDto.employee())
                 .build();
     }
    // from Entity to DTO
        public static FlightDto toDto(Flight flight) {
            return FlightDto.builder()
                    .id(flight.getId())
                    .number(flight.getNumber())
                    .cityFrom(flight.getCityFrom())
                    .cityTo(flight.getCityTo())
                    .flightDate(flight.getFlightDate())
                    .employee(flight.getEmployee())
                    .build();
        }
    // list of entities to list of DTOs
    public static List<FlightDto> toDtoList(List<Flight> flights) {
        return flights.stream()
                .map(FligthDtoMapper::toDto)
                .collect(Collectors.toList());
    }
    // list of DTOs to list of entities
    public static List<Flight> toEntityList(List<FlightDto> flightDtos) {
        return flightDtos.stream()
                .map(FligthDtoMapper::toEntity)
                .collect(Collectors.toList());
    }
}
