package com.paytm.seats.show;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /shows} body. {@code price_paise} is an integer number of paise; fractional
 * values are rejected at deserialisation. {@code per_user_limit} is optional (default
 * from config, 4).
 */
public record CreateShowRequest(@NotBlank @Size(max = 200) String name,
		@NotEmpty List<@NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,32}",
				message = "seat labels must be 1-32 chars of [A-Za-z0-9._-]") String> seats,
		@NotNull @PositiveOrZero Long pricePaise, @Positive @Max(100) Integer perUserLimit) {
}
