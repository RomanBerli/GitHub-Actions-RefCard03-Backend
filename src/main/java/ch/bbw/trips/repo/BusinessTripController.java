package ch.bbw.trips.repo;

import ch.bbw.trips.dtos.TripDto;
import ch.bbw.trips.ex.TriptNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class BusinessTripController {

	@Autowired
	private BusinessTripRepository tripRepository;

	@GetMapping("/trips")
	ResponseEntity<List<TripDto>> getTrips() {
		List<BusinessTrip> trips = tripRepository.findAll();
		List<TripDto> tripDtos = trips.stream()
				.map(trip -> new TripDto(trip.getId(), trip.getTitle(), trip.getDescription(), trip.getStartTrip(), trip.getEndTrip()))
				.toList();
		return ResponseEntity
				.status(trips.isEmpty() ? 204 : 200) // 204 No Content if empty, 200 OK if not
				.contentType(MediaType.APPLICATION_JSON)
				.body(tripDtos);
	}

	@PostMapping("/trips")
	ResponseEntity<BusinessTrip> newTrip(@RequestBody BusinessTrip newTrip) {
		BusinessTrip savedTrip = tripRepository.save(newTrip);
		return ResponseEntity.ok(savedTrip);
	}

	@GetMapping("/trips/{id}")
	BusinessTrip one(@PathVariable Long id) {
		return tripRepository.findById(id)
				.orElseThrow(() -> new TriptNotFoundException(id));
	}

	@DeleteMapping("/trips/{id}")
	void deleteTrip(@PathVariable Long id) {
		tripRepository.deleteById(id);
	}

}
