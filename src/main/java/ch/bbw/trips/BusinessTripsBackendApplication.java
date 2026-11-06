package ch.bbw.trips;


import ch.bbw.trips.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class BusinessTripsBackendApplication {
	private static final Logger log = LoggerFactory.getLogger(BusinessTripsBackendApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(BusinessTripsBackendApplication.class, args);
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CommandLineRunner demoData(FlightRepository flightRepository, EmployeeRepository employeeRepository,
                                      BusinessTripRepository businessTripRepository, MeetingRepository meetingRepository,
                                      PasswordEncoder passwordEncoder) {
		// https://spring.io/guides/gs/accessing-data-jpa/
		return (args) -> {
			if (employeeRepository.count() > 0) {
				log.info("Demo data already present, skipping seeding.");
				return;
			}

			// acouple of employees
			Employee giuanne = new Employee(null, "Joe Sarnataro", "SW-Engineer", "joe", passwordEncoder.encode("1234"));
			Employee sam = new Employee(null, "Sam Sony", "Web-Architect");
			Employee gino = new Employee(null, "Gino Brambilla", "Design-Engineer");
			Employee joe = new Employee(null, "Joe Santo", "SW-Engineer");
			Employee susan = new Employee(null, "Susan de Vito", "UX-Engineer");

			// a couple of Flights
			Flight zhsf = new Flight(null, 4711L, "ZH", "SF", LocalDateTime.of(2026, 2, 13, 07, 56), giuanne);
			Flight sfzh = new Flight(null, 4719L, "SF", "ZH", LocalDateTime.of(2026, 2, 15, 15, 56),giuanne);
			Flight amsf = new Flight(null, 4711L, "AM", "SF", LocalDateTime.of(2026, 2, 13, 15, 56),sam);
			Flight sfam = new Flight(null, 4799L, "SF", "AM", LocalDateTime.of(2026, 2, 15, 15, 56),sam);
			Flight rmsf = new Flight(null, 4788L, "RM", "SF", LocalDateTime.of(2026, 2, 13, 15, 56), gino);
			Flight sfrm = new Flight(null, 4757L, "SF", "RM", LocalDateTime.of(2026, 2, 15, 15, 56),gino);
			Flight lnsf = new Flight(null, 4711L, "AM", "SF", LocalDateTime.of(2026, 2, 13, 15, 56),joe);
			Flight sfln = new Flight(null, 4799L, "SF", "AM", LocalDateTime.of(2026, 2, 15, 15, 56),joe);
			Flight misf = new Flight(null, 4788L, "RM", "SF", LocalDateTime.of(2026, 2, 13, 15, 56),susan);
			Flight sfmi = new Flight(null, 4757L, "SF", "RM",LocalDateTime.of(2026, 2, 15, 15, 56), susan);

			employeeRepository.save(giuanne);
			employeeRepository.save(sam);
			employeeRepository.save(gino);
			employeeRepository.save(joe);
			employeeRepository.save(susan);

			flightRepository.save(zhsf);
			flightRepository.save(sfzh);
			flightRepository.save(amsf);
			flightRepository.save(sfam);
			flightRepository.save(rmsf);
			flightRepository.save(sfrm);

			// save a couple of BusinessTrips
			BusinessTrip bt01 = new BusinessTrip(null, "BT01", "San Francisco World Trade Center on new Server/IOT/Client ",  LocalDateTime.of(2026, 02, 13, 00, 00),  LocalDateTime.of(2026, 2, 15, 16, 56));
			BusinessTrip bt02 = new BusinessTrip(null, "BT02", "Santa Clara Halley on new Server/IOT/Client", LocalDateTime.of(2026, 6, 23, 9, 00),  LocalDateTime.of(2026, 6, 27, 16, 56));
			BusinessTrip bt03 = new BusinessTrip(null, "BT03", "San Cose City Halley on Docker/IOT/Client", LocalDateTime.of(2026, 12, 13, 9, 00),  LocalDateTime.of(2026, 12, 15, 16, 56));

			businessTripRepository.save(bt01);
			businessTripRepository.save(bt02);
			businessTripRepository.save(bt03);

			// save a couple of meetings

			Meeting one = new Meeting(null, "One Conference", "Key Note on One Conference", bt01);
			Meeting zero = new Meeting(null, "Zero Conference", "Workshop Zero on One Conference", bt01);
			Meeting handsOn = new Meeting(null, "One Conference", "HandsOn on One Conference", bt02);
			Meeting workOn = new Meeting(null, "One Conference", "Key Note on One Conference", bt02);
			Meeting stayTuned = new Meeting(null, "One Conference", "Key Note on One Conference", bt03);

			meetingRepository.save(one);
			meetingRepository.save(zero);
			meetingRepository.save(handsOn);
			meetingRepository.save(workOn);
			meetingRepository.save(stayTuned);


			// List<Trips>

			List<BusinessTrip> wishTrips = new ArrayList<BusinessTrip>();

			wishTrips.add(bt01);
			wishTrips.add(bt02);

			gino.setTrips(wishTrips);
			employeeRepository.save(gino);



			// fetch all products
//			log.info("Employees found with findAll()");
//			log.info("----------------------------");
//			for (Employee item : employeeRepository.findAll()) {
//				log.info(item.toString());
//			}
//			log.info("end findAll()");
//			// fetch an individual product by Id
//			employeeRepository.findById(1L).ifPresent(employee -> {
//				log.info("Employee find with findById(1L)");
//				log.info("------------------------------");
//				log.info(employee.toString());
//				log.info("");
//			});
//			// fetch products by name
//			log.info("Employee found by Name ('Gino')");
//			log.info("-------------------------------------");
//			employeeRepository.findByName("Gino").forEach(couch -> {
//				log.info(couch.toString());
//			});
//			for (Employee gino1 : employeeRepository.findByName("Gino")) {
//				log.info(gino1.toString());
//			}
//			log.info("");

		};
	}

	@Bean
	public CommandLineRunner demoProduct(BusinessTripRepository repository) {
		// https://spring.io/guides/gs/accessing-data-jpa/
		return (args) -> {
//			// save a couple of products
//			repository.save(new BusinessTrip(1L, "The Dash", new BigDecimal("399.00"), 5));
//			repository.save(new BusinessTrip(1L, "Couch Sofia", new BigDecimal("1349.00"), 1));
//			repository.save(new BusinessTrip(1L, "Fender Telecaster", new BigDecimal("10099.00"), 3));
//			repository.save(new BusinessTrip(1L, "Office Chair USM", new BigDecimal("55.90"), 24));
//			repository.save(new BusinessTrip(1L, "Sunglasses Ray Ban", new BigDecimal("99.95"), 35));
//			repository.save(new BusinessTrip(1L, "Wireless Headphones", new BigDecimal("349.00"), 44));

			// fetch all products
//			log.info("Products found with findAll()");
//			log.info("----------------------------");
//			for (Product product : repository.findAll()) {
//				log.info(product.toString());
//			}
//			log.info("end findAll()");
//			// fetch an individual product by Id
//			repository.findById(1L)
//				.ifPresent(product -> {
//				log.info("Product find with findById(1L)");
//				log.info("------------------------------");
//				log.info(product.toString());
//				log.info("");
//			});
//			// fetch products by name
//			log.info("Product found by Name ('Couch Sofia')");
//			log.info("-------------------------------------");
//			repository.findByName("Couch Sofia").forEach(couch ->    {
//				log.info(couch.toString());
//			});
//			for (Product couch : repository.findByName("Couch Sofia")) {
//				log.info(couch.toString());
//			}
			log.info("");

		};
	}
}
