package com.positivity.location.dto;

import lombok.Data;
import java.util.List;

@Data
public class PersonDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String primaryEmail;
    private String secondaryEmail;
    private List<String> phoneNumbers;
    private String username;
}

