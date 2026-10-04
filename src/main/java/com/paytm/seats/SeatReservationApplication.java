package com.paytm.seats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SeatReservationApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatReservationApplication.class, args);
	}

}
