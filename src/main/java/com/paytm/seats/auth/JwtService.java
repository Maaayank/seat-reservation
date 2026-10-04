package com.paytm.seats.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paytm.seats.config.SeatsProperties;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies HS256 user tokens. The user id is the {@code sub} claim and is the
 * only identity the service trusts.
 */
@Component
public class JwtService {

	private final MACSigner signer;

	private final MACVerifier verifier;

	private final Duration ttl;

	private final Clock clock;

	public JwtService(SeatsProperties properties, Clock clock) {
		byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
		try {
			this.signer = new MACSigner(secret);
			this.verifier = new MACVerifier(secret);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("invalid JWT secret", ex);
		}
		this.ttl = properties.jwtTtl();
		this.clock = clock;
	}

	public Duration ttl() {
		return this.ttl;
	}

	public String issue(String userId) {
		Instant now = this.clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(userId)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(this.ttl)))
			.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		try {
			jwt.sign(this.signer);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("failed to sign token", ex);
		}
		return jwt.serialize();
	}

	/**
	 * Returns the user id of a valid token. Empty for any token that is malformed, not
	 * HS256, wrongly signed, expired, or has no subject.
	 */
	public Optional<String> verify(String token) {
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(this.verifier)) {
				return Optional.empty();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Date expiry = claims.getExpirationTime();
			String subject = claims.getSubject();
			if (expiry == null || !expiry.toInstant().isAfter(this.clock.instant()) || subject == null
					|| subject.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(subject);
		}
		catch (ParseException | JOSEException | IllegalStateException ex) {
			return Optional.empty();
		}
	}

}
