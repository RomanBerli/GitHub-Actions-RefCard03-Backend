package ch.bbw.trips.repo;

import ch.bbw.trips.ex.TriptNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class MeetingController {

	@Autowired
	private MeetingRepository meetingRepository;

	@GetMapping("/meetings")
	List<Meeting> allMeetings() {
		return (List<Meeting>) meetingRepository.findAll();
	}

	@PostMapping("/meetings")
	Meeting createMeeting(@RequestBody Meeting newItem) {
		return meetingRepository.save(newItem);
	}

	@GetMapping("/meetings/{id}")
	Meeting one(@PathVariable Long id) {
		return meetingRepository.findById(id).orElseThrow(() -> new TriptNotFoundException(id));
	}

	@PutMapping("/meetings/{id}")
	Meeting updateMeeting(@RequestBody Meeting newItem, @PathVariable Long id) {
		return meetingRepository.findById(id).map(item -> {
			item.setTitle(newItem.getTitle());
			item.setDescription(newItem.getDescription());
			return meetingRepository.save(item);
		}).orElseGet(() -> {
			newItem.setId(id);
			return meetingRepository.save(newItem);
		});
	}

	@DeleteMapping("/meetings/{id}")
	void deleteMeeting(@PathVariable Long id) {
		meetingRepository.deleteById(id);
	}

	@DeleteMapping("/meetings")
	void deleteAllMeetings() {
		meetingRepository.deleteAll();
	}

}
