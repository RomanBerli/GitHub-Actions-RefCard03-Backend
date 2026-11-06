package ch.bbw.trips.repo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.*;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
public class BusinessTrip implements Serializable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String title;
	private String description;
	private LocalDateTime startTrip;
	private LocalDateTime endTrip;

	@OneToMany(mappedBy = "businessTrip")
	@JsonManagedReference
	private List<Meeting> meetings;

	@ManyToMany(mappedBy = "trips")
	@JsonIgnore
	private List<Employee> employees;


	public BusinessTrip(Long id, String title, String description, LocalDateTime startTrip, LocalDateTime endTrip, List<Employee> employees) {
		this();
		this.id = id;
		this.title = title;
		this.description = description;
		this.startTrip = startTrip;
		this.endTrip = endTrip;
		this.employees = employees;
	}


	public BusinessTrip(Long id, String title, String description, LocalDateTime start, LocalDateTime end) {
		this();
		this.id = id;
		this.title = title;
		this.description = description;
		this.startTrip = start;
		this.endTrip = end;
	}
}
