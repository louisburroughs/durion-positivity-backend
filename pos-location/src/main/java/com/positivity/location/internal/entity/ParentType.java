package com.positivity.location.internal.entity;

public enum ParentType {
    HOME_OFFICE, // A location's primary parent, used for inheritance of attributes and as the
                 // default for reporting and other purposes
    HEADQUARTERS, // A location's main parent that represents the top of its organizational
                  // hierarchy, used for organizational reporting and other purposes
    REGION, // A location's parent that represents a geographic region, used for geographic
            // reporting and other purposes
    DISTRICT, // A location's parent that represents a district, used for district-level
              // reporting and other purposes
    PHYSICAL, // A location's parent that represents a physical entity, used for physical
              // asset management and other purposes
    ORGANIZATIONAL, // A location's parent that represents an organizational unit, used for
                    // organizational reporting and other purposes
    FINANCIAL, // A location's parent that represents a financial entity, used for financial
               // reporting and other purposes
    SHIPPING// A location's parent that represents a shipping entity, used for logistics and
            // shipping purposes
}
