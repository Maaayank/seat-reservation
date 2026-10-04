package com.paytm.seats.show;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

	private final ShowService service;

	public ShowController(ShowService service) {
		this.service = service;
	}

	/** Admin only (X-Admin-Key, enforced by AdminKeyFilter). */
	@PostMapping
	public ResponseEntity<ShowView> create(@Valid @RequestBody CreateShowRequest request) {
		ShowView show = this.service.create(request);
		return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
	}

	@GetMapping("/{id}")
	public ShowView get(@PathVariable String id) {
		return this.service.get(id);
	}

}
