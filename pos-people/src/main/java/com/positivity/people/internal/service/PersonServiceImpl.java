package com.positivity.people.internal.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.dto.Person;
import com.positivity.people.internal.dto.ResolvePersonRequest;
import com.positivity.people.internal.dto.ResolvePersonResponse;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.service.PersonService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonServiceImpl implements PersonService {

	private static final int EMAIL_WEIGHT = 60;

	private static final int PHONE_WEIGHT = 25;

	private static final int LAST_NAME_WEIGHT = 10;

	private static final int FIRST_NAME_WEIGHT = 5;

	private final PersonRepository personRepository;

	private final SecurityServiceClient securityServiceClient;

	@Value("${pos.people.matching.threshold:30}")
	private int defaultMatchingThreshold;

	@Override
	@NonNull public List<Person> getAllPeople() {
		return personRepository.findAll().stream().map(this::toDto).toList();
	}

	@Override
	@NonNull public Optional<Person> getPersonById(@NonNull UUID id) {
		return personRepository.findById(id).map(this::toDto);
	}

	@Override
	@Transactional
	@NonNull public Person savePerson(@NonNull Person person) {
		if (person.getUsername() != null && !person.getUsername().isBlank()
				&& !validateUsernameWithSecurityService(person.getUsername())) {
			throw new IllegalArgumentException("Username is not valid or does not exist in security service");
		}

		com.positivity.people.internal.entity.Person saved = personRepository.save(toEntity(person));
		return toDto(saved);
	}

	@Override
	@Transactional
	@NonNull public ResolvePersonResponse resolvePerson(@NonNull ResolvePersonRequest request) {
		String email = normalizeEmail(request.getEmail());
		String phone = normalizePhone(request.getPhone());
		String lastName = normalizeText(request.getLastName());
		String firstName = normalizeText(request.getFirstName());

		if (email == null && phone == null && lastName == null && firstName == null) {
			throw new IllegalArgumentException("At least one of email, phone, lastName, or firstName is required");
		}

		int threshold = resolveThreshold(request.getThreshold());
		Map<UUID, ScoredCandidate> candidates = new HashMap<>();

		if (email != null) {
			personRepository.findByPrimaryEmailIgnoreCase(email)
				.ifPresent(person -> addScore(candidates, person, EMAIL_WEIGHT, "EMAIL"));
			personRepository.findBySecondaryEmailIgnoreCase(email)
				.ifPresent(person -> addScore(candidates, person, EMAIL_WEIGHT, "EMAIL"));
		}

		if (phone != null) {
			personRepository.findByPhoneNumbersContains(phone)
				.forEach(person -> addScore(candidates, person, PHONE_WEIGHT, "PHONE"));
		}

		if (lastName != null) {
			personRepository.findByLastNameIgnoreCase(lastName)
				.forEach(person -> addScore(candidates, person, LAST_NAME_WEIGHT, "LAST_NAME"));
		}

		if (firstName != null) {
			personRepository.findByFirstNameIgnoreCase(firstName)
				.forEach(person -> addScore(candidates, person, FIRST_NAME_WEIGHT, "FIRST_NAME"));
		}

		Optional<ScoredCandidate> best = candidates.values()
			.stream()
			.max(Comparator.comparingInt(ScoredCandidate::getScore)
				.thenComparing(candidate -> candidate.getPerson().getUpdatedAt(),
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(candidate -> candidate.getPerson().getId()));

		if (best.isPresent() && best.get().getScore() >= threshold) {
			ScoredCandidate matched = best.get();
			return ResolvePersonResponse.builder()
				.personId(matched.getPerson().getId())
				.matchedExisting(true)
				.score(matched.getScore())
				.thresholdApplied(threshold)
				.matchedBy(new ArrayList<>(matched.getMatchedBy()))
				.firstName(matched.getPerson().getFirstName())
				.lastName(matched.getPerson().getLastName())
				.primaryEmail(matched.getPerson().getPrimaryEmail())
				.phoneNumbers(matched.getPerson().getPhoneNumbers())
				.build();
		}

		com.positivity.people.internal.entity.Person entity = new com.positivity.people.internal.entity.Person();
		entity.setFirstName(firstName);
		entity.setLastName(lastName);
		entity.setPrimaryEmail(email);
		if (phone != null) {
			entity.setPhoneNumbers(new ArrayList<>(List.of(phone)));
		}
		else {
			entity.setPhoneNumbers(new ArrayList<>());
		}

		com.positivity.people.internal.entity.Person created = personRepository.save(entity);
		return ResolvePersonResponse.builder()
			.personId(created.getId())
			.matchedExisting(false)
			.score(0)
			.thresholdApplied(threshold)
			.matchedBy(List.of("CREATED"))
			.firstName(created.getFirstName())
			.lastName(created.getLastName())
			.primaryEmail(created.getPrimaryEmail())
			.phoneNumbers(created.getPhoneNumbers())
			.build();
	}

	@Override
	public void deletePerson(@NonNull UUID id) {
		personRepository.deleteById(id);
	}

	private boolean validateUsernameWithSecurityService(String username) {
		return securityServiceClient.getUserByUsername(username).isPresent()
				&& !personRepository.existsByUsername(username);
	}

	private int resolveThreshold(Integer thresholdOverride) {
		int threshold = thresholdOverride != null ? thresholdOverride : defaultMatchingThreshold;
		return Math.max(0, threshold);
	}

	private void addScore(Map<UUID, ScoredCandidate> candidates, com.positivity.people.internal.entity.Person person,
			int score, String reason) {
		ScoredCandidate candidate = candidates.computeIfAbsent(person.getId(), ignored -> new ScoredCandidate(person));
		candidate.add(score, reason);
	}

	private String normalizeText(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private String normalizeEmail(String email) {
		String normalized = normalizeText(email);
		return normalized == null ? null : normalized.toLowerCase();
	}

	private String normalizePhone(String phone) {
		String normalized = normalizeText(phone);
		if (normalized == null) {
			return null;
		}
		String digits = normalized.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return null;
		}
		if (normalized.startsWith("+")) {
			return "+" + digits;
		}
		return digits;
	}

	private Person toDto(com.positivity.people.internal.entity.Person entity) {
		Person dto = new Person();
		dto.setId(entity.getId());
		dto.setFirstName(entity.getFirstName());
		dto.setLastName(entity.getLastName());
		dto.setPrimaryEmail(entity.getPrimaryEmail());
		dto.setSecondaryEmail(entity.getSecondaryEmail());
		dto.setPhoneNumbers(entity.getPhoneNumbers());
		dto.setUsername(entity.getUsername());
		return dto;
	}

	private com.positivity.people.internal.entity.Person toEntity(Person dto) {
		com.positivity.people.internal.entity.Person entity = new com.positivity.people.internal.entity.Person();
		entity.setId(dto.getId());
		entity.setFirstName(dto.getFirstName());
		entity.setLastName(dto.getLastName());
		entity.setPrimaryEmail(dto.getPrimaryEmail());
		entity.setSecondaryEmail(dto.getSecondaryEmail());
		entity.setPhoneNumbers(dto.getPhoneNumbers());
		entity.setUsername(dto.getUsername());
		return entity;
	}

	private static final class ScoredCandidate {

		private final com.positivity.people.internal.entity.Person person;

		private final Set<String> matchedBy = new LinkedHashSet<>();

		private int score;

		private ScoredCandidate(com.positivity.people.internal.entity.Person person) {
			this.person = person;
		}

		private void add(int delta, String reason) {
			this.score += delta;
			this.matchedBy.add(reason);
		}

		private com.positivity.people.internal.entity.Person getPerson() {
			return person;
		}

		private int getScore() {
			return score;
		}

		private Set<String> getMatchedBy() {
			return matchedBy;
		}

	}

}
