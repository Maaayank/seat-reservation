package com.paytm.seats;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application on a random port against a shared Testcontainers Postgres.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "seats.admin-api-key=" + IntegrationTest.ADMIN_KEY,
				"seats.jwt-secret=" + IntegrationTest.JWT_SECRET,
				// Spring Boot disables metrics export in tests by default.
				"management.prometheus.metrics.export.enabled=true" })
public @interface IntegrationTest {

	String ADMIN_KEY = "test-admin-key";

	String JWT_SECRET = "test-jwt-secret-0123456789abcdef-0123456789";

}
