package ch.bbw.trips.repo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@AllArgsConstructor
public class Employee implements Serializable {


	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String name;
	private String jobTitle;
	private String username;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String password;

	@JsonIgnore
	private String token;

	@OneToMany(mappedBy = "employee")
	@JsonManagedReference
	private List<Flight> flights;


	@ManyToMany
	@JoinTable(name = "trip_employee",
	joinColumns = @JoinColumn(name = "employee_id_fs"),
	inverseJoinColumns = @JoinColumn(name = "trip_id_fs"))
	private List<BusinessTrip> trips;

	public Employee() {
		super();
		flights = new ArrayList<Flight>();

	}

	public Employee(Long id, String name, String jobTitle) {
		this();
		this.id = id;
		this.name = name;
		this.jobTitle = jobTitle;

	}
	public Employee(Long id, String name, String jobTitle, String username, String password) {
		this();
		this.id = id;
		this.name = name;
		this.jobTitle = jobTitle;
		this.username = username;
		this.password = password;
	}

	public Employee(Long id, String name, String jobTitle, List<Flight> flights) {
		this();
		this.id = id;
		this.name = name;
		this.jobTitle = jobTitle;
		this.flights = flights;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getJobTitle() {
		return jobTitle;
	}

	public void setJobTitle(String jobTitle) {
		this.jobTitle = jobTitle;
	}

	public List<Flight> getFlights() {
		return flights;
	}

	public void setFlights(List<Flight> flights) {
		this.flights = flights;
	}



	public List<BusinessTrip> getTrips() {
		return trips;
	}

	public void setTrips(List<BusinessTrip> trips) {
		this.trips = trips;
	}

	@Override
	public String toString() {
		return "Employee [id=" + id + ", name=" + name + ", jobTitle=" + jobTitle + ", flights=" + flights + "]";
	}

}
