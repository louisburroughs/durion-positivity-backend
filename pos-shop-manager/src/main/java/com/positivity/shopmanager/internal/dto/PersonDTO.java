package com.positivity.shopmanager.internal.dto;

import java.util.List;
import lombok.Data;

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
