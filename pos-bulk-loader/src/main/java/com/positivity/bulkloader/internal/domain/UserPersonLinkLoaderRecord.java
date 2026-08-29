package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One user-to-person link, with the person named by employee number. */
@Data
public class UserPersonLinkLoaderRecord {

    private String username;
    private String employeeNumber;

    /** Resolved from {@code employeeNumber}, or supplied directly. */
    private String personId;
}
