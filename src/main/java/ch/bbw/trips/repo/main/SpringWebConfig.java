package ch.bbw.trips.repo.main;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SpringWebConfig implements WebMvcConfigurer {
	/**
	 * CORS - Policy - from known Servers
	 */
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/v1/**")
				.allowedOrigins("http://localhost:3000", "http://localhost:3001", "http://localhost:5173")
				.allowedMethods("GET", "POST", "PUT", "DELETE")
				.allowedHeaders("Authorization", "Content-Type")
				.exposedHeaders("Authorization");
	}
}
