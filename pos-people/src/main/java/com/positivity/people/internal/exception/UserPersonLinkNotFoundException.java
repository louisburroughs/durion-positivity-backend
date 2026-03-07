package com.positivity.people.internal.exception;

import java.util.UUID;

public class UserPersonLinkNotFoundException extends RuntimeException {

	public UserPersonLinkNotFoundException(UUID userId) {
		super("No person link found for user: " + userId);
	}

}
