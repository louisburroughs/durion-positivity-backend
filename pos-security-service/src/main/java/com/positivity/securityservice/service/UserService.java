package com.positivity.securityservice.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;

public interface UserService {

    UserDto createUser(String username, String password, Set<String> roleNames);

    Optional<UserAuthContext> getUserByUsername(String username);

    Optional<UserDto> getUserById(UUID id);

    List<UserDto> getAllUsers();

    void deleteUser(UUID id);

    UserDto assignRoles(String username, Set<String> roleNames);

    UserDto updateUser(UUID id, UserUpdateRequest request);

}