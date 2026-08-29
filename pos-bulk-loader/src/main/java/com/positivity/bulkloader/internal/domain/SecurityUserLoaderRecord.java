package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * One user account to provision.
 *
 * <p>No password field, by design: a bulk file is uploaded and stored, so a password in it would
 * exist at rest for as long as the upload does. The owning service generates one instead.
 */
@Data
public class SecurityUserLoaderRecord {

    private String username;

    /** Semicolon-separated role names, e.g. {@code TECHNICIAN;SHOP_MGR}. */
    private String roles;
}
