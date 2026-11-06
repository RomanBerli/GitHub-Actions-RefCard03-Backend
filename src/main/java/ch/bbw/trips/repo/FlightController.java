package ch.bbw.trips.repo;

import ch.bbw.trips.dtos.FlightDto;
import ch.bbw.trips.ex.TriptNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class FlightController {

	private final FlightRepository flightRepository;

	FlightController(FlightRepository flightRepository) {
		this.flightRepository = flightRepository;
	}

	@GetMapping("/flights")
	ResponseEntity<List<FlightDto>> getFlights() {
		List<Flight> flights = (List<Flight>) flightRepository.findAll();
		List<FlightDto> flightDtos = flights.stream()
				.map(flight -> new FlightDto(flight.getId(), flight.getNumber(), flight.getCityFrom(), flight.getCityTo()))
				.toList();
		return ResponseEntity
				.status(flights.isEmpty() ? 204 : 200) // 204 No Content if empty, 200 OK if not
				.contentType(MediaType.APPLICATION_JSON)
				.body(flightDtos);
	}

	@GetMapping("/flights/{id}")
	ResponseEntity<FlightDto> getFlightDto(@PathVariable Long id) {
		Flight flight = flightRepository.findById(id).orElseThrow(() -> new TriptNotFoundException(id));
		FlightDto flightDto = new FlightDto(flight.getId(), flight.getNumber(), flight.getCityFrom(), flight.getCityTo());
		return ResponseEntity.ok(flightDto);
	}

	@PostMapping("/flights")
	Flight createFlight(@RequestBody Flight newItem) {
		return flightRepository.save(newItem);
	}

	@PutMapping("/flights/{id}")
	Flight updateFlight(@RequestBody Flight newItem, @PathVariable Long id) {
		return flightRepository.findById(id).map(item -> {
			item.setNumber(newItem.getNumber());
			item.setCityFrom(newItem.getCityFrom());
			item.setCityTo(newItem.getCityTo());
			return flightRepository.save(item);
		}).orElseGet(() -> {
			newItem.setId(id);
			return flightRepository.save(newItem);
		});
	}

	@DeleteMapping("/flights/{id}")
	void deleteFlight(@PathVariable Long id) {
		flightRepository.deleteById(id);
	}

	@DeleteMapping("/flights")
	void deleteAllFlights() {
		flightRepository.deleteAll();
	}

}
