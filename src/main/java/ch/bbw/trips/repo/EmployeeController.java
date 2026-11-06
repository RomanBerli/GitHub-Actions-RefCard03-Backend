package ch.bbw.trips.repo;

import ch.bbw.trips.ex.TriptNotFoundException;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class EmployeeController {
	private static final Logger log = LoggerFactory.getLogger(EmployeeController.class);

	@Autowired
	private EmployeeRepository employeeRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@PostMapping("/signIn")
	@ResponseStatus(HttpStatus.OK)
	ResponseEntity<TokenResponse> signIn(@RequestBody Employee authenticationUser) {
		Optional<Employee> userOptional = employeeRepository.findUserByUsername(authenticationUser.getUsername());

		if (userOptional.isPresent()) {
			Employee user = userOptional.get();
			if (passwordEncoder.matches(authenticationUser.getPassword(), user.getPassword())) {
				String uuid = UUID.randomUUID().toString();
				user.setToken(uuid);
				employeeRepository.save(user);
				log.info("User logged in: " + user);

				TokenResponse response = new TokenResponse(uuid);
				return ResponseEntity
						.status(HttpStatus.OK)
						.header("Authorization", "Bearer " + uuid)
						.body(response);
			}
		}
		return ResponseEntity.notFound().build();
	}

	// POJO to represent the response body
	@Data
	@AllArgsConstructor
	private static class TokenResponse {
		private String token;
	}

	@GetMapping("/employees")
	List<Employee> allEmployees() {
		return (List<Employee>) employeeRepository.findAll();
	}

	@PostMapping("/employees")
	Employee createEmployee(@RequestBody Employee newItem) {
		if (newItem.getPassword() != null) {
			newItem.setPassword(passwordEncoder.encode(newItem.getPassword()));
		}
		return employeeRepository.save(newItem);
	}

	@GetMapping("/employees/{id}")
	Employee one(@PathVariable Long id) {
		return employeeRepository.findById(id).orElseThrow(() -> new TriptNotFoundException(id));
	}

	@PutMapping("/employees/{id}")
	Employee updateEmployee(@RequestBody Employee newItem, @PathVariable Long id) {
		return employeeRepository.findById(id).map(item -> {
			item.setName(newItem.getName());
			item.setJobTitle(newItem.getJobTitle());
			return employeeRepository.save(item);
		}).orElseGet(() -> {
			newItem.setId(id);
			return employeeRepository.save(newItem);
		});
	}

	@DeleteMapping("/employees/{id}")
	void deleteEmployee(@PathVariable Long id) {
		employeeRepository.deleteById(id);
	}

	@DeleteMapping("/employees")
	void deleteAllEmployees() {
		employeeRepository.deleteAll();
	}

}
