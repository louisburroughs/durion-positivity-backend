package com.positivity.people.controller;

import com.positivity.people.entity.Person;
import com.positivity.people.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "People API", description = "Operations related to people records")
@RestController
@RequestMapping("/api/people")
@RequiredArgsConstructor
public class PersonController {
    private final PersonService personService;

    @Operation(summary = "Get all people", description = "Retrieve a list of all people.")
    @ApiResponse(responseCode = "200", description = "List of people returned successfully.")
    @GetMapping
    public List<Person> getAllPeople() {
        return personService.getAllPeople();
    }

    @Operation(summary = "Get person by ID", description = "Retrieve a person by their unique ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Person found and returned."),
            @ApiResponse(responseCode = "404", description = "Person not found.")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Person> getPersonById(
            @Parameter(description = "ID of the person to retrieve", example = "1")
            @PathVariable Long id) {
        return personService.getPersonById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new person", description = "Add a new person to the system.")
    @ApiResponse(responseCode = "200", description = "Person created successfully.")
    @PostMapping
    public ResponseEntity<Person> createPerson(
            @Parameter(description = "Person object to be created")
            @RequestBody Person person) {
        Person saved = personService.savePerson(person);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Update an existing person", description = "Update the details of an existing person.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Person updated successfully."),
            @ApiResponse(responseCode = "404", description = "Person not found.")
    })
    @PutMapping("/{id}")
    public ResponseEntity<Person> updatePerson(
            @Parameter(description = "ID of the person to update", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Updated person object")
            @RequestBody Person person) {
        if (personService.getPersonById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        person.setId(id);
        Person updated = personService.savePerson(person);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete a person", description = "Delete a person by their unique ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Person deleted successfully."),
            @ApiResponse(responseCode = "404", description = "Person not found.")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePerson(
            @Parameter(description = "ID of the person to delete", example = "1")
            @PathVariable Long id) {
        if (personService.getPersonById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        personService.deletePerson(id);
        return ResponseEntity.noContent().build();
    }
}

