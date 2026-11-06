package ch.bbw.trips.repo;

import com.fasterxml.jackson.annotation.JsonBackReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@Entity
@Table(name = "flight")
@AllArgsConstructor
@NoArgsConstructor
public class Flight implements Serializable {

	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;
	private Long number;
	private String cityFrom;
	private String cityTo;
	private LocalDateTime flightDate;



	@ManyToOne
	@JoinColumn(name="employee_idfs")
	@JsonBackReference
	@ToString.Exclude
	private Employee employee;


}
